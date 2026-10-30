#!/usr/bin/env bash
# End-to-end check of the MCP connector on a throwaway stack: Postgres 18 and Redis 8 in disposable
# containers on random loopback ports, and the app with the local profile on port 8091 (issuer
# http://localhost:8091). The local dev stack (docker/docker-compose.yml, its db volume, ports 5432,
# 6379 and 8080) is never touched. Everything is removed on exit.
#
# Needs Docker, Java 25 (java and jar), Maven, Node (node and npx), jq, curl and openssl. The MCP
# Inspector's own sign-in uses the macOS `open` shim in scripts/mcp-e2e/fake-browser, so that step runs
# on macOS only.
# Section 15 asks for the passkey sign-in and registration options with curl and checks 028's columns.
# Section 16 checks QR scans, the public numbers (with their 60-per-hour limit) and the burst alert. The
# alert goes to a local Node stub on a random loopback port: its URL is passed on the command line, which
# outranks DISCORD_WEBHOOK_URL, so the real webhook is never called.
# Section 17 checks bio pages on Postgres: racing creates, the page lock's SQL and what waits behind it, saves from an
# up-to-date and an outdated copy, the public page, the /@handle redirect, view counting, a click from the page, a report,
# the admin search, hide and revoke, and the export.
# Section 18 deletes API keys on Postgres: an active key refused, one revoked key, the bulk delete of inactive keys for
# the owner and (leaving the owner's keys alone) for an admin, and an admin deleting someone else's key.
# Section 19 reads the admin user list and edits a user: the JSON fields of a row and its roles, and the edit's answer.
# Section 20 runs what broke when open-in-view was turned off: a link created with an API key, an owner reactivating and
# editing their link, and the answers of a password change and a password reset, which carry the roles.
# Section 21 checks notifications and consent: the email toggles, an unsubscribe token signed in the shell with the local
# profile's dev key (GET only redirects, the RFC 8058 one-click POST revokes), a channel refused for a non-public address and
# one saved sealed and masked, a dead destination in the owner's list but not the public answer, deleting the channel (its
# consent history stays), the export and the admin counts. Channels are only saved; nothing is ever posted to them.
# Section 22 checks custom domains against scripts/mcp-e2e/fake-cloudflare.py, started on a random loopback port and passed
# as --app.cloudflare.api-base, so the real Cloudflare API is never called: an application and the refusals, the token
# check, approval and the automatic setup, the CNAME seen, links on the domain beside the same alias on the primary host,
# the Host header routing and its 404s, turning the domain off and on, the removal with its links moved and the queued
# Cloudflare delete, the three emails (a Mailpit container on a random port is the app's SMTP server) and the export.
# Section 23 checks campaign holds: links without an account (distinct CF-Connecting-IP values, so the 10-per-hour create limit is
# never reached) to one URL shape on three domains are held as CAMPAIGN (three url rows, one campaign row, one Campaign held: line at
# the stub, no campaign:alerted: key), a fourth link is answered active but held, and index.php?id=1 on three domains is a generic
# path that makes no row and stays active.
# Section 24 checks the admin settings: the Settings list as an admin and a user, ANONYMOUS_LINKS off (the sign-in refusal, the public
# switches, the Discord lines naming the admin), CUSTOM_ALIASES off, NEW_SIGNUPS off (the email sign-up refusal), SEND_EMAILS off (an
# admin domain block of the user's link logs a SKIPPED email_log row), and CAMPAIGN_HOLD off with CAMPAIGN_LINKS=4 (three same-shape
# links give no line, the fourth gives the alert-only line and nothing is held). Every setting is reset.
# Section 25 checks the Campaigns API (list, detail, links, block-preview, allow and a new link that stays active) and abuse reports
# (prepare with seeded rdap:info: values, so no registry is asked, and the /reported upsert with its audit row).
#
#   scripts/mcp-e2e.sh                         builds the jar, then runs every step
#   MCP_E2E_SKIP_BUILD=1 scripts/mcp-e2e.sh    reuses target/url-shortener-*.jar
#   MCP_E2E_PORT=8092 scripts/mcp-e2e.sh       another app port
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$SCRIPT_DIR")
# shellcheck source-path=SCRIPTDIR source=mcp-e2e/lib.sh
source "$SCRIPT_DIR/mcp-e2e/lib.sh"

PORT=${MCP_E2E_PORT:-8091}
CALLBACK_PORT=${MCP_E2E_CALLBACK_PORT:-6296}
INSPECTOR=${MCP_E2E_INSPECTOR:-@modelcontextprotocol/inspector@2.11.0}
BASE=http://localhost:$PORT
RESOURCE=$BASE/mcp
FRONTEND_URL=http://localhost:3000
USER_EMAIL=user@localhost
USER_PASSWORD=user123
RUN_ID=hrva-mcp-e2e-$$
POSTGRES_CONTAINER=$RUN_ID-postgres
REDIS_CONTAINER=$RUN_ID-redis
MAIL_CONTAINER=$RUN_ID-mail
DB_USER=e2e
DB_PASSWORD=$(openssl rand -hex 16)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/hrva-mcp-e2e.XXXXXX")
APP_PID=
INSPECTOR_PID=
STUB_PID=
CF_PID=
DISCORD_SECRET=e2e-$(openssl rand -hex 8)
DISCORD_POSTS=$WORK/discord-posts.jsonl
BROWSER_USER_AGENT='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
LOCK_HOLDER_PID=
BG_PIDS=()
INITIALIZE='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
TOOLS_LIST='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

export MCP_E2E_WORK=$WORK

cleanup() {
    local status=$?; trap '' INT TERM
    trap - EXIT
    if [[ -n $INSPECTOR_PID ]]; then
        kill -- "-$INSPECTOR_PID" 2>/dev/null || true
    fi
    if [[ -n $APP_PID ]] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    if [[ -n $STUB_PID ]]; then
        kill "$STUB_PID" 2>/dev/null || true
    fi
    if [[ -n $CF_PID ]]; then
        kill "$CF_PID" 2>/dev/null || true
    fi
    docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" "$MAIL_CONTAINER" >/dev/null 2>&1 || true
    if (( status != 0 )) && [[ -s $WORK/app.log ]]; then
        echo "--- last 60 lines of the app log"
        tail -n 60 "$WORK/app.log" | redact
    fi
    if (( status != 0 )) && [[ -s $WORK/fake-cloudflare.log ]]; then
        echo "--- last 20 lines of the fake Cloudflare log"
        tail -n 20 "$WORK/fake-cloudflare.log"
    fi
    rm -rf "$WORK"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

section() {
    printf '\n### %s\n' "$*"
}

ok() {
    printf '  ok: %s\n' "$*"
}

expect_eq() {
    [[ $1 == "$2" ]] || fail "$3: expected '$2', got '$1'"
    ok "$3"
}

expect_contains() {
    [[ $1 == *"$2"* ]] || fail "$3: '$2' not found in: $1"
    ok "$3"
}

show() {
    printf '%s\n' "$*" | redact | sed 's/^/  /'
}

host_port() {
    docker port "$1" "$2/tcp" | head -n1 | sed 's/.*://'
}

wait_until() {
    local seconds=$1 what=$2 second
    shift 2
    for (( second = 0; second < seconds; second++ )); do
        if "$@" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    fail "$what was not ready within ${seconds}s"
}

psql_query() {
    docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d url_shortener -P pager=off -c "$1" | sed 's/^/  /'
}

psql_value() {
    docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d url_shortener -tAq -c "$1"
}

# psql_has_referrer_row <short link>: the click recorder writes link_daily_stats first and link_daily_breakdown second, each
# committed on its own, so a click is fully recorded once its REFERRER breakdown row exists.
psql_has_referrer_row() {
    (( $(psql_value "select count(*) from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = '$1' and b.dimension = 'REFERRER';") >= 1 ))
}

# http_bg <name> <curl arguments…> sends a request in the background; wait_bg waits for all of them, then bg_status <name>
# and bg_body <name> read each answer.
http_bg() {
    local name=$1
    shift
    curl -sS -o "$WORK/$name.body" -w '%{http_code}' "$@" >"$WORK/$name.status" &
    BG_PIDS+=($!)
}

wait_bg() {
    local pid
    for pid in "${BG_PIDS[@]}"; do
        wait "$pid" || fail "a background request failed"
    done
    BG_PIDS=()
}

bg_status() {
    cat "$WORK/$1.status"
}

bg_body() {
    cat "$WORK/$1.body"
}

# hold_page_lock <page id> <lock clause> <seconds> locks a bio_page row from its own psql session, as a save in progress
# does, and returns once the lock is held: another session's FOR UPDATE NOWAIT on the row must be refused.
# release_page_lock waits for that session to commit.
hold_page_lock() {
    local tries probe
    docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d url_shortener -q -v ON_ERROR_STOP=1 \
        -c 'begin' -c "select id from bio_page where id = $1 $2" -c "select pg_sleep($3)" -c 'commit' >/dev/null &
    LOCK_HOLDER_PID=$!
    for (( tries = 0; tries < 50; tries++ )); do
        if [[ $(psql_value "select count(*) from pg_stat_activity where query = 'select pg_sleep($3)' and state = 'active';") == 1 ]]; then
            if probe=$(psql_value "select id from bio_page where id = $1 for update nowait;" 2>&1); then
                fail "the psql session does not hold the bio_page row: FOR UPDATE NOWAIT got it ($probe)"
            fi
            expect_contains "$probe" "could not obtain lock on row in relation \"bio_page\"" \
                "the psql session holds bio_page $1 \"$2\": another session's FOR UPDATE NOWAIT is refused"
            return 0
        fi
        sleep 0.1
    done
    fail "the bio_page row was not locked within 5s"
}

release_page_lock() {
    wait "$LOCK_HOLDER_PID" || fail "the psql session holding the bio_page lock failed"
    LOCK_HOLDER_PID=
}

# expect_lock_wait <seconds a request took> <what>: an insert that references the page takes FOR KEY SHARE on its row, which
# waits behind FOR UPDATE and not behind FOR NO KEY UPDATE.
expect_lock_wait() {
    if [[ $LOCK_CLAUSE == "for update" ]]; then
        awk -v seconds="$1" 'BEGIN { exit !(seconds >= 1.5) }' || fail "$2 took ${1}s; behind FOR UPDATE it should have waited"
        ok "$2 waited ${1}s, until the lock was released"
    else
        awk -v seconds="$1" 'BEGIN { exit !(seconds < 1) }' || fail "$2 took ${1}s; FOR NO KEY UPDATE should not block it"
        ok "$2 did not wait (${1}s)"
    fi
}

# mcp_post <path> <access token, or empty for none> <JSON-RPC body>
mcp_post() {
    if [[ -n $2 ]]; then
        http -X POST "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -H "Accept: $MCP_ACCEPT" -d "$3"
    else
        http -X POST "$BASE$1" -H 'Content-Type: application/json' -H "Accept: $MCP_ACCEPT" -d "$3"
    fi
}

# inspector <deadline seconds> <Inspector CLI arguments…> runs the MCP Inspector CLI with its state in $WORK.
# Its stderr is shown; its JSON result lands in INSPECTOR_RESULT. Fails on a non-zero exit or the deadline.
inspector() {
    local seconds=$1 result=$WORK/inspector.json errors=$WORK/inspector.err status=0 second
    shift
    set -m
    MCP_STORAGE_DIR=$WORK/inspector \
        MCP_CATALOG_PATH=$WORK/inspector/mcp.json \
        MCP_CLIENT_CONFIG_PATH=$WORK/inspector/client.json \
        MCP_INSPECTOR_SECRET_STORE=file \
        NO_COLOR=1 \
        npx -y "$INSPECTOR" --cli "$@" --format json >"$result" 2>"$errors" </dev/null &
    INSPECTOR_PID=$!
    set +m
    for (( second = 0; second < seconds; second++ )); do
        kill -0 "$INSPECTOR_PID" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$INSPECTOR_PID" 2>/dev/null; then
        redact <"$errors"
        fail "the Inspector did not finish within ${seconds}s"
    fi
    wait "$INSPECTOR_PID" || status=$?
    INSPECTOR_PID=
    grep -Ev '^npm notice|Secrets are stored unencrypted|How the secret store is chosen' "$errors" | redact | sed 's/^/  [inspector] /' || true
    INSPECTOR_RESULT=$(jq -c '.result' "$result" 2>/dev/null || cat "$result")
    (( status == 0 )) || fail "the Inspector exited with status $status: $(redact <<<"$INSPECTOR_RESULT")"
}

show_tool_result() {
    show "isError=$(jq -r .isError <<<"$INSPECTOR_RESULT")"
    show "$(tool_text)"
}

tool_text() {
    jq -r '[.content[]? | select(.type == "text") | .text] | join("\n")' <<<"$INSPECTOR_RESULT"
}

for tool in docker java jar mvn node npx jq curl openssl python3; do
    command -v "$tool" >/dev/null || fail "$tool is required"
done
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    fail "port $PORT is already in use; set MCP_E2E_PORT"
fi
if (exec 3<>"/dev/tcp/127.0.0.1/$CALLBACK_PORT") 2>/dev/null; then
    fail "port $CALLBACK_PORT (Inspector OAuth callback) is already in use; set MCP_E2E_CALLBACK_PORT"
fi

section "Throwaway stack"
docker run -d --rm --name "$POSTGRES_CONTAINER" --label hrva-mcp-e2e \
    -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" -e POSTGRES_DB=url_shortener \
    -p 127.0.0.1::5432 postgres:18 >/dev/null
docker run -d --rm --name "$REDIS_CONTAINER" --label hrva-mcp-e2e -p 127.0.0.1::6379 redis:8 >/dev/null
docker run -d --rm --name "$MAIL_CONTAINER" --label hrva-mcp-e2e -p 127.0.0.1::1025 axllent/mailpit >/dev/null
POSTGRES_PORT=$(host_port "$POSTGRES_CONTAINER" 5432)
REDIS_PORT=$(host_port "$REDIS_CONTAINER" 6379)
MAIL_PORT=$(host_port "$MAIL_CONTAINER" 1025)
wait_until 60 "Postgres" docker exec "$POSTGRES_CONTAINER" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d url_shortener
wait_until 30 "Redis" docker exec "$REDIS_CONTAINER" redis-cli ping
wait_until 30 "Mailpit" bash -c "exec 3<>/dev/tcp/127.0.0.1/$MAIL_PORT"
echo "  containers $RUN_ID-{postgres,redis,mail}: Postgres 18 on 127.0.0.1:$POSTGRES_PORT, Redis 8 on 127.0.0.1:$REDIS_PORT, Mailpit SMTP on 127.0.0.1:$MAIL_PORT"

CF_PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
python3 "$SCRIPT_DIR/mcp-e2e/fake-cloudflare.py" --port "$CF_PORT" 2>"$WORK/fake-cloudflare.log" &
CF_PID=$!
CF_BASE=http://127.0.0.1:$CF_PORT
wait_until 15 "the fake Cloudflare" curl -sf "$CF_BASE/_control/calls"
echo "  fake Cloudflare on $CF_BASE (the app's Cloudflare API; the real one is never called)"

: >"$DISCORD_POSTS"
node - "$WORK/discord-stub.port" "$DISCORD_POSTS" <<'JS' &
const fs = require('node:fs');
const [portFile, postsFile] = process.argv.slice(2);
const server = require('node:http').createServer((request, response) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
        if (request.method === 'POST') {
            fs.appendFileSync(postsFile, Buffer.concat(chunks).toString('utf8').replace(/\n/g, ' ') + '\n');
        }
        response.writeHead(204).end();
    });
});
server.listen(0, '127.0.0.1', () => fs.writeFileSync(portFile, String(server.address().port)));
JS
STUB_PID=$!
wait_until 15 "the Discord stub" test -s "$WORK/discord-stub.port"
DISCORD_WEBHOOK=http://127.0.0.1:$(cat "$WORK/discord-stub.port")/api/webhooks/1/$DISCORD_SECRET
echo "  Discord stub on ${DISCORD_WEBHOOK%/api/*} (the app's webhook; the real one is never called)"

if [[ ${MCP_E2E_SKIP_BUILD:-0} != 1 ]]; then
    echo "  building the jar (mvn -B -DskipTests package)"
    (cd "$ROOT" && mvn -B -DskipTests package >"$WORK/build.log" 2>&1) || { tail -n 40 "$WORK/build.log"; fail "the build failed"; }
fi
JAR=$(find "$ROOT/target" -maxdepth 1 -name 'url-shortener-*.jar' ! -name '*-plain.jar' | head -n1)
[[ -n $JAR ]] || fail "no jar in target/; run without MCP_E2E_SKIP_BUILD"

OAUTH2_GOOGLE_CLIENT_ID=${OAUTH2_GOOGLE_CLIENT_ID:-mcp-e2e-unused} \
    OAUTH2_GOOGLE_CLIENT_SECRET=${OAUTH2_GOOGLE_CLIENT_SECRET:-mcp-e2e-unused} \
    java -jar "$JAR" \
    --spring.profiles.active=local \
    --server.port="$PORT" \
    --app.mcp.issuer="$BASE" \
    --app.server-url="$BASE" \
    --app.discord-webhook-url="$DISCORD_WEBHOOK" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/url_shortener" \
    --spring.datasource.username="$DB_USER" \
    --spring.datasource.password="$DB_PASSWORD" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port="$REDIS_PORT" \
    --spring.mail.host=127.0.0.1 \
    --spring.mail.port="$MAIL_PORT" \
    --app.cloudflare.api-base="$CF_BASE/client/v4" \
    --app.cloudflare.api-token=e2e-fake \
    --app.cloudflare.zone-id=e2e-zone \
    >"$WORK/app.log" 2>&1 &
APP_PID=$!
for (( second = 0; second < 180; second++ )); do
    kill -0 "$APP_PID" 2>/dev/null || fail "the app exited during startup"
    [[ $(curl -s -o /dev/null -w '%{http_code}' "$BASE/.well-known/oauth-authorization-server") == 200 ]] && break
    sleep 1
