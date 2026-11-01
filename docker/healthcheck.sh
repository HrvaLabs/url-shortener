#!/bin/bash
# Asks the app on this container's own port for /actuator/health/liveness. The image has no curl or wget, so this uses bash's /dev/tcp.
# Any host other than a primary host answers 404, so the request names one: HEALTHCHECK_HOST, or the first APP_DOMAINS_PRIMARY_HOSTS entry, or hrva.cc.
host="${HEALTHCHECK_HOST:-${APP_DOMAINS_PRIMARY_HOSTS%%,*}}"
host="${host:-hrva.cc}"
exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
printf 'GET /actuator/health/liveness HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "$host" >&3
read -r -t 5 status <&3
[[ "$status" == *" 200 "* ]]
