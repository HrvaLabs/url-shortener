# shellcheck shell=bash
# Helpers shared by scripts/mcp-e2e.sh and its fake browser. Source it; it only defines functions.
# Temporary files go to $MCP_E2E_WORK, which the main script creates and removes.
# The HTTP_* and MCP_ACCEPT globals are read by the scripts that source this file.
# shellcheck disable=SC2034

MCP_ACCEPT='application/json, text/event-stream'

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

b64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

urlencode() {
    jq -rn --arg value "$1" '$value | @uri'
}

# jwt_part <JWT> <1 for the header, 2 for the claims> prints that part as JSON.
jwt_part() {
    local part
    part=$(printf '%s' "$1" | cut -d. -f"$2" | tr '_-' '/+')
    while (( ${#part} % 4 != 0 )); do
        part+='='
    done
    printf '%s' "$part" | openssl base64 -A -d
}

jwt_header() {
    jwt_part "$1" 1
}

jwt_claims() {
    jwt_part "$1" 2
}

# JWTs become eyJ…; one-time codes, connect handles and authorization codes keep their first 6 characters.
redact() {
    sed -E \
        -e 's/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/eyJ…/g' \
        -e 's/([?&](code|otc|request)=)([A-Za-z0-9_-]{6})[A-Za-z0-9_%-]*/\1\3…/g' \
        -e 's#(/connect/requests/)([A-Za-z0-9_-]{6})[A-Za-z0-9_-]*#\1\2…#g'
}

# http <curl arguments…> sets HTTP_STATUS, HTTP_HEADERS, HTTP_BODY and HTTP_LOCATION.
http() {
    local headers body
    headers=$(mktemp "$MCP_E2E_WORK/headers.XXXXXX")
    body=$(mktemp "$MCP_E2E_WORK/body.XXXXXX")
    HTTP_STATUS=$(curl -sS -o "$body" -D "$headers" -w '%{http_code}' "$@")
    HTTP_HEADERS=$(tr -d '\r' <"$headers")
    HTTP_BODY=$(cat "$body")
    HTTP_LOCATION=$(header_value location)
    rm -f "$headers" "$body"
}

header_value() {
    awk -v name="$1" 'tolower($1) == tolower(name) ":" { sub(/^[^:]*:[ ]*/, ""); print; exit }' <<<"$HTTP_HEADERS"
}