done
(( second < 180 )) || fail "the app did not start within 180s"
echo "  app $(basename "$JAR") on $BASE (profile local, issuer $BASE)"
expect_eq "$(grep -c 'spring.jpa.open-in-view is enabled by default' "$WORK/app.log" || true)" 0 "startup does not warn about open-in-view"
expect_eq "$(psql_value "select string_agg(id || ' ' || exectype, ', ' order by orderexecuted) from databasechangelog where id like '026-drop-ip-address%';")" \
    "026-drop-ip-address EXECUTED, 026-drop-ip-address-seq EXECUTED" "026 ran both ip_address drops"
expect_eq "$(psql_value "select (select count(*) from information_schema.tables where table_name = 'ip_address') + (select count(*) from information_schema.sequences where sequence_name = 'ip_address_id_seq');")" \
    0 "no ip_address table or sequence is left"
expect_eq "$(psql_value "select count(*) from databasechangelog where id like '027-%' and exectype = 'EXECUTED';")" 9 "027 ran all nine changesets"
expect_eq "$(psql_value "select string_agg(id || ' ' || exectype, ', ' order by orderexecuted) from databasechangelog where id like '030-%';")" \
    "030-url-short-url-rename-duplicates EXECUTED, 030-url-short-url-unique MARK_RAN" "030 finds no duplicate to rename and keeps the index 027 made"
expect_eq "$(psql_value "select count(*) from databasechangelog where id like '032-%' and exectype = 'EXECUTED';")" 8 \
    "032 ran all eight changesets, the Postgres 15 check included"
expect_eq "$(psql_value "select count(*) from pg_indexes where indexname = 'ux_url_short_url';") $(psql_value "select count(*) from pg_indexes where indexname = 'ux_url_domain_short_url' and indexdef like '%NULLS NOT DISTINCT%';")" \
    "0 1" "032 replaced ux_url_short_url with ux_url_domain_short_url (domain_id, short_url) NULLS NOT DISTINCT"

section "0. Discovery"
mcp_post /mcp "" "$INITIALIZE"
expect_eq "$HTTP_STATUS" 401 "POST /mcp without a token is refused"
show "WWW-Authenticate: $(header_value www-authenticate)"
expect_contains "$(header_value www-authenticate)" "resource_metadata=\"$BASE/.well-known/oauth-protected-resource/mcp\"" "401 points at the protected resource metadata"
http "$BASE/.well-known/oauth-protected-resource/mcp"
show "$(jq -c '{resource, authorization_servers}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r .resource <<<"$HTTP_BODY")" "$RESOURCE" "resource is \${issuer}/mcp"
http "$BASE/.well-known/oauth-authorization-server"
show "$(jq -c '{issuer, authorization_endpoint, token_endpoint, registration_endpoint, token_endpoint_auth_methods_supported, code_challenge_methods_supported}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r .issuer <<<"$HTTP_BODY")" "$BASE" "issuer is app.mcp.issuer"

section "1. Dynamic client registration (curl, shaped like Claude Code: public client, localhost callback, scope)"
REGISTRATION='{"client_name":"hrva e2e (curl)","redirect_uris":["http://localhost:53682/callback"],"grant_types":["authorization_code","refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none","scope":"claudeai"}'
show "request: $REGISTRATION"
http -X POST "$BASE/oauth2/register" -H 'Content-Type: application/json' -d "$REGISTRATION"
expect_eq "$HTTP_STATUS" 201 "client registered"
show "response: $(jq -c 'with_entries(if (.key | test("secret|registration_access_token")) then .value = "<redacted>" else . end)' <<<"$HTTP_BODY")"
CLIENT_ID=$(jq -r .client_id <<<"$HTTP_BODY")
expect_eq "$(jq -r .token_endpoint_auth_method <<<"$HTTP_BODY")" none "public client (no secret)"

