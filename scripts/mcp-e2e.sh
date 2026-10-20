#!/usr/bin/env bash
# End-to-end check of the MCP connector on a throwaway stack: Postgres 18 and Redis 8 in disposable
# containers on random loopback ports, and the app with the local profile on port 8091 (issuer
# http://localhost:8091). The local dev stack (docker/docker-compose.yml, its db volume, ports 5432,
# 6379 and 8080) is never touched. Everything is removed on exit.
#
# Needs Docker, Java 25, Maven, Node (npx), jq, curl and openssl. The MCP Inspector's own sign-in uses
# the macOS `open` shim in scripts/mcp-e2e/fake-browser, so that step runs on macOS only.
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

for tool in docker java mvn npx jq curl openssl; do
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
expect_eq "$(jq -r '[.tools[].name] | sort | join(",")' <<<"$INSPECTOR_RESULT")" "link_stats,list_links,shorten_url" "the three signed-in tools"
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

section "9. link_stats (Inspector CLI)"
show "visiting $BASE/$ALIAS once -> $(curl -s -o /dev/null -w '%{http_code}' "$BASE/$ALIAS")"
inspector 60 "$RESOURCE" --transport http --stored-auth-only --method tools/call --tool-name link_stats --tool-arg "link=$BASE/$ALIAS"
show_tool_result
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "link_stats succeeded"
expect_contains "$(tool_text)" "Visitor details are kept for about 48 hours" "states the 48-hour window"

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

section "13. /mcp/anon shorten_url without a token (Inspector CLI)"
inspector 60 "$BASE/mcp/anon" --transport http --method tools/list
expect_eq "$(jq -r '[.tools[].name] | join(",")' <<<"$INSPECTOR_RESULT")" shorten_url "tools/list has only shorten_url"
ANONYMOUS_DESTINATION="https://example.com/hrva-mcp-e2e-anon/$(date +%s)"
inspector 60 "$BASE/mcp/anon" --transport http --method tools/call --tool-name shorten_url --tool-arg "url=$ANONYMOUS_DESTINATION"
show_tool_result
expect_eq "$(jq -r .isError <<<"$INSPECTOR_RESULT")" false "anonymous shorten_url succeeded"
expect_contains "$(tool_text)" "Short link: $BASE/" "returns the full short link"
echo "  --- links in the database"
psql_query "select u.short_url, u.owner_id is null as anonymous, string_agg(t.tag, ',' order by t.tag) as tags from url u left join url_tags t on t.url_id = u.id group by u.id order by u.id;"

section "Done: every step passed"
