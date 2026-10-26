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
DB_USER=e2e
DB_PASSWORD=$(openssl rand -hex 16)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/hrva-mcp-e2e.XXXXXX")
APP_PID=
INSPECTOR_PID=
STUB_PID=
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
    docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    if (( status != 0 )) && [[ -s $WORK/app.log ]]; then
        echo "--- last 60 lines of the app log"
        tail -n 60 "$WORK/app.log" | redact
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

for tool in docker java jar mvn node npx jq curl openssl; do
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
POSTGRES_PORT=$(host_port "$POSTGRES_CONTAINER" 5432)
REDIS_PORT=$(host_port "$REDIS_CONTAINER" 6379)
wait_until 60 "Postgres" docker exec "$POSTGRES_CONTAINER" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d url_shortener
wait_until 30 "Redis" docker exec "$REDIS_CONTAINER" redis-cli ping
echo "  containers $RUN_ID-{postgres,redis}: Postgres 18 on 127.0.0.1:$POSTGRES_PORT, Redis 8 on 127.0.0.1:$REDIS_PORT"

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
    --app.discord-webhook-url="$DISCORD_WEBHOOK" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/url_shortener" \
    --spring.datasource.username="$DB_USER" \
    --spring.datasource.password="$DB_PASSWORD" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port="$REDIS_PORT" \
    >"$WORK/app.log" 2>&1 &
APP_PID=$!
for (( second = 0; second < 180; second++ )); do
    kill -0 "$APP_PID" 2>/dev/null || fail "the app exited during startup"
    [[ $(curl -s -o /dev/null -w '%{http_code}' "$BASE/.well-known/oauth-authorization-server") == 200 ]] && break
    sleep 1
done
(( second < 180 )) || fail "the app did not start within 180s"
echo "  app $(basename "$JAR") on $BASE (profile local, issuer $BASE)"
expect_eq "$(psql_value "select string_agg(id || ' ' || exectype, ', ' order by orderexecuted) from databasechangelog where id like '026-drop-ip-address%';")" \
    "026-drop-ip-address EXECUTED, 026-drop-ip-address-seq EXECUTED" "026 ran both ip_address drops"
expect_eq "$(psql_value "select (select count(*) from information_schema.tables where table_name = 'ip_address') + (select count(*) from information_schema.sequences where sequence_name = 'ip_address_id_seq');")" \
    0 "no ip_address table or sequence is left"
expect_eq "$(psql_value "select count(*) from databasechangelog where id like '027-%' and exectype = 'EXECUTED';")" 9 "027 ran all nine changesets"
expect_eq "$(psql_value "select count(*) from pg_indexes where indexname = 'ux_url_short_url';")" 1 "short_url has its unique index"

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

http -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/reserved-aliases"
expect_eq "$HTTP_STATUS" 200 "GET /api/v1/admin/reserved-aliases"
expect_eq "$(jq -r 'any(.[]; .alias == "mcp" and .builtIn == true)' <<<"$HTTP_BODY")" true "mcp is a built-in reserved alias"
expect_eq "$(jq -r 'any(.[]; .alias == "anon" and .builtIn == true)' <<<"$HTTP_BODY")" true "anon is a built-in reserved alias"
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
expect_eq "$QR_TEXT" "localhost/$ALIAS?qr" "the code is the short link (app.server-url of the local profile) with the ?qr marker"

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

section "Done: every step passed"