section "2. Authorization request without a session -> 302 to the frontend /connect page"
VERIFIER=$(openssl rand -hex 32)
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | b64url)
STATE=$(openssl rand -hex 8)
REDIRECT_URI=http://localhost:61111/callback
COOKIES=$WORK/curl-cookies
AUTHORIZE_URL="$BASE/oauth2/authorize?response_type=code&client_id=$(urlencode "$CLIENT_ID")&redirect_uri=$(urlencode "$REDIRECT_URI")&scope=claudeai&state=$STATE&code_challenge=$CHALLENGE&code_challenge_method=S256&resource=$(urlencode "$RESOURCE")"
show "GET $AUTHORIZE_URL"
show "(redirect_uri uses port 61111; the client registered 53682, as a CLI that picks a new port per sign-in does)"
http -c "$COOKIES" -b "$COOKIES" "$AUTHORIZE_URL"
show "$HTTP_STATUS Location: $HTTP_LOCATION"
expect_eq "$HTTP_STATUS" 302 "redirected"
expect_contains "$HTTP_LOCATION" "$FRONTEND_URL/connect?request=" "to \${app.frontend-url}/connect?request=<handle>"
HANDLE=${HTTP_LOCATION##*request=}

section "3. Approve as the frontend, with a local app JWT from /api/v1/auth/login"
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as $USER_EMAIL"
APP_JWT=$(jq -r .token <<<"$HTTP_BODY")
APP_JWT=${APP_JWT#Bearer }
show "app JWT: $(redact <<<"$APP_JWT")"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/connect/requests/$HANDLE"
show "GET /api/v1/connect/requests/<handle> -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$(jq -r .clientName <<<"$HTTP_BODY")" "hrva e2e (curl)" "consent screen gets the client name"
http -X POST -H "Authorization: Bearer $APP_JWT" -H "Origin: $FRONTEND_URL" "$BASE/api/v1/connect/requests/$HANDLE/approve"
show "POST /api/v1/connect/requests/<handle>/approve -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 200 "approved"
CONTINUE_URL=$(jq -r .continueUrl <<<"$HTTP_BODY")
expect_contains "$CONTINUE_URL" "$BASE/oauth2/connect/continue?otc=" "continueUrl is absolute, from the issuer"

section "4. Continue in the same browser -> resumed authorization -> code"
http -c "$COOKIES" -b "$COOKIES" "$CONTINUE_URL"
show "GET continueUrl -> $HTTP_STATUS Location: $HTTP_LOCATION"
expect_eq "$HTTP_STATUS" 302 "one-time code redeemed"
expect_contains "$HTTP_LOCATION" "$BASE/oauth2/authorize?" "back to /oauth2/authorize with the original query"
http -c "$COOKIES" -b "$COOKIES" "$HTTP_LOCATION"
show "GET /oauth2/authorize -> $HTTP_STATUS Location: $HTTP_LOCATION"
expect_contains "$HTTP_LOCATION" "$REDIRECT_URI?code=" "code delivered to the client's redirect_uri"
expect_contains "$HTTP_LOCATION" "state=$STATE" "state echoed"
CODE=$(sed -E 's/.*[?&]code=([^&]*).*/\1/' <<<"$HTTP_LOCATION")

section "5. Code -> tokens (PKCE verifier + resource)"
http -X POST "$BASE/oauth2/token" \
    --data-urlencode grant_type=authorization_code \
    --data-urlencode "code=$CODE" \
    --data-urlencode "redirect_uri=$REDIRECT_URI" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "code_verifier=$VERIFIER" \
    --data-urlencode "resource=$RESOURCE"
expect_eq "$HTTP_STATUS" 200 "token issued"
ACCESS_TOKEN=$(jq -r .access_token <<<"$HTTP_BODY")
REFRESH_TOKEN=$(jq -r .refresh_token <<<"$HTTP_BODY")
show "$(jq -c '{token_type, expires_in, scope, access_token: "eyJ…", refresh_token: "<opaque, \(.refresh_token | length) chars>"}' <<<"$HTTP_BODY")"
CLAIMS=$(jwt_claims "$ACCESS_TOKEN")
show "access token header: $(jwt_header "$ACCESS_TOKEN" | jq -c '{alg, kid}')"
show "access token claims: $(jq -c '{iss, sub, aud, scope, client_id, lifetime_s: (.exp - .iat)}' <<<"$CLAIMS")"
expect_eq "$(jq -r .sub <<<"$CLAIMS")" "$USER_EMAIL" "token subject is the approving user"
expect_eq "$(jq -r '.aud | if type == "array" then .[0] else . end' <<<"$CLAIMS")" "$RESOURCE" "audience is \${issuer}/mcp"
[[ -n $REFRESH_TOKEN && $REFRESH_TOKEN != null ]] || fail "public client got no refresh token"
ok "public client got a refresh token"
mcp_post /mcp "$ACCESS_TOKEN" "$TOOLS_LIST"
expect_eq "$HTTP_STATUS" 200 "the access token works on /mcp"

section "6. tools/list (MCP Inspector CLI: its own discovery, registration, PKCE sign-in through the fake browser)"
export MCP_E2E_BASE=$BASE MCP_E2E_FRONTEND_URL=$FRONTEND_URL MCP_E2E_APP_JWT=$APP_JWT MCP_E2E_BROWSER_LOG=$WORK/browser.log
: >"$MCP_E2E_BROWSER_LOG"
PATH="$SCRIPT_DIR/mcp-e2e/fake-browser:$PATH" MCP_AUTO_OPEN_ENABLED=true \
    inspector 120 "$RESOURCE" --transport http --callback-url "http://127.0.0.1:$CALLBACK_PORT/oauth/callback" --method tools/list
echo "  --- fake browser log"
sed 's/^/  /' "$MCP_E2E_BROWSER_LOG"
echo "  --- tools/list result"
show "$(jq -r '.tools[] | "\(.name): \(.title // "")"' <<<"$INSPECTOR_RESULT")"
expect_eq "$(jq -r '[.tools[].name] | sort | join(",")' <<<"$INSPECTOR_RESULT")" \
    "block_domain,block_link,block_user,check_url,delete_link,link_stats,list_links,moderation_summary,possible_scammers,review_queue,shorten_url" \
    "the three link tools and the eight moderation tools (admin only when called)"
echo "  --- what the Inspector registered (oauth2_registered_client)"
psql_query "select client_name, client_authentication_methods, authorization_grant_types, redirect_uris, scopes from oauth2_registered_client order by client_id_issued_at;"

section "7. list_links (Inspector CLI, stored sign-in)"
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name list_links
show_tool_result
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "list_links succeeded"

section "8. shorten_url (Inspector CLI): the new link carries the ai tag"
ALIAS=e2e-$(openssl rand -hex 3)
DESTINATION="https://example.com/hrva-mcp-e2e/$(date +%s)"
show "arguments: {\"url\":\"$DESTINATION\",\"alias\":\"$ALIAS\",\"tags\":[\"docs\"]}"
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name shorten_url \
    --tool-args-json "{\"url\":\"$DESTINATION\",\"alias\":\"$ALIAS\",\"tags\":[\"docs\"]}"
show_tool_result
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "shorten_url succeeded"
expect_contains "$(tool_text)" "Short link: $BASE/$ALIAS" "returns the full short link"
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name list_links --tool-arg tag=ai
show "list_links tag=ai:"
show_tool_result
expect_contains "$(tool_text)" "$BASE/$ALIAS -> $DESTINATION" "list_links tag=ai finds it"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/urls?tag=ai"
LINK_TAGS=$(jq -c --arg alias "$ALIAS" '[.content[] | select(.shortUrl == $alias) | .tags | sort] | first' <<<"$HTTP_BODY")
show "GET /api/v1/urls?tag=ai -> $ALIAS tags $LINK_TAGS"
expect_eq "$LINK_TAGS" '["ai","docs"]' "stored tags are ai + the requested one"

section "9. link_stats (Inspector CLI) after a browser visit and a bot visit"
show "visiting $BASE/$ALIAS as Chrome on Windows from t.co in HR -> $(curl -s -o /dev/null -w '%{http_code}' \
    -A "$BROWSER_USER_AGENT" \
    -H 'Referer: https://t.co/x' -H 'CF-IPCountry: HR' "$BASE/$ALIAS")"
show "visiting $BASE/$ALIAS with plain curl, a bot -> $(curl -s -o /dev/null -w '%{http_code}' "$BASE/$ALIAS")"
sleep 2
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name link_stats --tool-arg "link=$BASE/$ALIAS"
show_tool_result
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "link_stats succeeded"
STATS=$(tool_text)
expect_contains "$STATS" "Clicks: 1" "one human click"
expect_contains "$STATS" "Daily unique visitors: 1" "one daily unique visitor"
expect_contains "$STATS" "Bot clicks: 1" "the plain curl counts as a bot click"
expect_contains "$STATS" "Top referrers: t.co 1" "referrer t.co"
expect_contains "$STATS" "Top countries: HR 1" "country HR"
expect_contains "$STATS" "Devices: desktop 1" "device desktop"
expect_contains "$STATS" "Unique visitors are counted per day" "explains the daily unique visitors"
echo "  --- link_daily_stats and link_daily_breakdown for $ALIAS"
psql_query "select s.stat_date, s.clicks, s.unique_visitors, s.bot_clicks from link_daily_stats s join url u on u.id = s.url_id where u.short_url = '$ALIAS';"
psql_query "select b.stat_date, b.dimension, b.dimension_value, b.clicks from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = '$ALIAS' order by b.dimension;"
expect_eq "$(psql_value "select s.clicks || ',' || s.unique_visitors || ',' || s.bot_clicks from link_daily_stats s join url u on u.id = s.url_id where u.short_url = '$ALIAS';")" "1,1,1" "link_daily_stats: clicks 1, unique_visitors 1, bot_clicks 1"
expect_eq "$(psql_value "select count(*) from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = '$ALIAS';")" 6 "link_daily_breakdown: 6 rows"
expect_eq "$(psql_value "select b.dimension_value from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = '$ALIAS' and b.dimension = 'SOURCE';")" direct "the browser visit counts as direct"

section "10. Refresh (curl): rotation, replay refused"
http -X POST "$BASE/oauth2/token" \
    --data-urlencode grant_type=refresh_token \
    --data-urlencode "refresh_token=$REFRESH_TOKEN" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "resource=$RESOURCE"
expect_eq "$HTTP_STATUS" 200 "refreshed with client_id only (public client)"
NEW_ACCESS_TOKEN=$(jq -r .access_token <<<"$HTTP_BODY")
NEW_REFRESH_TOKEN=$(jq -r .refresh_token <<<"$HTTP_BODY")
show "$(jq -c '{token_type, expires_in, access_token: "eyJ…", refresh_token: "<opaque, \(.refresh_token | length) chars>"}' <<<"$HTTP_BODY")"
show "new access token claims: $(jwt_claims "$NEW_ACCESS_TOKEN" | jq -c '{sub, aud}')"
[[ $NEW_REFRESH_TOKEN != "$REFRESH_TOKEN" ]] || fail "refresh token was not rotated"
ok "refresh token rotated"
http -X POST "$BASE/oauth2/token" \
    --data-urlencode grant_type=refresh_token \
    --data-urlencode "refresh_token=$REFRESH_TOKEN" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "resource=$RESOURCE"
show "replaying the old refresh token -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 400 "old refresh token refused"
mcp_post /mcp "$NEW_ACCESS_TOKEN" "$TOOLS_LIST"
expect_eq "$HTTP_STATUS" 200 "new access token works on /mcp"

section "11. Connected apps (GET /api/v1/users/me/connected-apps)"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/users/me/connected-apps"
show "$(jq -c '.[]' <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS" 200 "listed"
expect_eq "$(jq -r '[.[].clientName] | sort | join(", ")' <<<"$HTTP_BODY")" "MCP Inspector, hrva e2e (curl)" "both clients, one entry each"

section "12. Disconnect the curl client -> its tokens stop working at once"
http -X DELETE -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/users/me/connected-apps/$CLIENT_ID"
expect_eq "$HTTP_STATUS" 204 "DELETE /api/v1/users/me/connected-apps/{clientId}"
mcp_post /mcp "$NEW_ACCESS_TOKEN" "$TOOLS_LIST"
show "POST /mcp with the unexpired access token -> $HTTP_STATUS WWW-Authenticate: $(header_value www-authenticate)"
expect_eq "$HTTP_STATUS" 401 "access token rejected before it expires"
http -X POST "$BASE/oauth2/token" \
    --data-urlencode grant_type=refresh_token \
    --data-urlencode "refresh_token=$NEW_REFRESH_TOKEN" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "resource=$RESOURCE"
show "refresh -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 400 "refresh token rejected"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/users/me/connected-apps"
expect_eq "$(jq -r '[.[].clientName] | join(", ")' <<<"$HTTP_BODY")" "MCP Inspector" "only the Inspector is still connected"
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name list_links
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "the Inspector's own sign-in still works"

section "13. The no-account MCP server is gone"
mcp_post /mcp/anon "" "$INITIALIZE"
expect_eq "$HTTP_STATUS" 404 "POST /mcp/anon is 404"
expect_eq "$(header_value www-authenticate)" "" "no sign-in challenge on /mcp/anon"
http "$BASE/mcp/anon"
expect_eq "$HTTP_STATUS" 404 "GET /mcp/anon is 404"
echo "  --- links in the database"
psql_query "select u.short_url, u.owner_id is null as anonymous, string_agg(t.tag, ',' order by t.tag) as tags from url u left join url_tags t on t.url_id = u.id group by u.id order by u.id;"
expect_eq "$(psql_value "select created_via from url where short_url = '$ALIAS';")" MCP "the signed-in shorten_url is created via MCP"

section "14. Admin stats and reserved aliases"
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"admin@localhost","password":"admin123"}'
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as admin@localhost"
ADMIN_JWT=$(jq -r .token <<<"$HTTP_BODY")
ADMIN_JWT=${ADMIN_JWT#Bearer }
show "admin JWT: $(redact <<<"$ADMIN_JWT")"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/traffic?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/traffic?days=7"
show "$(jq -c '{totals, anonymousClicks, accountClicks, topLinks: [.topLinks[] | {shortUrl, clicks}], referrers: .breakdowns.referrers}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r .totals.clicks <<<"$HTTP_BODY")" 1 "traffic: one human click"
expect_eq "$(jq -r .totals.botClicks <<<"$HTTP_BODY")" 1 "traffic: one bot click"
expect_eq "$(jq -r '.topLinks[0].shortUrl' <<<"$HTTP_BODY")" "$ALIAS" "traffic: $ALIAS is the top link"
expect_eq "$(jq -r .anonymousClicks <<<"$HTTP_BODY")" 0 "traffic: no clicks on anonymous links"
expect_eq "$(jq -r '.accountClicks >= 1' <<<"$HTTP_BODY")" true "traffic: account links have clicks"
expect_eq "$(jq -r '.breakdowns.referrers | length > 0' <<<"$HTTP_BODY")" true "traffic: the referrer breakdown is filled"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/traffic?days=14"
expect_eq "$HTTP_STATUS" 400 "days=14 is refused"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "days must be 7, 30, 90 or 365" "with the range message"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/links?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/links?days=7"
show "$(jq -c '{createdInRange, keys: .created.keys}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '[.created.daily[].counts.MCP] | add' <<<"$HTTP_BODY")" 1 "links: one link created via MCP"
expect_eq "$(jq -r '[.created.daily[].counts.ANONYMOUS_MCP] | add' <<<"$HTTP_BODY")" 0 "links: none created via ANONYMOUS_MCP"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/users?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/users?days=7"
show "$(jq -c '{now: (.now | {total, admins}), apiKeys, mcp}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.mcp.connectedUsers >= 1' <<<"$HTTP_BODY")" true "users: the Inspector's user counts as connected"
expect_eq "$(jq -r '.mcp.registeredClients >= 1' <<<"$HTTP_BODY")" true "users: registered MCP clients are counted"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/safety?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/safety?days=7"
show "$(jq -c '{openReviews, resolved, resolveTimes, domainRules, possibleScammers, sources: (.sources | length)}' <<<"$HTTP_BODY")"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/system?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/system?days=7"
show "$(jq -c '{appVersion, uptime, javaVersion, activeProfiles, health, database, redis}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r .health.database <<<"$HTTP_BODY")" UP "system: database UP"
expect_eq "$(jq -r .health.redis <<<"$HTTP_BODY")" UP "system: Redis UP"
expect_eq "$(jq -r .database.product <<<"$HTTP_BODY")" PostgreSQL "system: the database is PostgreSQL"
expect_eq "$(jq -r .database.shortUrlUnique <<<"$HTTP_BODY")" true "system: short_url uniqueness is enforced"
expect_eq "$(jq -r '.database.sizeBytes > 0' <<<"$HTTP_BODY")" true "system: the database size is known"
expect_eq "$(jq -r '.redis.usedMemoryBytes > 0 and .redis.keys.scanned > 0' <<<"$HTTP_BODY")" true "system: Redis memory and keys are read"
expect_eq "$(jq -r .redis.keys.capped <<<"$HTTP_BODY")" false "system: the key scan is not capped"
expect_eq "$(jq -r '.emails.daily | length' <<<"$HTTP_BODY")" 7 "system: 7 days of emails"
expect_eq "$(jq -r '[.. | strings | select(test("jdbc:|redis://|127\\.0\\.0\\.1|localhost"))] | length' <<<"$HTTP_BODY")" 0 \
    "system: no connection URL or host in the answer"
echo "  Postgres version: $(jq -r .database.version <<<"$HTTP_BODY")"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats (legacy)"
expect_eq "$(jq -r .databaseActive <<<"$HTTP_BODY")" true "legacy: databaseActive"
expect_eq "$(jq -r .cacheActive <<<"$HTTP_BODY")" true "legacy: cacheActive"
expect_eq "$(jq -r .appVersion <<<"$HTTP_BODY")" "$(jq -r '.releases[0].version | ltrimstr("v")' "$ROOT/src/main/resources/changelog.json")" \
    "legacy: appVersion is the newest changelog release"

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/reserved-aliases?search=mcp"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/reserved-aliases?search=mcp"
expect_eq "$(jq -r 'any(.content[]; .alias == "mcp" and .builtIn == true)' <<<"$HTTP_BODY")" true "mcp is a built-in reserved alias"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/reserved-aliases?search=anon"
expect_eq "$(jq -r 'any(.content[]; .alias == "anon" and .builtIn == true)' <<<"$HTTP_BODY")" true "anon is a built-in reserved alias"
http -X POST "$BASE/api/v1/admin/reserved-aliases" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d '{"alias":"E2E-Reserved","reason":"e2e check"}'
show "POST /api/v1/admin/reserved-aliases -> $HTTP_STATUS $(jq -c '{id, alias, reason, builtIn}' <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS" 200 "reserved E2E-Reserved"
expect_eq "$(jq -r .alias <<<"$HTTP_BODY")" e2e-reserved "stored in lower case"
RESERVED_ID=$(jq -r .id <<<"$HTTP_BODY")
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d '{"longUrl":"https://example.com/hrva-mcp-e2e-reserved","shortUrl":"e2e-RESERVED"}'
show "POST /api/v1/urls shortUrl=e2e-RESERVED as $USER_EMAIL -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 409 "the user cannot take the reserved alias in another case"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "This short URL is reserved. Try another." "with the usual refusal"
http -X DELETE "$BASE/api/v1/admin/reserved-aliases/$RESERVED_ID" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS" 204 "DELETE /api/v1/admin/reserved-aliases/{id}"
IDN_DESTINATION="https://čokolada.hr/torta"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d "{\"longUrl\":\"$IDN_DESTINATION\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/urls to $IDN_DESTINATION (after the traffic totals, so its visit doesn't change them)"
IDN_ALIAS=$(jq -r .shortUrl <<<"$HTTP_BODY")
http "$BASE/$IDN_ALIAS"
show "GET $BASE/$IDN_ALIAS -> $HTTP_STATUS Location: $HTTP_LOCATION"
expect_eq "$HTTP_STATUS" 302 "the accented destination redirects"
expect_eq "$HTTP_LOCATION" "https://xn--okolada-i6a.hr/torta" "with its host in punycode, so the Location header is ASCII"

section "15. Passkey options (curl)"
http -X POST "$BASE/api/v1/auth/passkeys/options"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/passkeys/options without a token"
expect_eq "$(jq -r .options.rpId <<<"$HTTP_BODY")" localhost "rpId is app.passkeys.rp-id of the local profile"
expect_eq "$(jq -r .options.userVerification <<<"$HTTP_BODY")" required "passwordless sign-in requires user verification"
expect_eq "$(jq -r '.options.allowCredentials | length' <<<"$HTTP_BODY")" 0 "allowCredentials is empty, for discoverable passkeys"
expect_eq "$(jq -r '.ceremonyId | length' <<<"$HTTP_BODY")" 43 "ceremonyId is 32 random bytes in base64url"
http -X POST "$BASE/api/v1/users/me/passkeys/registration/options" -H "Authorization: Bearer $APP_JWT" \
    -H 'Content-Type: application/json' -d "{\"password\":\"$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "registration options with the current password"
expect_eq "$(jq -r .options.rp.id <<<"$HTTP_BODY")" localhost "rp.id is app.passkeys.rp-id"
expect_eq "$(jq -r .options.rp.name <<<"$HTTP_BODY")" hrva.cc "rp.name is hrva.cc"
expect_eq "$(jq -r .options.user.name <<<"$HTTP_BODY")" "$USER_EMAIL" "user.name is the account email"
expect_eq "$(jq -r .options.authenticatorSelection.residentKey <<<"$HTTP_BODY")" preferred "residentKey is preferred"
expect_eq "$(jq -c '[.options.pubKeyCredParams[].alg]' <<<"$HTTP_BODY")" "[-7,-8,-257]" "ES256, EdDSA, RS256 in that order"
expect_eq "$(jq -r .options.attestation <<<"$HTTP_BODY")" none "attestation is none"
http -X POST "$BASE/api/v1/users/me/passkeys/registration/options" -H "Authorization: Bearer $APP_JWT" \
    -H 'Content-Type: application/json' -d '{"password":"wrong"}'
expect_eq "$HTTP_STATUS" 403 "a wrong password gets no registration options"
http -X POST "$BASE/api/v1/users/me/passkeys/registration/options" \
    -H 'Content-Type: application/json' -d "{\"password\":\"$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 401 "registration options need a signed-in user"
expect_eq "$(psql_value "select count(*) from information_schema.columns where table_name = 'user_account' and column_name = 'two_step_enabled'")" \
    1 "028 added user_account.two_step_enabled"
expect_eq "$(psql_value "select count(*) from information_schema.columns where table_name = 'passkey_credential' and column_name = 'attestation_object'")" \
    1 "028 added passkey_credential.attestation_object"

section "16. QR scans, public numbers and the burst alert"
source_clicks() {
    psql_value "select coalesce(sum(b.clicks), 0) from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = '$ALIAS' and b.dimension = 'SOURCE' and b.dimension_value = '$1';"
}
qr_scan_counted() {
    [[ $(source_clicks qr) == 1 ]]
}
QR_REDIRECT=$(curl -s -o /dev/null -w '%{redirect_url}' -A "$BROWSER_USER_AGENT" "$BASE/$ALIAS?qr")
show "visiting $BASE/$ALIAS?qr as Chrome -> Location: $QR_REDIRECT"
expect_eq "$QR_REDIRECT" "$DESTINATION" "the scan lands on the destination exactly, with no qr added"
wait_until 15 "the QR scan row" qr_scan_counted
expect_eq "$(source_clicks qr),$(source_clicks direct)" "1,1" "link_daily_breakdown SOURCE: qr 1 (this scan), direct 1 (section 9's plain visit)"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/traffic?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/stats/traffic?days=7"
show "$(jq -c '{clicks: .totals.clicks, sources: .breakdowns.sources}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '[.breakdowns.sources[] | select(.value == "qr") | .clicks] | add' <<<"$HTTP_BODY")" 1 "traffic: one QR scan"
expect_eq "$(jq -r '[.breakdowns.sources[] | select(.value == "direct") | .clicks] | add' <<<"$HTTP_BODY")" 1 "traffic: one direct click"
expect_eq "$(jq -r .totals.clicks <<<"$HTTP_BODY")" 2 "traffic: QR scans and direct clicks add up to the clicks"

expect_eq "$(curl -s -o "$WORK/qr.png" -w '%{http_code} %{content_type}' "$BASE/api/v1/urls/$ALIAS/qr")" "200 image/png" \
    "GET /api/v1/urls/$ALIAS/qr is a PNG"
QR_CLASSPATH=
while IFS= read -r library; do
    (cd "$WORK" && jar xf "$JAR" "$library")
    QR_CLASSPATH+=${QR_CLASSPATH:+:}$WORK/$library
done < <(jar tf "$JAR" | grep -E '^BOOT-INF/lib/(core|javase)-[0-9.]+\.jar$')
cat >"$WORK/QrText.java" <<'JAVA'
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import javax.imageio.ImageIO;

public class QrText {
    public static void main(final String[] arguments) throws Exception {
        final var image = ImageIO.read(new File(arguments[0]));
        System.out.print(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText());
    }
}
JAVA
QR_TEXT=$(java -Djava.awt.headless=true -cp "$QR_CLASSPATH" "$WORK/QrText.java" "$WORK/qr.png")
show "the code decodes (ZXing from the app jar) to: $QR_TEXT"
expect_eq "$QR_TEXT" "$BASE/$ALIAS?qr" "the code is the short link (app.server-url, set to the run's base URL) with the ?qr marker"

public_stats() {
    http -H "CF-Connecting-IP: $1" "$BASE/api/v1/public/stats"
}
STATS_NETWORK=203.0.113.7
public_stats "$STATS_NETWORK"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/public/stats without a token"
show "$(jq -c '{from, to, activeLinks, linksCreated, clicks, days: (.daily | length), blocked, visitorReportsHandled, averageReviewMinutes, blocklists}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r 'keys | join(",")' <<<"$HTTP_BODY")" \
    "activeLinks,averageReviewMinutes,blocked,blocklists,clicks,daily,from,linksCreated,to,updatedAt,visitorReportsHandled" "only the aggregate fields"
expect_eq "$(jq -r '.daily | length' <<<"$HTTP_BODY")" 30 "30 days"
expect_eq "$(jq -r '.daily[0].date == .from and .daily[-1].date == .to' <<<"$HTTP_BODY")" true "the days run from .from to .to"
expect_eq "$(jq -r '[.daily[] | keys | join(",")] | unique | join(";")' <<<"$HTTP_BODY")" "clicks,date" "each day is a date and a click count"
expect_eq "$(jq -r '.blocked | keys | join(",")' <<<"$HTTP_BODY")" "allTime,last30Days" "blocked links are two counts"
expect_eq "$(jq -r '.activeLinks >= 1' <<<"$HTTP_BODY")" true "the active links are counted"
expect_eq "$(jq -r .clicks <<<"$HTTP_BODY")" 0 "today's clicks wait for the day to close (the window ends yesterday)"
expect_eq "$(jq -r --arg alias "$ALIAS" '[.. | strings | select(test("@|example\\.com|localhost|" + $alias))] | length' <<<"$HTTP_BODY")" 0 \
    "no alias, destination host or email in the answer"
show "Cache-Control: $(header_value cache-control)"
expect_contains "$(header_value cache-control)" "max-age=600" "browsers may keep it for 10 minutes"
expect_contains "$(header_value cache-control)" "public" "shared caches may keep it too"
TTL=$(docker exec "$REDIS_CONTAINER" redis-cli --raw ttl public:stats)
expect_eq "$(( TTL > 0 && TTL <= 3600 ))" 1 "public:stats is cached in Redis for at most an hour (ttl ${TTL}s)"
ALLOWED=1
for (( call = 2; call <= 60; call++ )); do
    if [[ $(curl -s -o /dev/null -w '%{http_code}' -H "CF-Connecting-IP: $STATS_NETWORK" "$BASE/api/v1/public/stats") == 200 ]]; then
        ALLOWED=$(( ALLOWED + 1 ))
    fi
done
expect_eq "$ALLOWED" 60 "60 calls in the hour from one network are answered"
public_stats "$STATS_NETWORK"
show "call 61 -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 429 "the 61st call in the hour is refused"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "You've opened these numbers a lot in a short time. Try again in an hour." "with the plan's text"
public_stats 198.51.100.7
expect_eq "$HTTP_STATUS" 200 "another network still gets the numbers"
RATE_KEYS=$(docker exec "$REDIS_CONTAINER" redis-cli --raw --scan --pattern 'rate-limit:public-stats:*')
show "rate limit keys: $(tr '\n' ' ' <<<"$RATE_KEYS")"
expect_eq "$(grep -c . <<<"$RATE_KEYS")" 2 "one counter per network"
expect_eq "$(grep -Ec '203\.0\.113|198\.51\.100' <<<"$RATE_KEYS" || true)" 0 "the counter keys hold no IP address"

BURST_DOMAIN=hrva-burst-e2e.hr
BURST_LINE="Possible phishing wave: 5 new links to $BURST_DOMAIN in the last hour, from 2 different accounts or networks. The domain's age is unknown. Nothing was blocked. Review them: <$FRONTEND_URL/admin/urls?search=$BURST_DOMAIN>"
burst_logged() {
    grep -q "Burst of new links domain=$BURST_DOMAIN" "$WORK/app.log"
}
burst_posts() {
    jq -s --arg line "$BURST_LINE" '[.[] | select(.content | contains($line))] | length' "$DISCORD_POSTS"
}
burst_posted() {
    [[ $(burst_posts) -ge 1 ]]
}
create_burst_link() {
    http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
        -d "{\"longUrl\":\"https://$BURST_DOMAIN/$3\"}"
    expect_eq "$HTTP_STATUS" 200 "$2 creates https://$BURST_DOMAIN/$3"
}
for path in a b c; do
    create_burst_link "$APP_JWT" "$USER_EMAIL" "$path"
done
for path in d e; do
    create_burst_link "$ADMIN_JWT" admin@localhost "$path"
done
wait_until 30 "the burst check" burst_logged
show "$(grep -o "Burst of new links domain=$BURST_DOMAIN.*" "$WORK/app.log")"
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw zcard "burst:links:$BURST_DOMAIN")" 5 "burst:links:$BURST_DOMAIN holds the five links"
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw exists "burst:alerted:$BURST_DOMAIN")" 1 \
    "burst:alerted:$BURST_DOMAIN is set (.hr RDAP has no dates, so the age is unknown and counts as new)"
expect_eq "$(grep -c "Burst of new links domain=$BURST_DOMAIN" "$WORK/app.log")" 1 "one burst line in the app log"
wait_until 45 "the burst alert at the Discord stub (30-second buffer)" burst_posted
show "$(jq -r --arg line "$BURST_LINE" 'select(.content | contains($line)) | .content' "$DISCORD_POSTS")"
expect_eq "$(burst_posts)" 1 "the Discord stub got the burst alert once"
expect_eq "$(jq -sc --arg line "$BURST_LINE" '[.[] | select(.content | contains($line)) | {allowed_mentions, flags}] | first' "$DISCORD_POSTS")" \
    '{"allowed_mentions":{"parse":[]},"flags":4}' "no mentions, no embeds"
expect_eq "$(grep -c "$DISCORD_SECRET" "$WORK/app.log" || true)" 0 "the webhook URL never reaches the app log"

section "17. Bio pages"
CHROME='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
BIO_PAGES=$BASE/api/v1/bio-pages
expect_eq "$(psql_value "select count(*) from information_schema.tables where table_name in ('bio_page','bio_page_button','bio_handle_hold','bio_page_daily_views','bio_page_review');")" \
    5 "029 created the five bio tables"

echo "  --- two accounts create a page with the same handle at the same moment"
http_bg race-user -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' -d '{"handle":"e2e-race"}'
http_bg race-admin -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"handle":"e2e-race"}'
wait_bg
show "user -> $(bg_status race-user) $(bg_body race-user | jq -c '{handle, error}'), admin -> $(bg_status race-admin) $(bg_body race-admin | jq -c '{handle, error}')"
expect_eq "$(printf '%s\n' "$(bg_status race-user)" "$(bg_status race-admin)" | sort | tr '\n' ' ')" "201 409 " "one create wins the handle, the other is a 409"
if [[ $(bg_status race-user) == 201 ]]; then RACE_WINNER_JWT=$APP_JWT RACE_LOSER=race-admin; else RACE_WINNER_JWT=$ADMIN_JWT RACE_LOSER=race-user; fi
expect_eq "$(bg_body "$RACE_LOSER" | jq -r .error)" "This handle is taken. Try another." "the losing account is told the handle is taken"
expect_eq "$(psql_value "select count(*) from bio_page where handle = 'e2e-race';")" 1 "one bio_page row holds e2e-race"
http -X DELETE "$BIO_PAGES/me" -H "Authorization: Bearer $RACE_WINNER_JWT"
expect_eq "$HTTP_STATUS" 204 "the winner deletes the page"
expect_eq "$(psql_value "select (page_id is null) || '|' || (held_until > now() + interval '29 days') from bio_handle_hold where handle = 'e2e-race';")" \
    "true|true" "the deleted page's handle is held 30 days, linked to no page"

echo "  --- the same account sends its create twice at the same moment"
http_bg create-1 -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' -d '{"handle":"E2E-Bio"}'
http_bg create-2 -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' -d '{"handle":"E2E-Bio"}'
wait_bg
show "first -> $(bg_status create-1) $(bg_body create-1 | jq -c '{handle, error}'), second -> $(bg_status create-2) $(bg_body create-2 | jq -c '{handle, error}')"
expect_eq "$(printf '%s\n' "$(bg_status create-1)" "$(bg_status create-2)" | sort | tr '\n' ' ')" "201 409 " "one create makes the page, the other is a 409, never a 500"
if [[ $(bg_status create-1) == 201 ]]; then CREATED=create-1 REFUSED=create-2; else CREATED=create-2 REFUSED=create-1; fi
expect_eq "$(bg_body "$CREATED" | jq -r .handle)" e2e-bio "POST /api/v1/bio-pages/me stores the handle E2E-Bio as e2e-bio"
REFUSAL=$(bg_body "$REFUSED" | jq -r .error)
[[ $REFUSAL == "You already have a bio page." || $REFUSAL == "This handle is taken. Try another." ]] || fail "the second create: unexpected answer '$REFUSAL'"
ok "the second create is refused with \"$REFUSAL\""
echo "  lost the insert race on Postgres (logged by the create): $(grep -q 'Bio page was created by a concurrent request' "$WORK/app.log" && echo yes || echo 'no, refused before the insert')"
PAGE_ID=$(bg_body "$CREATED" | jq -r .id)
expect_eq "$(psql_value "select count(*) from bio_page p join user_account u on u.id = p.user_id where u.email = '$USER_EMAIL';")" 1 "$USER_EMAIL has exactly one page"
http -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"handle":"e2e-bio"}'
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "409 This handle is taken. Try another." "another account can't take e2e-bio"
http -X POST "$BIO_PAGES/me" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"handle":"e2e_admin"}'
expect_eq "$HTTP_STATUS" 201 "admin@localhost creates e2e_admin"
http -X PUT "$BIO_PAGES/me" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d '{"displayName":"Lab notes","bio":"","avatarKind":"INITIALS","accent":"SLATE","theme":"AUTO","buttons":[]}'
expect_eq "$HTTP_STATUS" 200 "admin@localhost names its page Lab notes"

echo "  --- a save, with every statement Postgres runs logged"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d '{"longUrl":"https://example.com/hrva-mcp-e2e-bio","shortUrl":"e2e-bio-link"}'
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/urls e2e-bio-link"
BIO_LINK_ID=$(jq -r .id <<<"$HTTP_BODY")
psql_value "alter system set log_statement = 'all';" >/dev/null
psql_value "select pg_reload_conf();" >/dev/null
sleep 1
SAVE=$(jq -nc --argjson urlId "$BIO_LINK_ID" '{displayName: "E2E a\\b 100%", bio: "Photos from Zagreb", avatarKind: "INITIALS", accent: "TEAL",
    theme: "AUTO", buttons: [{urlId: $urlId, title: "My shop", icon: "WEBSITE"}]}')
http -X PUT "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' -d "$SAVE"
expect_eq "$HTTP_STATUS" 200 "PUT /api/v1/bio-pages/me with one button"
expect_eq "$(jq -r '.buttons[0].linkState + " " + (.buttons[0].shownOnPage | tostring)' <<<"$HTTP_BODY")" "LIVE true" "the button's link is LIVE and shows on the page"
FIRST_SAVED_AT=$(jq -r .updatedAt <<<"$HTTP_BODY")
psql_value "alter system reset log_statement;" >/dev/null
psql_value "select pg_reload_conf();" >/dev/null
docker logs "$POSTGRES_CONTAINER" >"$WORK/postgres.log" 2>&1
LOCK_LINE=$(grep -E 'from bio_page [a-z0-9_]+ where [a-z0-9_]+\.id=\$1 for (no key )?update' "$WORK/postgres.log" | tail -n1)
[[ -n $LOCK_LINE ]] || fail "no locking select on bio_page in the Postgres log"
LOCK_CLAUSE=$(grep -Eo 'for (no key )?update' <<<"$LOCK_LINE" | tail -n1)
show "the page lock as Postgres ran it: ${LOCK_LINE#*: }"
[[ $LOCK_CLAUSE == "for update" || $LOCK_CLAUSE == "for no key update" ]] || fail "unexpected lock clause '$LOCK_CLAUSE'"
ok "PESSIMISTIC_WRITE on bio_page runs as \"$LOCK_CLAUSE\""
LOCK_PID=$(awk '{print $4}' <<<"$LOCK_LINE")
LOCK_HELD_MS=$(awk -v pid="$LOCK_PID" -v lock="$LOCK_LINE" '
    function ms(date, time) { split(time, t, ":"); return (t[1] * 3600 + t[2] * 60 + t[3]) * 1000 }
    $0 == lock { start = ms($1, $2); next }
    start && $4 == pid && /COMMIT/ { printf "%.0f", ms($1, $2) - start; exit }' "$WORK/postgres.log")
echo "  the save held the page lock for ${LOCK_HELD_MS:-?} ms (its locking select to its COMMIT)"

echo "  --- saves from the copy the last save answered with, then one from an outdated copy"
http -X PUT "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d "$(jq -c --arg at "$FIRST_SAVED_AT" '. + {bio: "Photos from Split", expectedUpdatedAt: $at}' <<<"$SAVE")"
expect_eq "$HTTP_STATUS" 200 "a save sending the first save's updatedAt ($FIRST_SAVED_AT) goes through"
SECOND_SAVED_AT=$(jq -r .updatedAt <<<"$HTTP_BODY")
http -X PUT "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d "$(jq -c --arg at "$SECOND_SAVED_AT" '. + {expectedUpdatedAt: $at}' <<<"$SAVE")"
expect_eq "$HTTP_STATUS" 200 "so does the next one, sending the second save's updatedAt ($SECOND_SAVED_AT)"
THIRD_SAVED_AT=$(jq -r .updatedAt <<<"$HTTP_BODY")
http -H "Authorization: Bearer $APP_JWT" "$BIO_PAGES/me"
expect_eq "$(jq -r .updatedAt <<<"$HTTP_BODY")" "$THIRD_SAVED_AT" "GET /api/v1/bio-pages/me reads back the updatedAt the save answered with"
STORED_BEFORE=$(psql_value "select bio || ' | ' || updated_at from bio_page where id = $PAGE_ID;")
http -X PUT "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT" -H 'Content-Type: application/json' \
    -d "$(jq -c --arg at "$FIRST_SAVED_AT" '. + {bio: "From an outdated tab", expectedUpdatedAt: $at}' <<<"$SAVE")"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" \
    "409 Your bio page changed in another tab or window. We loaded the latest version; check your changes and save again." \
    "a save still sending the first save's updatedAt is a 409"
[[ $STORED_BEFORE == "Photos from Zagreb | "* ]] || fail "the page before the outdated save: unexpected '$STORED_BEFORE'"
expect_eq "$(psql_value "select bio || ' | ' || updated_at from bio_page where id = $PAGE_ID;")" "$STORED_BEFORE" \
    "and writes nothing: bio_page still has the bio and updated_at of the save before it ($STORED_BEFORE)"

echo "  --- the public page"
http "$BASE/api/v1/public/bio-pages/E2E-Bio"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/public/bio-pages/E2E-Bio without a token"
show "$(jq -c . <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.buttons | length' <<<"$HTTP_BODY")" 1 "one button"
expect_eq "$(jq -r '.buttons[0].shortUrl' <<<"$HTTP_BODY")" e2e-bio-link "the button is the short link e2e-bio-link"
expect_eq "$(jq -r '[paths | .[-1] | strings] - ["id", "urlId", "ownerId", "owner", "destinationHost", "longUrl", "linkState", "visible", "hiddenReason"]
    == [paths | .[-1] | strings]' <<<"$HTTP_BODY")" true "no ids, owner, destination, state or moderation fields"
expect_contains "$(header_value cache-control)" no-store "Cache-Control: no-store"
expect_eq "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$BASE/@E2E-Bio?qr")" "302 $FRONTEND_URL/bio/e2e-bio" \
    "/@E2E-Bio?qr -> 302 to the frontend page, lower-cased, the query dropped"

echo "  --- the first view of the day while the page row is locked \"$LOCK_CLAUSE\""
hold_page_lock "$PAGE_ID" "$LOCK_CLAUSE" 3
BEACON=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST -A "$CHROME" -H 'CF-Connecting-IP: 198.51.100.7' \
    "$BASE/api/v1/public/bio-pages/e2e-bio/views")
release_page_lock
show "first view while locked -> $BEACON (seconds)"
expect_eq "${BEACON%% *}" 204 "the beacon answers 204"
expect_lock_wait "${BEACON#* }" "the first view of the day"
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -X POST -A "$CHROME" -H 'CF-Connecting-IP: 198.51.100.7' "$BASE/api/v1/public/bio-pages/e2e-bio/views")" \
    204 "the same visitor again -> 204"
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/public/bio-pages/e2e-bio/views")" 204 "plain curl, a bot -> 204"
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/public/bio-pages/nobody-here/views")" 204 "an unknown page -> 204 too"
expect_eq "$(psql_value "select views || '|' || bot_views from bio_page_daily_views where page_id = $PAGE_ID;")" "1|1" \
    "bio_page_daily_views: one human view (counted once) and one bot view"
VISITOR_KEYS=$(docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "analytics:bio-visitors:$PAGE_ID:*")
expect_eq "$(grep -cE "^analytics:bio-visitors:$PAGE_ID:[0-9]{4}-[0-9]{2}-[0-9]{2}$" <<<"$VISITOR_KEYS")" 1 "Redis: one analytics:bio-visitors:<pageId>:<day> set"
VISITOR=$(docker exec "$REDIS_CONTAINER" redis-cli smembers "$VISITOR_KEYS")
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli scard "$VISITOR_KEYS") ${#VISITOR}" "1 22" "it holds one 22-character hash"
[[ $VISITOR != *198.51.100.7* ]] || fail "the visitor set holds the IP address"
expect_eq "$(( $(docker exec "$REDIS_CONTAINER" redis-cli ttl "$VISITOR_KEYS") > 0 ))" 1 "the set expires"
VIEW_LIMIT_KEYS=$(docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern 'rate-limit:bio-view:*')
expect_eq "$(grep -cE '^rate-limit:bio-view:[0-9a-f]{64}$' <<<"$VIEW_LIMIT_KEYS") $(grep -c . <<<"$VIEW_LIMIT_KEYS")" "2 2" \
    "Redis: the view limit has one key per network (the visitor's and plain curl's), each a sha256, never an IP"

echo "  --- a click on the button from the bio page"
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -A "$CHROME" -H "Referer: $FRONTEND_URL/bio/e2e-bio" "$BASE/e2e-bio-link")" 302 \
    "a browser follows the button with the bio page as its referrer"
wait_until 15 "the click's referrer row for e2e-bio-link" psql_has_referrer_row e2e-bio-link
expect_eq "$(psql_value "select b.dimension_value from link_daily_breakdown b join url u on u.id = b.url_id where u.short_url = 'e2e-bio-link' and b.dimension = 'REFERRER';")" \
    other "a localhost referrer has no dot, so it is classified other, not as the own host"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/analytics/bio-page?days=7"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/analytics/bio-page?days=7"
show "$(jq -c '{handle, views, botViews, days: (.daily | length), buttons}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '"\(.views) \(.botViews) \(.daily | length)"' <<<"$HTTP_BODY")" "1 1 7" "views 1, bot views 1, 7 days"
expect_eq "$(jq -r '.buttons[0] | "\(.shortUrl) \(.allClicks) \(.fromHrvaPages)"' <<<"$HTTP_BODY")" "e2e-bio-link 1 0" \
    "the button: 1 click in all, 0 from hrva.cc pages on localhost"
expect_eq "$(jq -r '.buttons[0] | "\(.visible) \(.linkState) \(.shownOnPage)"' <<<"$HTTP_BODY")" "true LIVE true" \
    "the button says it is visible, its link LIVE, so it is on the page"

echo "  --- a visitor report while the page row is locked \"$LOCK_CLAUSE\""
hold_page_lock "$PAGE_ID" "$LOCK_CLAUSE" 3
REPORT=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST "$BASE/api/v1/reports" -H 'Content-Type: application/json' \
    -d '{"shortUrl":"@e2e-bio","category":"IMPERSONATION"}')
release_page_lock
show "POST /api/v1/reports @e2e-bio while locked -> $REPORT (seconds)"
expect_eq "${REPORT%% *}" 202 "the report answers 202"
expect_lock_wait "${REPORT#* }" "the report that opens the review"
expect_eq "$(psql_value "select count(*) || ' ' || bool_or(system_flagged) from bio_page_review where page_id = $PAGE_ID and resolved_at is null;")" "1 false" \
    "one open review, opened by a visitor"

echo "  --- admin"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/bio-page-reviews"
expect_eq "$HTTP_STATUS $(jq -r .page.totalElements <<<"$HTTP_BODY")" "200 1" "GET /api/v1/admin/bio-page-reviews lists it"
show "$(jq -c '.content[0] | {pageId, handle, reasons}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.content[0].reasons | join("|")' <<<"$HTTP_BODY")" "reported by a visitor: impersonation" "the review names the IMPERSONATION report"
for search in 'a\b:e2e-bio' '%:e2e-bio' '_:e2e_admin'; do
    http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/bio-pages?search=$(urlencode "${search%%:*}")"
    expect_eq "$HTTP_STATUS $(jq -r '"\(.page.totalElements) \(.content[0].handle)"' <<<"$HTTP_BODY")" "200 1 ${search#*:}" \
        "search ${search%%:*} matches it literally: only ${search#*:}"
done
http -X POST "$BASE/api/v1/admin/bio-pages/$PAGE_ID/revoke-handle" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d '{"reason":"E2E","expectedHandle":"someone-else"}'
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "409 This handle changed. Reload and try again." "a revoke from a stale list is a 409"
http -X POST "$BASE/api/v1/admin/bio-pages/$PAGE_ID/hide" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"reason":"E2E"}'
expect_eq "$HTTP_STATUS $(jq -r '"\(.hidden) \(.hiddenReason)"' <<<"$HTTP_BODY")" "200 true E2E" "POST /api/v1/admin/bio-pages/{id}/hide"
http "$BASE/api/v1/public/bio-pages/e2e-bio"
expect_eq "$HTTP_STATUS $HTTP_BODY" "404 {\"error\":\"This page isn't available.\"}" "the hidden page is not available"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/bio-page-reviews"
expect_eq "$(jq -r .page.totalElements <<<"$HTTP_BODY")" 0 "hiding resolved the review"
expect_eq "$(psql_value "select resolution || ' ' || resolved_by from bio_page_review where page_id = $PAGE_ID;")" "HIDDEN admin@localhost" "as HIDDEN, by the admin"
http -X DELETE "$BIO_PAGES/me" -H "Authorization: Bearer $APP_JWT"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" \
    "409 An admin hid this page, so it can't be deleted here. You can still delete your whole account in Settings." "the owner can't delete a hidden page"
http -X POST "$BASE/api/v1/admin/bio-pages/$PAGE_ID/revoke-handle" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d '{"reason":"E2E","expectedHandle":"E2E-Bio"}'
expect_eq "$HTTP_STATUS $(jq -r '"\(.handle) \(.handleRevokedReason)"' <<<"$HTTP_BODY")" "200 null E2E" "revoking e2e-bio clears the handle and keeps the page"
expect_eq "$(psql_value "select reason || ' | ' || created_by from reserved_alias where alias = 'e2e-bio';")" "Revoked bio page handle: E2E | admin@localhost" \
    "the revoked handle is reserved with the reason"
expect_eq "$(psql_value "select string_agg(action || ' ' || target_identifier || case when failed then ' failed' else '' end, ', ' order by id) from audit_log where action in ('HIDE_BIO_PAGE', 'REVOKE_BIO_HANDLE') or target_identifier = 'e2e-bio';")" \
    "REVOKE_BIO_HANDLE $PAGE_ID failed, HIDE_BIO_PAGE $PAGE_ID, RESERVE_ALIAS e2e-bio, REVOKE_BIO_HANDLE $PAGE_ID" \
    "audit: the refused revoke, the hide, then the revoke's two rows"
http -H "Authorization: Bearer $APP_JWT" "$BASE/api/v1/users/me/export"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/users/me/export"
show "$(jq -c .bioPage <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.bioPage | "\(.handle) \(.handleRevokedReason) \(.hidden) \(.hiddenReason) \(.buttons[0].shortUrl)"' <<<"$HTTP_BODY")" \
    "null E2E true E2E e2e-bio-link" "the export has the page, its revoked and hidden reasons and the button"
expect_eq "$(jq -r '.bioPage | [paths | .[-1] | strings] | map(select(. == "views" or . == "botViews" or . == "hiddenBy" or . == "id")) | length' <<<"$HTTP_BODY")" \
    0 "no view counts, admin or ids in the export"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/users?days=7"
expect_eq "$(jq -c .bioPages <<<"$HTTP_BODY")" '{"total":2,"hidden":1}' "users stats: 2 bio pages, 1 hidden"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/traffic?days=7"
expect_eq "$(jq -c .bioPageViews <<<"$HTTP_BODY")" '{"views":1,"botViews":1}' "traffic stats: 1 bio page view, 1 bot view"

section "18. API key cleanup"
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as $USER_EMAIL"
KEY_JWT=$(jq -r .token <<<"$HTTP_BODY")
KEY_JWT=${KEY_JWT#Bearer }
KEY_IDS=()
for _ in 1 2 3; do
    http -X POST "$BASE/api/v1/api-keys" -H "Authorization: Bearer $KEY_JWT"
    expect_eq "$HTTP_STATUS" 200 "POST /api/v1/api-keys"
    KEY_IDS+=("$(jq -r .id <<<"$HTTP_BODY")")
done
http -X DELETE "$BASE/api/v1/api-keys/${KEY_IDS[0]}" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "409 Revoke this key before deleting it." "an active key can't be deleted"
for id in "${KEY_IDS[@]}"; do
    http -X PATCH "$BASE/api/v1/api-keys/$id/revoke" -H "Authorization: Bearer $KEY_JWT"
    expect_eq "$HTTP_STATUS" 200 "PATCH /api/v1/api-keys/$id/revoke"
done
http -X DELETE "$BASE/api/v1/api-keys/${KEY_IDS[0]}" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS" 204 "the owner deletes a revoked key"
expect_eq "$(psql_value "select count(*) from api_key where id = ${KEY_IDS[0]};")" 0 "its row is gone"
http -X DELETE "$BASE/api/v1/api-keys/inactive" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS" 200 "DELETE /api/v1/api-keys/inactive as admin@localhost"
expect_eq "$(psql_value "select count(*) from api_key where id in (${KEY_IDS[1]}, ${KEY_IDS[2]});")" 2 "the admin's bulk delete leaves $USER_EMAIL's keys alone"
http -X DELETE "$BASE/api/v1/api-keys/${KEY_IDS[1]}" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS" 204 "an admin deletes someone else's revoked key"
http -X DELETE "$BASE/api/v1/api-keys/inactive" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '200 {"deleted":1}' "the owner's bulk delete removes the last inactive key and answers the count"
expect_eq "$(psql_value "select count(*) from api_key k join user_account u on u.id = k.owner_id where u.email = '$USER_EMAIL' and not k.active;")" 0 \
    "$USER_EMAIL has no inactive key left"

section "19. Admin user list and edit"
http -G "$BASE/api/v1/users" -H "Authorization: Bearer $ADMIN_JWT" --data-urlencode "search=$USER_EMAIL"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/users as admin@localhost"
expect_eq "$(jq -c '.content[0] | keys' <<<"$HTTP_BODY")" \
    '["active","apiKeySlots","authProvider","authorities","createDate","email","emailVerified","id","lastLogin","possibleScammer","signInMethods","twoFactorEnabled","twoStepEnabled"]' \
    "a user row has the fields the User entity had, plus signInMethods"
expect_contains "$(jq -c '[.content[0].signInMethods[].method]' <<<"$HTTP_BODY")" '"PASSWORD"' "the password sign-ins are recorded as a sign-in method"
expect_eq "$(jq -c '.content[0].authorities | map(keys) | unique' <<<"$HTTP_BODY")" '[["active","authority","id","name"]]' "each role has id, name, active and authority"
expect_contains "$(jq -c '[.content[0].authorities[].name]' <<<"$HTTP_BODY")" '"ROLE_USER"' "the roles are read inside the transaction"
LISTED_ID=$(jq -r .content[0].id <<<"$HTTP_BODY")
LISTED_SLOTS=$(jq -r .content[0].apiKeySlots <<<"$HTTP_BODY")
http -X PUT "$BASE/api/v1/users/$LISTED_ID" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d "{\"id\":$LISTED_ID,\"apiKeySlots\":$((LISTED_SLOTS + 1))}"
expect_eq "$HTTP_STATUS $(jq -r '.apiKeySlots, has("possibleScammer"), (.authorities | length > 0)' <<<"$HTTP_BODY" | paste -sd' ' -)" \
    "200 $((LISTED_SLOTS + 1)) false true" "PUT /api/v1/users/{id} answers the edited user with its roles and no possibleScammer"
expect_eq "$(psql_value "select api_key_slots from user_account where id = $LISTED_ID;")" "$((LISTED_SLOTS + 1))" "the edit is saved"
http -X PUT "$BASE/api/v1/users/$LISTED_ID" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d "{\"id\":$LISTED_ID,\"apiKeySlots\":$LISTED_SLOTS}"
expect_eq "$HTTP_STATUS" 200 "the slots are put back"

section "20. Without open-in-view: a link created with an API key, an owner's edits, a password change and a reset"
http -X POST "$BASE/api/v1/api-keys" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/api-keys as $USER_EMAIL"
OWNER_KEY=$(jq -r .key <<<"$HTTP_BODY")
http -X POST "$BASE/api/v1/urls" -H "X-Api-Key: $OWNER_KEY" -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com/created-with-a-key"}'
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/urls with X-Api-Key"
KEY_LINK_ID=$(jq -r .id <<<"$HTTP_BODY")
expect_eq "$(psql_value "select u.email || ' ' || k.api_calls_used from url l join user_account u on u.id = l.owner_id join api_key k on k.owner_id = u.id where l.id = $KEY_LINK_ID and k.key = '$OWNER_KEY';")" \
    "$USER_EMAIL 1" "the link belongs to the key's owner and the key counts one call"
http -X PATCH "$BASE/api/v1/urls/$KEY_LINK_ID/deactivate" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS" 200 "the owner deactivates the link"
http -X PATCH "$BASE/api/v1/urls/$KEY_LINK_ID/activate" -H "Authorization: Bearer $KEY_JWT"
expect_eq "$HTTP_STATUS $(jq -r .active <<<"$HTTP_BODY")" "200 true" "PATCH /api/v1/urls/{id}/activate: the owner reactivates it"
http -X PUT "$BASE/api/v1/urls/$KEY_LINK_ID" -H "Authorization: Bearer $KEY_JWT" -H 'Content-Type: application/json' \
    -d "{\"id\":$KEY_LINK_ID,\"longUrl\":\"https://example.com/created-with-a-key\",\"visitLimit\":5}"
expect_eq "$HTTP_STATUS $(jq -r .visitLimit <<<"$HTTP_BODY")" "200 5" "PUT /api/v1/urls/{id}: the owner edits it"
http -X PATCH "$BASE/api/v1/users/password" -H "Authorization: Bearer $KEY_JWT" -H 'Content-Type: application/json' \
    -d "{\"oldPassword\":\"$USER_PASSWORD\",\"newPassword\":\"changed-$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "PATCH /api/v1/users/password"
expect_contains "$(jq -c '[.authorities[].name]' <<<"$HTTP_BODY")" '"ROLE_USER"' "the answer carries the roles"
http -X POST "$BASE/api/v1/auth/password-reset" -H 'Content-Type: application/json' -d "{\"email\":\"$USER_EMAIL\"}"
expect_eq "$HTTP_STATUS" 202 "POST /api/v1/auth/password-reset"
RESET_TOKEN=$(psql_value "select r.token from reset_token r join user_account u on u.id = r.user_id where u.email = '$USER_EMAIL' and r.active order by r.id desc limit 1;")
http -X POST "$BASE/api/v1/auth/password-reset/confirm" -H 'Content-Type: application/json' \
    -d "{\"token\":\"$RESET_TOKEN\",\"email\":\"$USER_EMAIL\",\"password\":\"reset-$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/password-reset/confirm"
expect_contains "$(jq -c '[.authorities[].name]' <<<"$HTTP_BODY")" '"ROLE_USER"' "the answer carries the roles"

section "21. Notifications and consent"
NOTIFY_PASSWORD=reset-$USER_PASSWORD
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$NOTIFY_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as $USER_EMAIL with the password section 20 reset"
NOTIFY_JWT=$(jq -r .token <<<"$HTTP_BODY")
NOTIFY_JWT=${NOTIFY_JWT#Bearer }
NOTIFY_USER_ID=$(psql_value "select id from user_account where email = '$USER_EMAIL';")
active_consents() {
    psql_value "select count(*) from notification_consent where user_id = $NOTIFY_USER_ID and revoked_at is null;"
}
expect_eq "$(psql_value "select count(*) from databasechangelog where id like '031-%' and exectype = 'EXECUTED';")" 7 "031 ran all seven changesets"
http -H "Authorization: Bearer $NOTIFY_JWT" "$BASE/api/v1/users/me/notifications"
expect_eq "$HTTP_STATUS $(jq -c '[.[].type]' <<<"$HTTP_BODY")" '200 ["DEAD_LINK_EMAIL","WEEKLY_SUMMARY_EMAIL","EXPIRY_REMINDER_EMAIL"]' \
    "GET /api/v1/users/me/notifications lists the three email types"
expect_eq "$(jq -c '[.[].consented]' <<<"$HTTP_BODY")" '[false,false,false]' "every email is off until the user turns it on"
http -X PUT "$BASE/api/v1/users/me/notifications/WEEKLY_SUMMARY_EMAIL" -H "Authorization: Bearer $NOTIFY_JWT" -H 'Content-Type: application/json' \
    -d '{"consented":true}'
expect_eq "$HTTP_STATUS $(jq -r '"\(.type) \(.consented)"' <<<"$HTTP_BODY")" "200 WEEKLY_SUMMARY_EMAIL true" "PUT .../WEEKLY_SUMMARY_EMAIL turns the weekly summary on"
expect_eq "$(active_consents)" 1 "one active consent row"

echo "  --- the unsubscribe token, signed in the shell with the local profile's dev key as NotificationKeys derives it"
NOTIFY_DEV_KEY=$(awk '/^  notifications:/ { inside = 1 } inside && $1 == "key:" { print $2; exit }' "$ROOT/src/main/resources/application-local.yaml")
[[ -n $NOTIFY_DEV_KEY ]] || fail "no app.notifications.key in application-local.yaml"
hex_of() {
    od -An -tx1 | tr -d ' \n'
}
NOTIFY_MASTER_HEX=$(printf '%s' "$NOTIFY_DEV_KEY" | openssl base64 -A -d | hex_of)
NOTIFY_UNSUBSCRIBE_HEX=$(printf '%s' hrva-unsubscribe-v1 | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$NOTIFY_MASTER_HEX" -binary | hex_of)
UNSUBSCRIBE_PAYLOAD="v1.$NOTIFY_USER_ID.WEEKLY_SUMMARY_EMAIL"
UNSUBSCRIBE_TOKEN="$(printf '%s' "$UNSUBSCRIBE_PAYLOAD" | b64url).$(printf '%s' "$UNSUBSCRIBE_PAYLOAD" \
    | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$NOTIFY_UNSUBSCRIBE_HEX" -binary | b64url)"
UNSUBSCRIBE_URL="$BASE/api/v1/notifications/unsubscribe?token=$UNSUBSCRIBE_TOKEN"
expect_eq "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$UNSUBSCRIBE_URL")" "302 $FRONTEND_URL/unsubscribe?token=$UNSUBSCRIBE_TOKEN" \
    "GET .../unsubscribe answers 302 to the frontend page"
expect_eq "$(active_consents)" 1 "a GET revokes nothing"
http -X POST "$UNSUBSCRIBE_URL" -d 'List-Unsubscribe=One-Click'
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '200 {"type":"WEEKLY_SUMMARY_EMAIL","alreadyOff":false}' \
    "the RFC 8058 one-click POST turns the weekly summary off"
expect_eq "$(active_consents)" 0 "no active consent row is left"
expect_eq "$(psql_value "select revoked_source from notification_consent where user_id = $NOTIFY_USER_ID and type = 'WEEKLY_SUMMARY_EMAIL';")" \
    UNSUBSCRIBE_LINK "the row is kept, revoked by the unsubscribe link"
http -X POST "$UNSUBSCRIBE_URL" -d 'List-Unsubscribe=One-Click'
expect_eq "$HTTP_STATUS $(jq -r .alreadyOff <<<"$HTTP_BODY")" "200 true" "the same POST again says it is already off"
http -X POST "$BASE/api/v1/notifications/unsubscribe?token=$(printf 'v1.%s.DEAD_LINK_EMAIL' "$NOTIFY_USER_ID" | b64url).${UNSUBSCRIBE_TOKEN#*.}"
expect_eq "$HTTP_STATUS" 400 "the weekly summary's signature on a dead-link email token is refused"

echo "  --- channels"
NOTIFY_CHANNELS=$BASE/api/v1/users/me/notification-channels
http -X POST "$NOTIFY_CHANNELS" -H "Authorization: Bearer $NOTIFY_JWT" -H 'Content-Type: application/json' \
    -d '{"name":"E2E","type":"WEBHOOK","events":["LINK_DEAD"],"target":"https://127.0.0.1/hook"}'
expect_eq "$HTTP_STATUS" 400 "a webhook to 127.0.0.1 is refused"
expect_contains "$(jq -r .target <<<"$HTTP_BODY")" "public" "the target error asks for a public address"
DISCORD_CHANNEL_SECRET=e2e-secret-abcd
http -X POST "$NOTIFY_CHANNELS" -H "Authorization: Bearer $NOTIFY_JWT" -H 'Content-Type: application/json' \
    -d "{\"name\":\"E2E\",\"type\":\"DISCORD\",\"events\":[\"LINK_DEAD\"],\"target\":\"https://discord.com/api/webhooks/1/$DISCORD_CHANNEL_SECRET\"}"
expect_eq "$HTTP_STATUS" 201 "POST .../notification-channels adds a Discord channel"
MASK_DOTS=$(jq -rn '[8226, 8226, 8226, 8226] | implode')
expect_eq "$(jq -r .maskedTarget <<<"$HTTP_BODY")" "https://discord.com/${MASK_DOTS}abcd" "the answer carries the mask"
expect_eq "$(jq -c .events <<<"$HTTP_BODY")" '["LINK_DEAD"]' "with its event"
[[ $HTTP_BODY != *e2e-secret* ]] || fail "the channel answer contains the webhook URL's secret"
ok "the answer does not contain the webhook URL"
NOTIFY_CHANNEL_ID=$(jq -r .id <<<"$HTTP_BODY")
expect_eq "$(psql_value "select (target_sealed like 'v1:%') || ' ' || (target_sealed not like '%e2e-secret%') from notification_channel where id = $NOTIFY_CHANNEL_ID;")" \
    "true true" "the URL is sealed at rest"
expect_eq "$(active_consents)" 1 "the ticked event is an active consent row"
grep -q 'e2e-secret' "$WORK/app.log" && fail "the app log contains the webhook URL's secret"
ok "the app log does not contain the webhook URL"

echo "  --- a dead destination"
DEAD_ALIAS=$(psql_value "select short_url from url where id = $KEY_LINK_ID;")
psql_value "update url set destination_dead_since = now(), destination_last_status = '404' where id = $KEY_LINK_ID;" >/dev/null
http -G "$BASE/api/v1/urls" -H "Authorization: Bearer $NOTIFY_JWT" --data-urlencode "size=100"
expect_eq "$(jq -r --argjson id "$KEY_LINK_ID" '.content[] | select(.id == $id) | .destinationHealth.lastStatus' <<<"$HTTP_BODY")" 404 \
    "the owner's list marks $DEAD_ALIAS dead with 404"
http -A "$BROWSER_USER_AGENT" "$BASE/api/v1/urls/$DEAD_ALIAS"
expect_eq "$HTTP_STATUS $(jq -r 'has("destinationHealth")' <<<"$HTTP_BODY")" "200 false" \
    "the public GET /api/v1/urls/$DEAD_ALIAS (a browser; the link has a visit limit, which refuses bots) has no destinationHealth"
http -G "$BASE/api/v1/urls/all" -H "Authorization: Bearer $ADMIN_JWT" --data-urlencode "deadDestination=true"
expect_eq "$(jq -r '[.content[].shortUrl] | join(",")' <<<"$HTTP_BODY")" "$DEAD_ALIAS" "the admin's dead-destination filter finds only $DEAD_ALIAS"

echo "  --- deleting the channel keeps its consent history"
http -X DELETE "$NOTIFY_CHANNELS/$NOTIFY_CHANNEL_ID" -H "Authorization: Bearer $NOTIFY_JWT"
expect_eq "$HTTP_STATUS" 204 "DELETE .../notification-channels/{id}"
expect_eq "$(psql_value "select (channel_id is null) || ' ' || channel_label || ' ' || revoked_source from notification_consent where user_id = $NOTIFY_USER_ID and type = 'LINK_DEAD';")" \
    "true Discord: E2E CHANNEL_REMOVED" "its consent row stays, without the channel, revoked as CHANNEL_REMOVED"
expect_eq "$(active_consents)" 0 "no active consent row is left"

http -H "Authorization: Bearer $NOTIFY_JWT" "$BASE/api/v1/users/me/export"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/users/me/export"
show "$(jq -c .notifications <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.notifications.consentHistory | length >= 2' <<<"$HTTP_BODY")" true "the export has the consent history"
expect_eq "$(jq -r '[.notifications.consentHistory[] | "\(.type) \(.revokedSource)"] | join(", ")' <<<"$HTTP_BODY")" \
    "WEEKLY_SUMMARY_EMAIL UNSUBSCRIBE_LINK, LINK_DEAD CHANNEL_REMOVED" "in order, with how each was turned off"
expect_eq "$(jq -c .notifications.channels <<<"$HTTP_BODY")" '[]' "and no channel"
[[ $(jq -c .notifications <<<"$HTTP_BODY") != *e2e-secret* && $(jq -c .notifications <<<"$HTTP_BODY") != *v1:* ]] || fail "the export holds a secret"
ok "the export holds no URL or sealed value"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/users?days=7"
expect_eq "$(jq -c '[.notifications.consents[].value]' <<<"$HTTP_BODY")" \
    '["DEAD_LINK_EMAIL","WEEKLY_SUMMARY_EMAIL","EXPIRY_REMINDER_EMAIL","LINK_DEAD","LINK_BACK","DAILY_SUMMARY","WEEKLY_SUMMARY"]' \
    "users stats list all seven consent types"
expect_eq "$(jq -c '[.notifications.channels[].value], .notifications.autoDisabledChannels' <<<"$HTTP_BODY" | paste -sd' ' -)" \
    '["DISCORD","SLACK","TELEGRAM","NTFY","WEBHOOK"] 0' "and all five channel types"
expect_eq "$(jq -r '[.notifications.consents[].count, .notifications.channels[].count] | add' <<<"$HTTP_BODY")" 0 "with nothing active left"
http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/stats/links?days=7"
expect_eq "$(jq -c .deadDestinations <<<"$HTTP_BODY")" '{"total":1,"anonymous":0}' "links stats count the dead destination"

section "22. Custom domains"
DOMAIN_HOST=links.example.com
MY_DOMAINS=$BASE/api/v1/users/me/domains
ADMIN_DOMAINS=$BASE/api/v1/admin/domains
cf_calls() {
    curl -sS "$CF_BASE/_control/calls"
}
cf_control() {
    curl -sS -o /dev/null -X POST "$CF_BASE/_control/$1"
}
# cf_count <method> [query part]: how many API calls the fake got with that method and, when given, that text in the query.
cf_count() {
    cf_calls | jq --arg method "$1" --arg query "${2:-}" '[.calls[] | select(.method == $method and (.query | contains($query)))] | length'
}
cf_call_lines() {
    cf_calls | jq -c '[.calls[] | "\(.method) \(.path | sub("^/client/v4/zones/[^/]+"; ""))\(if .query == "" then "" else "?" + .query end) \(.status)"]'
}
host_redirect() {
    curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$@"
}
admin_domain_row() {
    http -G "$ADMIN_DOMAINS" -H "Authorization: Bearer $ADMIN_JWT" --data-urlencode "search=$DOMAIN_HOST"
    ADMIN_ROW=$(jq -c --argjson id "$DOMAIN_ID" '.content[] | select(.id == $id)' <<<"$HTTP_BODY")
}
email_statuses() {
    psql_value "select coalesce(string_agg(status, ','), 'none') from email_log where subject = '$1';"
}
expect_eq "$(psql_value "select count(*) from information_schema.tables where table_name in ('custom_domain', 'custom_domain_allowance', 'custom_domain_reconcile', 'custom_domain_cap_lock');")" \
    4 "032 created the four custom domain tables"
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"reset-$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as $USER_EMAIL with the password section 20 reset"
DOMAIN_JWT=$(jq -r .token <<<"$HTTP_BODY")
DOMAIN_JWT=${DOMAIN_JWT#Bearer }
cf_control 'reset?scope=calls'
show "the fake's call log is cleared (the startup token check and reconcile catch-up called it before this section)"

echo "  --- applying"
http -X POST "$MY_DOMAINS" -H "Authorization: Bearer $DOMAIN_JWT" -H 'Content-Type: application/json' \
    -d '{"hostname":"Links.Example.com.","reason":"E2E"}'
show "POST /api/v1/users/me/domains Links.Example.com. -> $HTTP_STATUS $(jq -c '{id, hostname, status, dnsRecord, steps}' <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS $(jq -r '"\(.hostname) \(.status) \(.dnsRecord)"' <<<"$HTTP_BODY")" "201 $DOMAIN_HOST PENDING_REVIEW null" \
    "the application is stored lower-cased without the trailing dot, PENDING_REVIEW, with no DNS record yet"
DOMAIN_ID=$(jq -r .id <<<"$HTTP_BODY")
http -X POST "$MY_DOMAINS" -H "Authorization: Bearer $DOMAIN_JWT" -H 'Content-Type: application/json' \
    -d '{"hostname":"second.example.com","reason":"E2E"}'
expect_eq "$HTTP_STATUS" 400 "a second application is refused"
expect_contains "$(jq -r .error <<<"$HTTP_BODY")" "You can have 1 custom domain." "with the limit text"
http -X POST "$MY_DOMAINS" -H "Authorization: Bearer $DOMAIN_JWT" -H 'Content-Type: application/json' \
    -d '{"hostname":"example.com","reason":"E2E"}'
expect_eq "$HTTP_STATUS" 400 "an apex is refused"
expect_contains "$(jq -r .hostname <<<"$HTTP_BODY")" "Use a subdomain" "with the hostname field text"
expect_eq "$(cf_calls | jq -c .calls)" "[]" "the fake Cloudflare got no call: nothing reaches Cloudflare before approval"

echo "  --- the token check, and approval refused while the token can't manage hostnames"
http -X POST "$ADMIN_DOMAINS/cloudflare-check" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS $(jq -r .status <<<"$HTTP_BODY")" "200 OK" "POST /api/v1/admin/domains/cloudflare-check: OK"
cf_control 'token?mode=forbidden'
http -X POST "$ADMIN_DOMAINS/cloudflare-check" -H "Authorization: Bearer $ADMIN_JWT"
show "with the fake in mode=forbidden -> $(jq -c '{status, message}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r .status <<<"$HTTP_BODY")" NO_PERMISSION "an active token refused on the zone is NO_PERMISSION"
http -X POST "$ADMIN_DOMAINS/$DOMAIN_ID/approve" -H "Authorization: Bearer $ADMIN_JWT"
show "approve -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 409 "approval is refused"
expect_contains "$(jq -r .error <<<"$HTTP_BODY")" "Cloudflare isn't set up" "because Cloudflare isn't set up"
expect_eq "$(cf_count POST)" 0 "and nothing was created"
cf_control 'token?mode=ok'

echo "  --- approval and the automatic setup"
cf_control 'reset?scope=calls'
http -X POST "$ADMIN_DOMAINS/$DOMAIN_ID/approve" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"note":null}'
expect_eq "$HTTP_STATUS $(jq -r .status <<<"$HTTP_BODY")" "200 APPROVED_PROVISIONING" "POST /api/v1/admin/domains/{id}/approve: APPROVED_PROVISIONING"
domain_awaits_dns() {
    admin_domain_row && [[ $(jq -r '"\(.status) \(.cloudflareId != null)"' <<<"$ADMIN_ROW") == "AWAITING_DNS true" ]]
}
wait_until 90 "AWAITING_DNS with a Cloudflare id" domain_awaits_dns
show "GET /api/v1/admin/domains?search=$DOMAIN_HOST -> $(jq -c '{status, cloudflareId, cloudflareStatus, sslStatus}' <<<"$ADMIN_ROW")"
ok "within 90 s the setup ran: AWAITING_DNS with cloudflareId set"
show "Cloudflare calls since the approval: $(cf_call_lines)"
expect_eq "$(cf_count GET "hostname=$DOMAIN_HOST") $(cf_count POST)" "1 1" "one lookup by hostname, then one create"
http -H "Authorization: Bearer $DOMAIN_JWT" "$MY_DOMAINS/$DOMAIN_ID"
show "GET /api/v1/users/me/domains/{id} -> $HTTP_STATUS $(jq -c '{status, dnsRecord, steps, problem}' <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS $(jq -r '"\(.dnsRecord.type) \(.dnsRecord.name) \(.dnsRecord.target) \(.steps.dnsFound)"' <<<"$HTTP_BODY")" \
    "200 CNAME $DOMAIN_HOST domains.hrva.cc false" "the owner sees the CNAME record to add, not found yet"

echo "  --- the CNAME is seen and the certificate issued"
cf_control "activate?hostname=$DOMAIN_HOST"
domain_active_for_owner() {
    http -H "Authorization: Bearer $DOMAIN_JWT" "$MY_DOMAINS/$DOMAIN_ID" \
        && [[ $(jq -r '"\(.status) \(.steps.dnsFound) \(.steps.certificateIssued)"' <<<"$HTTP_BODY") == "ACTIVE true true" ]]
}
wait_until 60 "ACTIVE for the owner" domain_active_for_owner
ok "after /_control/activate the owner's GET (one live check per 15 s) says ACTIVE with both steps done"

echo "  --- the same alias on the primary host and on the domain"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $DOMAIN_JWT" -H 'Content-Type: application/json' \
    -d '{"longUrl":"https://example.org/e2e-a","shortUrl":"e2e-menu"}'
expect_eq "$HTTP_STATUS $(jq -r 'has("domain")' <<<"$HTTP_BODY")" "200 false" "POST /api/v1/urls e2e-menu on the primary host, with no domain"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $DOMAIN_JWT" -H 'Content-Type: application/json' \
    -d "{\"longUrl\":\"https://example.org/e2e-b\",\"shortUrl\":\"e2e-menu\",\"domain\":\"$DOMAIN_HOST\"}"
expect_eq "$HTTP_STATUS $(jq -r .domain.hostname <<<"$HTTP_BODY")" "200 $DOMAIN_HOST" "POST /api/v1/urls e2e-menu on $DOMAIN_HOST too"
DOMAIN_LINK_ID=$(jq -r .id <<<"$HTTP_BODY")

echo "  --- Host header routing"
expect_eq "$(host_redirect -H "Host: $DOMAIN_HOST" "$BASE/e2e-menu")" "302 https://example.org/e2e-b" "Host: $DOMAIN_HOST /e2e-menu -> the domain's link"
expect_eq "$(host_redirect "$BASE/e2e-menu")" "302 https://example.org/e2e-a" "the primary host's /e2e-menu -> its own link"
expect_eq "$(host_redirect -H "Host: $DOMAIN_HOST" "$BASE/")" "302 $FRONTEND_URL/" "Host: $DOMAIN_HOST / -> the frontend's home page"
for path in /login /api/v1/users/me /actuator/health /@x; do
    expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -H "Host: $DOMAIN_HOST" "$BASE$path")" 404 "Host: $DOMAIN_HOST $path -> 404"
done
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: nobody.example.net' "$BASE/e2e-menu")" 404 "an unknown host -> 404"
http "$BASE/api/v1/urls/e2e-menu?domain=$DOMAIN_HOST"
expect_eq "$HTTP_STATUS $(jq -r .longUrl <<<"$HTTP_BODY")" "200 https://example.org/e2e-b" "GET /api/v1/urls/e2e-menu?domain=$DOMAIN_HOST finds the domain's link"

echo "  --- turned off and on"
http -X POST "$ADMIN_DOMAINS/$DOMAIN_ID/disable" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d '{"note":"E2E"}'
expect_eq "$HTTP_STATUS $(jq -r '"\(.status) \(.disabledReason)"' <<<"$HTTP_BODY")" "200 DISABLED ADMIN" "POST /api/v1/admin/domains/{id}/disable"
expect_eq "$(host_redirect -H "Host: $DOMAIN_HOST" "$BASE/e2e-menu")" "302 $FRONTEND_URL/unavailable?s=e2e-menu&reason=domain&d=$DOMAIN_HOST" \
    "at once, the domain's link goes to the unavailable page (reason=domain), its cached entry bypassed"
http -X POST "$ADMIN_DOMAINS/$DOMAIN_ID/enable" -H "Authorization: Bearer $ADMIN_JWT"
show "POST /api/v1/admin/domains/{id}/enable -> $HTTP_STATUS $(jq -c '{status, disabledReason}' <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS" 200 "turned on again"
domain_link_redirects() {
    [[ $(host_redirect -H "Host: $DOMAIN_HOST" "$BASE/e2e-menu") == "302 https://example.org/e2e-b" ]]
}
wait_until 90 "the domain's link redirecting again" domain_link_redirects
ok "the domain's link redirects to its destination again once a check finds it active"

echo "  --- removed with its links moved to the primary host"
cf_control 'reset?scope=calls'
http -X DELETE "$ADMIN_DOMAINS/$DOMAIN_ID?links=MOVE" -H "Authorization: Bearer $ADMIN_JWT"
show "DELETE /api/v1/admin/domains/{id}?links=MOVE -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS $(jq -r '"\(.moved) \(.renamed | length) \(.renamed[0].from)"' <<<"$HTTP_BODY")" "200 1 1 e2e-menu" \
    "the link moved, renamed because the primary host already has e2e-menu"
RENAMED_TO=$(jq -r '.renamed[0].to' <<<"$HTTP_BODY")
expect_eq "$(psql_value "select (domain_id is null) || ' ' || short_url from url where id = $DOMAIN_LINK_ID;")" "true $RENAMED_TO" \
    "Postgres: the moved link has domain_id null and the new alias"
expect_eq "$(curl -s -o /dev/null -w '%{http_code}' -H "Host: $DOMAIN_HOST" "$BASE/e2e-menu")" 404 "Host: $DOMAIN_HOST is no longer served"
cloudflare_hostname_deleted() {
    [[ $(cf_count DELETE) == 1 && $(psql_value "select cloudflare_delete_pending from custom_domain where id = $DOMAIN_ID;") == f ]]
}
wait_until 90 "the queued Cloudflare delete" cloudflare_hostname_deleted
show "Cloudflare calls since the removal: $(cf_call_lines)"
ok "within 90 s the lifecycle job deleted the hostname in Cloudflare and cleared cloudflare_delete_pending"
expect_eq "$(psql_value "select status || ' ' || (hostname_key is null) from custom_domain where id = $DOMAIN_ID;")" "DELETED true" \
    "the row stays DELETED for its owner, its hostname free to apply for again"

echo "  --- emails and the export"
domain_emails_sent() {
    [[ $(email_statuses "Your domain $DOMAIN_HOST is approved: one step left") == SENT \
        && $(email_statuses "Your domain $DOMAIN_HOST was turned off") == SENT \
        && $(email_statuses "Your domain $DOMAIN_HOST was removed") == SENT ]]
}
wait_until 30 "the approved, turned off and removed emails" domain_emails_sent
psql_query "select subject, status from email_log where subject like 'Your domain%' order by id;"
ok "email_log: the approved, turned off and removed emails are SENT (Mailpit took them)"
http -H "Authorization: Bearer $DOMAIN_JWT" "$BASE/api/v1/users/me/export"
show "GET /api/v1/users/me/export .domains -> $(jq -c .domains <<<"$HTTP_BODY")"
expect_eq "$HTTP_STATUS $(jq -r '.domains | length' <<<"$HTTP_BODY")" "200 1" "the export lists the domain"
expect_eq "$(jq -r '.domains[0] | [has("cloudflareId"), has("lastError"), has("applyFlags")] | any' <<<"$HTTP_BODY")" false \
    "with no Cloudflare id, last error or apply flags"
expect_eq "$(jq -r '.domains[0] | [has("disabledReason"), has("disabledNote")] | all' <<<"$HTTP_BODY")" true \
    "and with disabledReason and disabledNote (null once turned on again)"

section "23. Moderation: campaign holds"
CAMPAIGN_LINE_PREFIX='Campaign held: 3 links without an account on 3 domains'
SHAPE_ALIASES=()
create_shape_link() {
    http -X POST "$BASE/api/v1/urls" -H "CF-Connecting-IP: $1" -H 'Content-Type: application/json' -d "{\"longUrl\":\"$2\"}"
    expect_eq "$HTTP_STATUS" 200 "a link without an account from $1 to $2"
    SHAPE_ALIASES+=("$(jq -r .shortUrl <<<"$HTTP_BODY")")
}
campaign_lines() {
    jq -s --arg prefix "$CAMPAIGN_LINE_PREFIX" '[.[] | select(.content | startswith($prefix))] | length' "$DISCORD_POSTS"
}
any_campaign_lines() {
    jq -s '[.[] | select(.content | startswith("Campaign held:") or startswith("Same-shape links:"))] | length' "$DISCORD_POSTS"
}
campaign_posted() {
    [[ $(campaign_lines) -ge 1 ]]
}
held_campaign_links() {
    psql_value "select count(*) from url where long_url like '%$1%' and not active and block_reason = 'CAMPAIGN' and owner_id is null;"
}
campaign_links_held() {
    [[ $(held_campaign_links "$1") == "$2" ]]
}
create_shape_link 203.0.113.21 'https://a1.e2e-shape-a.com/wqamhcsom?r=1&t=1'
create_shape_link 203.0.113.22 'https://a1.e2e-shape-b.com/wqamhcsom?r=1&t=1'
create_shape_link 203.0.113.23 'https://a1.e2e-shape-c.com/wqamhcsom?r=1&t=1'
wait_until 15 "the three links held as CAMPAIGN in Postgres" campaign_links_held e2e-shape- 3
psql_query "select u.id, u.short_url, u.active, u.block_reason, u.owner_id is null as anonymous, u.long_url from url u where u.long_url like '%e2e-shape-%' order by u.id;"
expect_eq "$(held_campaign_links e2e-shape-)" 3 "Postgres holds the three links as CAMPAIGN: inactive, without an owner"
expect_eq "$(psql_value "select count(*) from campaign;")" 1 "and has one campaign row"
expect_eq "$(psql_value "select status || ' ' || path || ' ' || query_names || ' ' || (select count(*) from campaign_link l where l.campaign_id = campaign.id) from campaign;" | tr '\n' ' ')" \
    "HOLDING /wqamhcsom r t 3 " "it is HOLDING /wqamhcsom with the query names r and t and three campaign_link rows"
wait_until 45 "the campaign line at the Discord stub (30-second buffer)" campaign_posted
CAMPAIGN_POST=$(jq -r --arg prefix "$CAMPAIGN_LINE_PREFIX" 'select(.content | startswith($prefix)) | .content' "$DISCORD_POSTS")
show "$CAMPAIGN_POST"
expect_eq "$(campaign_lines)" 1 "the Discord stub got one Campaign held: 3 links line"
expect_contains "$CAMPAIGN_POST" "share the URL shape /wqamhcsom?r&t in the last 24 hours." "the line names the shape, with the query values left out"
expect_contains "$CAMPAIGN_POST" "New links with this shape are held for 7 days after the last one." "it says how long new links are held"
expect_contains "$CAMPAIGN_POST" "Review: <$FRONTEND_URL/admin/campaigns?search=%2Fwqamhcsom>, links: <$FRONTEND_URL/admin/urls?search=%2Fwqamhcsom>" \
    "and links to the campaign on the Campaigns page and the matching links in All URLs"
for alias in "${SHAPE_ALIASES[@]}"; do
    expect_contains "$CAMPAIGN_POST" "$alias" "the line shows the example $alias"
done
expect_eq "$(jq -sc --arg prefix "$CAMPAIGN_LINE_PREFIX" '[.[] | select(.content | startswith($prefix)) | {allowed_mentions, flags}] | first' "$DISCORD_POSTS")" \
    '{"allowed_mentions":{"parse":[]},"flags":4}' "no mentions, no embeds"
SHAPE_KEY=$(printf '/wqamhcsom\nr,t' | openssl dgst -sha256 | awk '{print $NF}')
expect_eq "$(psql_value "select shape_key from campaign;")" "$SHAPE_KEY" "campaign.shape_key is the sha256 of the shape"
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw zcard "campaign:shape:$SHAPE_KEY")" 3 "campaign:shape:<sha256 of the shape> holds the three links"
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw exists "campaign:alerted:$SHAPE_KEY")" 0 "there is no campaign:alerted: key any more"

create_shape_link 203.0.113.21 'https://a1.e2e-shape-d.com/wqamhcsom?r=1&t=1'
expect_eq "$(jq -r '[.active, .status] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "true,ACTIVE" "the fourth link on -d.com is answered active: true"
FOURTH_ALIAS=${SHAPE_ALIASES[3]}
expect_eq "$(psql_value "select active || ' ' || coalesce(block_reason, '') || ' ' || (owner_id is null) from url where short_url = '$FOURTH_ALIAS';")" \
    "false CAMPAIGN true" "while Postgres holds it as CAMPAIGN, inactive and without an owner"
expect_eq "$(psql_value "select count(*) from campaign_link;")" 4 "the campaign has four campaign_link rows"
campaign_counted() {
    [[ $(docker exec "$REDIS_CONTAINER" redis-cli --raw zcard "campaign:shape:$SHAPE_KEY") == 4 ]]
}
wait_until 15 "the fourth link counted" campaign_counted
ok "the fourth link is counted"
create_shape_link 203.0.113.22 'https://a.e2e-generic-x.com/index.php?id=1'
create_shape_link 203.0.113.23 'https://a.e2e-generic-y.com/index.php?id=1'
create_shape_link 203.0.113.21 'https://a.e2e-generic-z.com/index.php?id=1'
echo "  waiting 40 s, longer than the Discord buffer's 30-second flush, for a second line that must not come"
sleep 40
expect_eq "$(any_campaign_lines)" 1 "the fourth domain and the three index.php links add no line"
expect_eq "$(grep -c 'Campaign alert shape=' "$WORK/app.log" || true)" 1 "the app logged one campaign alert"
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw --scan --pattern 'campaign:shape:*')" "campaign:shape:$SHAPE_KEY" \
    "index.php has no shape: only the campaign shape is counted in Redis"
psql_query "select u.id, u.short_url, u.active, u.block_reason, u.owner_id is null as anonymous, u.long_url from url u where u.long_url like '%e2e-shape-%' or u.long_url like '%e2e-generic-%' order by u.id;"
expect_eq "$(held_campaign_links e2e-shape-)" 4 "all four campaign links are held as CAMPAIGN"
expect_eq "$(psql_value "select count(*) filter (where active and owner_id is null and blocked_at is null) || ' of ' || count(*) from url where long_url like '%e2e-generic-%';")" \
    "3 of 3" "the three index.php links are active, without an account and not blocked"
expect_eq "$(psql_value "select count(*) from campaign_link l join url u on u.id = l.url_id where u.long_url like '%e2e-generic-%';") $(psql_value "select count(*) from campaign;")" \
    "0 1" "index.php made no campaign_link row and no campaign row"

section "24. Moderation: settings"
SETTINGS_URL=$BASE/api/v1/admin/settings
put_setting() {
    http -X PUT "$SETTINGS_URL/$1" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d "{\"value\":$2}"
}
reset_setting() {
    http -X DELETE "$SETTINGS_URL/$1" -H "Authorization: Bearer $ADMIN_JWT"
}
http -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"reset-$USER_PASSWORD\"}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/auth/login as $USER_EMAIL with the password section 20 reset"
SETTINGS_JWT=$(jq -r .token <<<"$HTTP_BODY")
SETTINGS_JWT=${SETTINGS_JWT#Bearer }
http "$SETTINGS_URL" -H "Authorization: Bearer $SETTINGS_JWT"
expect_eq "$HTTP_STATUS" 403 "GET /api/v1/admin/settings as $USER_EMAIL"
http "$SETTINGS_URL" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/settings as admin@localhost"
expect_eq "$(jq -r '[.[] | select(.changed)] | length' <<<"$HTTP_BODY")" 0 "no setting starts changed"
expect_eq "$(jq -r '[.[] | select(.key == "ANONYMOUS_LINKS" or .key == "CUSTOM_ALIASES" or .key == "SEND_EMAILS") | .value] | map(tostring) | join(",")' <<<"$HTTP_BODY")" \
    "true,true,true" "links without an account, custom aliases and emails start on in the local profile"
expect_eq "$(jq -r '.[] | select(.key == "CAMPAIGN_LINKS") | .value' <<<"$HTTP_BODY")" 3 "the same-shape alert starts at 3 links"
expect_eq "$(jq -r '[.[] | select(.key == "CAMPAIGN_HOLD" or .key == "CAMPAIGN_HOLD_DAYS") | .value] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "true,7" \
    "campaign holds start on, for 7 days"
http "$BASE/api/v1/public/settings"
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '200 {"anonymousLinks":true,"newSignups":true,"customAliases":true}' "GET /api/v1/public/settings without a token"

echo "  --- links without an account"
put_setting ANONYMOUS_LINKS false
expect_eq "$HTTP_STATUS" 200 "PUT /api/v1/admin/settings/ANONYMOUS_LINKS false"
show "$(jq -c '{key, value, defaultValue, changed, changedBy}' <<<"$HTTP_BODY")"
expect_eq "$(jq -c '[.value, .changed, .changedBy]' <<<"$HTTP_BODY")" '[false,true,"admin@localhost"]' "the answer shows the switch off, changed by the admin"
http -X POST "$BASE/api/v1/urls" -H 'CF-Connecting-IP: 203.0.113.30' -H 'Content-Type: application/json' -d '{"longUrl":"https://e2e-settings-anon.com/a"}'
show "POST /api/v1/urls without an account -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 400 "a link without an account is refused"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "Sign in to create short URLs" "with the sign-in text"
http "$BASE/api/v1/public/settings"
expect_eq "$(jq -r .anonymousLinks <<<"$HTTP_BODY")" false "GET /api/v1/public/settings: anonymousLinks is false"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' -d '{"longUrl":"https://e2e-settings-anon.com/signed-in"}'
expect_eq "$HTTP_STATUS" 200 "$USER_EMAIL can still make a link"
put_setting ANONYMOUS_LINKS true
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 true,false" "PUT ANONYMOUS_LINKS true is the default again"
http -X POST "$BASE/api/v1/urls" -H 'CF-Connecting-IP: 203.0.113.30' -H 'Content-Type: application/json' -d '{"longUrl":"https://e2e-settings-anon.com/b"}'
expect_eq "$HTTP_STATUS" 200 "a link without an account is made again"
settings_lines() {
    jq -s --arg line "$1" '[.[].content | scan($line)] | length' "$DISCORD_POSTS"
}
settings_line_posted() {
    [[ $(settings_lines "$1") -ge 1 ]]
}
wait_until 45 "the settings line at the Discord stub (30-second buffer)" settings_line_posted "Links without an account turned off by admin@localhost"
wait_until 45 "the turned-on line at the Discord stub (30-second buffer)" settings_line_posted "Links without an account turned on by admin@localhost"
expect_eq "$(settings_lines 'Links without an account turned off by admin@localhost')" 1 "the Discord stub got Links without an account turned off by admin@localhost once"
expect_eq "$(settings_lines 'Links without an account turned on by admin@localhost')" 1 "and turned on by admin@localhost once"
expect_eq "$(psql_value "select count(*) from audit_log where action = 'CHANGE_SETTING' and target_identifier = 'ANONYMOUS_LINKS' and performed_by = 'admin@localhost';")" 2 \
    "the audit log has both changes"

echo "  --- custom aliases"
put_setting CUSTOM_ALIASES false
expect_eq "$HTTP_STATUS" 200 "PUT /api/v1/admin/settings/CUSTOM_ALIASES false"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' \
    -d '{"longUrl":"https://e2e-settings-alias.com/a","shortUrl":"e2e-settings-alias"}'
show "POST /api/v1/urls shortUrl=e2e-settings-alias as $USER_EMAIL -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 400 "a chosen alias is refused"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "Custom aliases are turned off right now." "with the turned-off text"
http "$BASE/api/v1/public/settings"
expect_eq "$(jq -r .customAliases <<<"$HTTP_BODY")" false "GET /api/v1/public/settings: customAliases is false"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' -d '{"longUrl":"https://e2e-settings-alias.com/b"}'
expect_eq "$HTTP_STATUS" 200 "a generated alias still works"
reset_setting CUSTOM_ALIASES
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 true,false" "DELETE CUSTOM_ALIASES resets it"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' \
    -d '{"longUrl":"https://e2e-settings-alias.com/c","shortUrl":"e2e-settings-alias"}'
expect_eq "$HTTP_STATUS" 200 "the chosen alias works again"

echo "  --- new sign-ups"
put_setting NEW_SIGNUPS false
expect_eq "$HTTP_STATUS" 200 "PUT /api/v1/admin/settings/NEW_SIGNUPS false"
http -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' -d '{"email":"e2e-settings-signup@example.org","password":"e2e-settings-signup"}'
show "POST /api/v1/auth/register -> $HTTP_STATUS $HTTP_BODY"
expect_eq "$HTTP_STATUS" 400 "an email sign-up is refused"
expect_eq "$(jq -r .error <<<"$HTTP_BODY")" "Creating new accounts is paused." "with the paused text"
http "$BASE/api/v1/public/settings"
expect_eq "$(jq -r .newSignups <<<"$HTTP_BODY")" false "GET /api/v1/public/settings: newSignups is false"
reset_setting NEW_SIGNUPS
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 true,false" "DELETE NEW_SIGNUPS resets it"
expect_eq "$(psql_value "select count(*) from user_account where email = 'e2e-settings-signup@example.org';")" 0 "no account was made"

echo "  --- emails"
EMAIL_BLOCKED_SUBJECT='Your link was blocked as unsafe'
EMAIL_LOG_BEFORE=$(psql_value "select coalesce(max(id), 0) from email_log;")
put_setting SEND_EMAILS false
expect_eq "$HTTP_STATUS" 200 "PUT /api/v1/admin/settings/SEND_EMAILS false"
http -X POST "$BASE/api/v1/urls" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' -d '{"longUrl":"https://e2e-settings-mail.com/x"}'
expect_eq "$HTTP_STATUS" 200 "$USER_EMAIL makes a link on e2e-settings-mail.com"
http -X POST "$BASE/api/v1/admin/domain-rules" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
    -d '{"domain":"e2e-settings-mail.com","type":"BLOCK","reason":"E2E settings"}'
show "POST /api/v1/admin/domain-rules e2e-settings-mail.com BLOCK -> $HTTP_STATUS $(jq -c '{id: .rule.id, linksAffected}' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '[.linksAffected, .rule.type] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "1,BLOCK" "the admin blocks the link (and its domain)"
MAIL_RULE_ID=$(jq -r .rule.id <<<"$HTTP_BODY")
skipped_email_rows() {
    psql_value "select count(*) from email_log where id > $EMAIL_LOG_BEFORE and recipient = '$USER_EMAIL' and subject = '$EMAIL_BLOCKED_SUBJECT' and status = 'SKIPPED' and error_message = 'Not sent: Send emails is off';"
}
email_row_logged() {
    [[ $(skipped_email_rows) == 1 ]]
}
wait_until 30 "the email_log row" email_row_logged
psql_query "select id, recipient, subject, status, error_message from email_log where id > $EMAIL_LOG_BEFORE order by id;"
expect_eq "$(skipped_email_rows)" 1 "email_log has one SKIPPED row, Not sent: Send emails is off, for the owner's blocked-link email"
expect_eq "$(psql_value "select count(*) from email_log where id > $EMAIL_LOG_BEFORE and status <> 'SKIPPED';")" 0 "no email went out while the switch was off"
reset_setting SEND_EMAILS
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 true,false" "DELETE SEND_EMAILS resets it"
http -X DELETE "$BASE/api/v1/admin/domain-rules/$MAIL_RULE_ID" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS" 204 "the domain rule is removed"

echo "  --- the same-shape alert with CAMPAIGN_HOLD off"
SETTINGS_SHAPE_PATTERN='Same-shape links: [0-9]+ links without an account on [0-9]+ domains share the URL shape /e2esettings\?r&t'
settings_shape_lines() {
    jq -s --arg pattern "$SETTINGS_SHAPE_PATTERN" '[.[].content | scan($pattern)] | length' "$DISCORD_POSTS"
}
settings_shape_posted() {
    [[ $(settings_shape_lines) -ge 1 ]]
}
put_setting CAMPAIGN_HOLD false
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 false,true" "PUT /api/v1/admin/settings/CAMPAIGN_HOLD false"
put_setting CAMPAIGN_LINKS 4
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 4,true" "PUT /api/v1/admin/settings/CAMPAIGN_LINKS 4"
create_shape_link 203.0.113.31 'https://a1.e2e-set-a.com/e2esettings?r=1&t=1'
create_shape_link 203.0.113.32 'https://a1.e2e-set-b.com/e2esettings?r=1&t=1'
create_shape_link 203.0.113.33 'https://a1.e2e-set-c.com/e2esettings?r=1&t=1'
echo "  waiting 40 s, longer than the Discord buffer's 30-second flush, for a line that must not come"
sleep 40
expect_eq "$(settings_shape_lines)" 0 "three same-shape links on three domains give no line while the threshold is 4"
create_shape_link 203.0.113.31 'https://a1.e2e-set-d.com/e2esettings?r=1&t=1'
expect_eq "$(jq -r '[.active, .status] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "true,ACTIVE" "the fourth link is answered active"
wait_until 45 "the same-shape alert at the Discord stub (30-second buffer)" settings_shape_posted
SETTINGS_SHAPE_POST=$(jq -r --arg pattern "$SETTINGS_SHAPE_PATTERN" 'select(.content | test($pattern)) | .content' "$DISCORD_POSTS")
show "$SETTINGS_SHAPE_POST"
expect_eq "$(settings_shape_lines)" 1 "the fourth link on a fourth domain gives exactly one same-shape line"
expect_contains "$SETTINGS_SHAPE_POST" "Same-shape links: 4 links without an account on 4 domains share the URL shape /e2esettings?r&t in the last 24 hours." \
    "and it reads Same-shape links: 4 links"
expect_contains "$SETTINGS_SHAPE_POST" "Nothing was held. Review them: <$FRONTEND_URL/admin/urls?search=%2Fe2esettings> Campaign: <$FRONTEND_URL/admin/campaigns?search=%2Fe2esettings>" \
    "it says nothing was held and links to All URLs and the campaign on the Campaigns page"
expect_eq "$(jq -s '[.[].content | select(startswith("Campaign held:") and contains("/e2esettings"))] | length' "$DISCORD_POSTS")" 0 "no Campaign held: line for the shape"
expect_eq "$(psql_value "select count(*) filter (where active and owner_id is null and blocked_at is null and block_reason is null) || ' of ' || count(*) from url where long_url like '%e2e-set-%';")" \
    "4 of 4" "all four links are active and not blocked: the switch held nothing"
expect_eq "$(psql_value "select status || ' ' || path || ' ' || (select count(*) from campaign_link l where l.campaign_id = c.id) from campaign c where path = '/e2esettings';")" \
    "ALERT_ONLY /e2esettings 4" "the campaign row is ALERT_ONLY with four links"
http "$BASE/api/v1/admin/campaigns?search=wqamhcsom" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS $(jq -c '.content[0] | [.status, .holding, .holdingOff]' <<<"$HTTP_BODY")" '200 ["HOLDING",false,"switch"]' \
    "with CAMPAIGN_HOLD off, section 23's campaign stays HOLDING but answers holding false and holdingOff switch"
reset_setting CAMPAIGN_HOLD
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 true,false" "DELETE CAMPAIGN_HOLD resets it"
http "$BASE/api/v1/admin/campaigns?search=wqamhcsom" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$HTTP_STATUS $(jq -c '.content[0] | [.status, .holding, .holdingOff]' <<<"$HTTP_BODY")" '200 ["HOLDING",true,null]' \
    "with CAMPAIGN_HOLD on again, it holds again"
reset_setting CAMPAIGN_LINKS
expect_eq "$HTTP_STATUS $(jq -r '[.value, .changed] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "200 3,false" "DELETE CAMPAIGN_LINKS resets it"

echo "  --- reset"
http "$SETTINGS_URL" -H "Authorization: Bearer $ADMIN_JWT"
expect_eq "$(jq -r '[.[] | select(.changed)] | length' <<<"$HTTP_BODY")" 0 "no setting has changed: true"
expect_eq "$(psql_value "select count(*) from app_setting;")" 0 "app_setting holds no row"
http "$BASE/api/v1/public/settings"
expect_eq "$(jq -c . <<<"$HTTP_BODY")" '{"anonymousLinks":true,"newSignups":true,"customAliases":true}' "the public switches are all on again"

section "25. Moderation: the Campaigns API and abuse reports"
CAMPAIGNS_URL=$BASE/api/v1/admin/campaigns
ABUSE_URL=$BASE/api/v1/admin/abuse-reports
admin_get() {
    http "$1" -H "Authorization: Bearer $ADMIN_JWT"
}
admin_post() {
    local body=${2-}
    [[ -n $body ]] || body='{}'
    http -X POST "$1" -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' -d "$body"
}
echo "  --- a campaign to work on"
create_shape_link 203.0.113.41 'https://a1.e2e-camp-a.com/e2ecamp?r=1&t=1'
create_shape_link 203.0.113.42 'https://a1.e2e-camp-b.com/e2ecamp?r=1&t=1'
create_shape_link 203.0.113.43 'https://a1.e2e-camp-c.com/e2ecamp?r=1&t=1'
wait_until 15 "the three links held as CAMPAIGN in Postgres" campaign_links_held e2e-camp- 3
create_shape_link 203.0.113.44 'https://a1.e2e-camp-d.com/e2ecamp?r=1&t=1'
expect_eq "$(held_campaign_links e2e-camp-)" 4 "four links of /e2ecamp?r&t are held as CAMPAIGN"
CAMPAIGN_ID=$(psql_value "select id from campaign where path = '/e2ecamp';")
expect_eq "$(psql_value "select count(*) from campaign where path = '/e2ecamp';")" 1 "and they share one campaign row"

echo "  --- access"
http "$CAMPAIGNS_URL"
expect_eq "$HTTP_STATUS" 401 "GET /api/v1/admin/campaigns without a token"
http "$CAMPAIGNS_URL" -H "Authorization: Bearer $SETTINGS_JWT"
expect_eq "$HTTP_STATUS" 403 "GET /api/v1/admin/campaigns as $USER_EMAIL"
http -X POST "$ABUSE_URL" -H "Authorization: Bearer $SETTINGS_JWT" -H 'Content-Type: application/json' -d "{\"campaignId\":$CAMPAIGN_ID}"
expect_eq "$HTTP_STATUS" 403 "POST /api/v1/admin/abuse-reports as $USER_EMAIL"
http -X POST "$ABUSE_URL/reported" -H 'Content-Type: application/json' -d '{"domains":["e2e-camp-a.com"]}'
expect_eq "$HTTP_STATUS" 401 "POST /api/v1/admin/abuse-reports/reported without a token"

echo "  --- the list"
admin_get "$CAMPAIGNS_URL"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/campaigns"
expect_eq "$(jq -r '.page.totalElements' <<<"$HTTP_BODY")" 3 "it lists the three campaigns of sections 23, 24 and 25"
expect_eq "$(jq -r '[.content[].path] | join(",")' <<<"$HTTP_BODY")" "/e2ecamp,/e2esettings,/wqamhcsom" "newest first"
admin_get "$CAMPAIGNS_URL?search=E2ECAMP"
show "$(jq -c '.content[0]' <<<"$HTTP_BODY")"
expect_eq "$(jq -r '.page.totalElements' <<<"$HTTP_BODY")" 1 "search finds the campaign by its path, whatever the case"
expect_eq "$(jq -c '.content[0] | {id, shape, path, queryNames, status, holding}' <<<"$HTTP_BODY")" \
    "{\"id\":$CAMPAIGN_ID,\"shape\":\"/e2ecamp?r&t\",\"path\":\"/e2ecamp\",\"queryNames\":[\"r\",\"t\"],\"status\":\"HOLDING\",\"holding\":true}" "the row names the shape and says it is holding"
expect_eq "$(jq -c '.content[0] | [.links, .domains, .clicks, .reports]' <<<"$HTTP_BODY")" \
    '[{"total":4,"active":0,"held":4,"blocked":0},4,0,{"reported":0,"suspended":0}]' "four links, all held, on four domains, with no clicks and no reports"
expect_eq "$(jq -r '.content[0] | [.allowedBy, .allowedAt] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "null,null" "and nobody has allowed it"
admin_get "$CAMPAIGNS_URL?search=wqamhcsom"
expect_eq "$(jq -r '.content[0] | [.path, (.links.held | tostring)] | join(" ")' <<<"$HTTP_BODY")" "/wqamhcsom 4" "section 23's campaign shows its four held links"
admin_get "$CAMPAIGNS_URL?search=e2esettings"
expect_eq "$(jq -r '.content[0] | [.status, .holding, (.links.active | tostring), (.links.held | tostring)] | join(" ")' <<<"$HTTP_BODY")" "ALERT_ONLY false 4 0" \
    "section 24's campaign is ALERT_ONLY with four active links"
admin_get "$CAMPAIGNS_URL?sort=status,asc"
expect_eq "$(jq -r '[.content[].status] | join(",")' <<<"$HTTP_BODY")" "ALERT_ONLY,HOLDING,HOLDING" "sort=status,asc"
admin_get "$CAMPAIGNS_URL?size=1&page=2&sort=firstSeenAt,asc"
expect_eq "$(jq -r '[.content[0].path, .page.totalPages, .page.number] | map(tostring) | join(" ")' <<<"$HTTP_BODY")" "/e2ecamp 3 2" "size=1&page=2&sort=firstSeenAt,asc is the newest campaign on the third page"
admin_get "$CAMPAIGNS_URL?sort=clicks,desc"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "400 Sort must be lastSeenAt, firstSeenAt or status" "another sort field is a 400"

echo "  --- the detail"
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/campaigns/$CAMPAIGN_ID"
expect_eq "$(jq -c 'keys' <<<"$HTTP_BODY")" '["campaign","domains"]' "the detail has the campaign and its domains, no links"
expect_eq "$(jq -r '.campaign.id' <<<"$HTTP_BODY")" "$CAMPAIGN_ID" "the campaign is the one asked for"
expect_eq "$(jq -c '[.domains[] | {domain, links, report}]' <<<"$HTTP_BODY")" \
    '[{"domain":"e2e-camp-a.com","links":1,"report":null},{"domain":"e2e-camp-b.com","links":1,"report":null},{"domain":"e2e-camp-c.com","links":1,"report":null},{"domain":"e2e-camp-d.com","links":1,"report":null}]' \
    "it lists the four domains, one link each, none reported"
admin_get "$CAMPAIGNS_URL/999999"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "404 There is no campaign 999999." "an unknown campaign is a 404"

echo "  --- the links"
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID/links?sort=createDate,asc"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/campaigns/$CAMPAIGN_ID/links"
expect_eq "$(jq -r '.page.totalElements' <<<"$HTTP_BODY")" 4 "four links"
expect_eq "$(jq -c '[.content[] | [.domain, .active, .blockReason, .clicks]]' <<<"$HTTP_BODY")" \
    '[["e2e-camp-a.com",false,"CAMPAIGN",0],["e2e-camp-b.com",false,"CAMPAIGN",0],["e2e-camp-c.com",false,"CAMPAIGN",0],["e2e-camp-d.com",false,"CAMPAIGN",0]]' \
    "oldest first, each inactive, CAMPAIGN, with its registrable domain and no clicks"
expect_eq "$(jq -r '.content[0].shortUrl' <<<"$HTTP_BODY")" "${SHAPE_ALIASES[$(( ${#SHAPE_ALIASES[@]} - 4 ))]}" "the first one is the link made first (the alias only)"
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID/links?search=e2e-camp-c"
expect_eq "$(jq -r '[.page.totalElements, .content[0].domain] | map(tostring) | join(" ")' <<<"$HTTP_BODY")" "1 e2e-camp-c.com" "search finds a link by its destination"
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID/links?sort=domain,asc"
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "400 Sort must be createDate or id" "another sort field is a 400"
admin_get "$CAMPAIGNS_URL/999999/links"
expect_eq "$HTTP_STATUS" 404 "the links of an unknown campaign are a 404"

echo "  --- block preview"
admin_post "$CAMPAIGNS_URL/$CAMPAIGN_ID/block-preview"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/admin/campaigns/$CAMPAIGN_ID/block-preview"
show "$(jq -c . <<<"$HTTP_BODY")"
expect_eq "$(jq -c '[.[] | [.domain, .hosts, .selectedLinks, .activeLinks, .heldLinks, .rule]]' <<<"$HTTP_BODY")" \
    '[["e2e-camp-a.com",["a1.e2e-camp-a.com"],1,0,1,null],["e2e-camp-b.com",["a1.e2e-camp-b.com"],1,0,1,null],["e2e-camp-c.com",["a1.e2e-camp-c.com"],1,0,1,null],["e2e-camp-d.com",["a1.e2e-camp-d.com"],1,0,1,null]]' \
    "the preview lists four domains with one held link each and no rule"
expect_eq "$(psql_value "select count(*) from domain_rule where domain like 'e2e-camp-%';") $(held_campaign_links e2e-camp-)" "0 4" "the preview changed nothing: no rule, four links still held"

echo "  --- abuse reports"
RDAP_LOG_LINES=$(grep -c 'RDAP' "$WORK/app.log" || true)
seed_rdap() {
    docker exec "$REDIS_CONTAINER" redis-cli set "rdap:info:$1" "$2" EX 3600 >/dev/null
}
seed_rdap e2e-camp-a.com '{"domain":"e2e-camp-a.com","status":"FOUND","registeredOn":"2026-08-14","registrar":"E2E Registrar, LLC","abuseEmail":"abuse@e2e-registrar.example","statuses":["active"]}'
seed_rdap e2e-camp-b.com '{"domain":"e2e-camp-b.com","status":"FOUND","registeredOn":"2026-08-15","registrar":"E2E Registrar, LLC","abuseEmail":"abuse@e2e-registrar.example","statuses":["active"]}'
seed_rdap e2e-camp-c.com '{"domain":"e2e-camp-c.com","status":"FOUND","registeredOn":null,"registrar":"E2E Other Registrar","abuseEmail":"abuse@e2e-other.example","statuses":["active"]}'
seed_rdap e2e-camp-d.com '{"domain":"e2e-camp-d.com","status":"NO_RDAP_SERVER","registeredOn":null,"registrar":null,"abuseEmail":null,"statuses":[]}'
expect_eq "$(docker exec "$REDIS_CONTAINER" redis-cli --raw --scan --pattern 'rdap:info:e2e-camp-*' | wc -l | tr -d ' ')" 4 "rdap:info: holds a seeded record for each of the four domains, so no registry is asked"
admin_post "$ABUSE_URL" "{\"campaignId\":$CAMPAIGN_ID}"
expect_eq "$HTTP_STATUS" 200 "POST /api/v1/admin/abuse-reports for the campaign"
ABUSE_BODY=$HTTP_BODY
show "$(jq -c '{reports: [.reports[] | del(.body)], noContact}' <<<"$ABUSE_BODY")"
expect_eq "$(jq -c '[.reports[] | [.registrar, .to, .cc, [.domains[].domain]]]' <<<"$ABUSE_BODY")" \
    '[["E2E Registrar, LLC","abuse@e2e-registrar.example",["reportphishing@apwg.org"],["e2e-camp-a.com","e2e-camp-b.com"]],["E2E Other Registrar","abuse@e2e-other.example",["reportphishing@apwg.org"],["e2e-camp-c.com"]]]' \
    "two cards, the larger first, each to its registrar's abuse address with the default Cc"
expect_eq "$(jq -c '.reports[0].domains | map([.registeredOn, .report])' <<<"$ABUSE_BODY")" '[["2026-08-14",null],["2026-08-15",null]]' "the card has the registration dates and no report yet"
expect_eq "$(jq -c '.noContact' <<<"$ABUSE_BODY")" '[{"domain":"e2e-camp-d.com","registrar":null,"lookupFailed":false,"reason":"noRegistry","report":null}]' \
    "the domain with no RDAP server is listed as no contact with the reason noRegistry, not as a failed lookup"
expect_contains "$(jq -r '.reports[0].subject' <<<"$ABUSE_BODY")" "Phishing: 2 domains used in links to " "the subject counts the domains"
ABUSE_EMAIL_TEXT=$(jq -r '.reports[0].body' <<<"$ABUSE_BODY")
show "$ABUSE_EMAIL_TEXT"
expect_contains "$ABUSE_EMAIL_TEXT" "I run hrva.cc, a free URL shortener." "the body introduces hrva.cc"
expect_contains "$ABUSE_EMAIL_TEXT" ", registered with E2E Registrar, LLC:" "it names the registrar"
expect_contains "$ABUSE_EMAIL_TEXT" "  e2e-camp-a.com" "it lists the domains"
expect_contains "$ABUSE_EMAIL_TEXT" "Please suspend these domains under your abuse policy." "and asks for the suspension"
expect_eq "$(psql_value "select count(*) from reported_domain;")" 0 "preparing reports marks nothing as reported"
admin_post "$ABUSE_URL" "{\"urlIds\":[$(psql_value "select string_agg(id::text, ',' order by id) from url where long_url like '%e2e-camp-%';")]}"
expect_eq "$HTTP_STATUS $(jq -c '[.reports[] | [.to, (.domains | length)]]' <<<"$HTTP_BODY")" '200 [["abuse@e2e-registrar.example",2],["abuse@e2e-other.example",1]]' \
    "the same links given by id give the same cards"
admin_post "$ABUSE_URL" '{}'
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '400 {"urlIds":"Select between 1 and 100 links"}' "neither urlIds nor campaignId is a 400"
admin_post "$ABUSE_URL" '{"campaignId":999999}'
expect_eq "$HTTP_STATUS $(jq -r .error <<<"$HTTP_BODY")" "404 There is no campaign 999999." "an unknown campaign is a 404"
expect_eq "$(grep -c 'RDAP' "$WORK/app.log" || true)" "$RDAP_LOG_LINES" "the app logged no RDAP lookup or failure for the report"

echo "  --- marking domains as reported"
admin_post "$ABUSE_URL/reported" '{"domains":["e2e-camp-a.com","E2E-Camp-B.com"],"registrar":"E2E Registrar, LLC","abuseEmail":"abuse@e2e-registrar.example"}'
expect_eq "$HTTP_STATUS" 204 "POST /api/v1/admin/abuse-reports/reported for two domains"
psql_query "select domain, registrar, abuse_email, reported_by, reported_at, last_checked_at, suspended_at from reported_domain order by domain;"
expect_eq "$(psql_value "select string_agg(domain || ' ' || registrar || ' ' || abuse_email || ' ' || reported_by, ', ' order by domain) from reported_domain;")" \
    "e2e-camp-a.com E2E Registrar, LLC abuse@e2e-registrar.example admin@localhost, e2e-camp-b.com E2E Registrar, LLC abuse@e2e-registrar.example admin@localhost" \
    "two reported_domain rows, lower-cased, by the admin"
expect_eq "$(psql_value "select action || '|' || target_type || '|' || target_identifier || '|' || details || '|' || performed_by from audit_log where action = 'REPORT_DOMAINS';")" \
    "REPORT_DOMAINS|Registrar|abuse@e2e-registrar.example|domains: 2, registrar: E2E Registrar, LLC|admin@localhost" "and one REPORT_DOMAINS audit row"
FIRST_REPORTED_AT=$(psql_value "select reported_at from reported_domain where domain = 'e2e-camp-a.com';")
sleep 2
admin_post "$ABUSE_URL/reported" '{"domains":["e2e-camp-a.com","e2e-camp-c.com"]}'
expect_eq "$HTTP_STATUS" 204 "reporting e2e-camp-a.com again, with e2e-camp-c.com and no registrar"
expect_eq "$(psql_value "select count(*) from reported_domain;")" 3 "the upsert made one new row"
expect_eq "$(psql_value "select (reported_at = '$FIRST_REPORTED_AT') || ' ' || registrar || ' ' || abuse_email from reported_domain where domain = 'e2e-camp-a.com';")" \
    "true E2E Registrar, LLC abuse@e2e-registrar.example" "the first report's time and the known contact stay: a missing registrar wipes nothing"
expect_eq "$(psql_value "select coalesce(registrar, 'none') || ' ' || coalesce(abuse_email, 'none') from reported_domain where domain = 'e2e-camp-c.com';")" "none none" "the new domain has no contact"
expect_eq "$(psql_value "select count(*) from audit_log where action = 'REPORT_DOMAINS' and details = 'domains: 2, registrar: unknown';")" 1 "the second call has its own audit row, registrar unknown"
admin_post "$ABUSE_URL/reported" '{"domains":[]}'
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '400 {"domains":"Select between 1 and 100 domains"}' "an empty list is a 400"
psql_value "update reported_domain set suspended_at = now() where domain = 'e2e-camp-b.com';" >/dev/null
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID"
expect_eq "$(jq -c '.campaign.reports' <<<"$HTTP_BODY")" '{"reported":3,"suspended":1}' "the campaign counts three reported domains, one of them suspended"
expect_eq "$(jq -c '[.domains[] | [.domain, (.report.reportedBy // "none"), (.report.registrar // "none"), (.report.suspendedAt != null)]]' <<<"$HTTP_BODY")" \
    '[["e2e-camp-a.com","admin@localhost","E2E Registrar, LLC",false],["e2e-camp-b.com","admin@localhost","E2E Registrar, LLC",true],["e2e-camp-c.com","admin@localhost","none",false],["e2e-camp-d.com","none","none",false]]' \
    "and the detail shows each domain's report"
admin_post "$ABUSE_URL" "{\"campaignId\":$CAMPAIGN_ID}"
expect_eq "$(jq -c '[.reports[].domains[] | [.domain, .report.reportedBy, (.report.suspendedAt != null)]]' <<<"$HTTP_BODY")" \
    '[["e2e-camp-a.com","admin@localhost",false],["e2e-camp-b.com","admin@localhost",true],["e2e-camp-c.com","admin@localhost",false]]' \
    "a new report shows who reported each domain and when it was suspended"

echo "  --- allow this shape"
admin_post "$CAMPAIGNS_URL/$CAMPAIGN_ID/allow"
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '200 {"linksAffected":4}' "POST /api/v1/admin/campaigns/$CAMPAIGN_ID/allow restores the four held links"
expect_eq "$(psql_value "select count(*) filter (where active and block_reason is null and blocked_at is null) || ' of ' || count(*) from url where long_url like '%e2e-camp-%';")" "4 of 4" "Postgres has the four links active again"
expect_eq "$(psql_value "select status || ' ' || allowed_by || ' ' || (allowed_at is not null) from campaign where id = $CAMPAIGN_ID;")" "ALLOWED admin@localhost true" \
    "the campaign is ALLOWED, by the admin"
expect_eq "$(psql_value "select target_type || ' ' || target_identifier || ' ' || details || ' ' || performed_by from audit_log where action = 'ALLOW_CAMPAIGN_SHAPE';")" \
    "Campaign $CAMPAIGN_ID shape: /e2ecamp?r&t, links: 4 admin@localhost" "and one ALLOW_CAMPAIGN_SHAPE audit row"
admin_post "$CAMPAIGNS_URL/$CAMPAIGN_ID/allow"
expect_eq "$HTTP_STATUS $(jq -c . <<<"$HTTP_BODY")" '200 {"linksAffected":0}' "allowing again restores nothing"
create_shape_link 203.0.113.41 'https://a1.e2e-camp-e.com/e2ecamp?r=1&t=1'
expect_eq "$(jq -r '[.active, .status] | map(tostring) | join(",")' <<<"$HTTP_BODY")" "true,ACTIVE" "a new link with the allowed shape is answered active"
ALLOWED_ALIAS=${SHAPE_ALIASES[$(( ${#SHAPE_ALIASES[@]} - 1 ))]}
campaign_link_added() {
    [[ $(psql_value "select count(*) from campaign_link where campaign_id = $CAMPAIGN_ID;") == 5 ]]
}
wait_until 15 "the new link added to the allowed campaign" campaign_link_added
expect_eq "$(psql_value "select active || ' ' || coalesce(block_reason, 'none') from url where short_url = '$ALLOWED_ALIAS';")" "true none" "Postgres has it active and not blocked"
admin_get "$CAMPAIGNS_URL/$CAMPAIGN_ID"
expect_eq "$(jq -c '.campaign | [.status, .holding, .links]' <<<"$HTTP_BODY")" '["ALLOWED",false,{"total":5,"active":5,"held":0,"blocked":0}]' "the campaign counts five active links and holds none"

section "Done: every step passed"
