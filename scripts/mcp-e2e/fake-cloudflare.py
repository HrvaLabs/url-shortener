#!/usr/bin/env python3
"""A fake Cloudflare API for custom hostnames, for the e2e run and local stacks.

Python 3 standard library only. State lives in memory and is lost on exit.

    python3 scripts/mcp-e2e/fake-cloudflare.py --port 18099 [--host 127.0.0.1] [--verbose]

Point the backend at it with
    --app.cloudflare.api-base=http://127.0.0.1:18099/client/v4
    --app.cloudflare.api-token=local-fake-token --app.cloudflare.zone-id=local-zone

API (the subset of https://api.cloudflare.com/client/v4 the backend uses):
    GET    /client/v4/user/tokens/verify
    GET    /client/v4/zones/{zone}/custom_hostnames?hostname=&page=&per_page=
    POST   /client/v4/zones/{zone}/custom_hostnames      {"hostname", "ssl": {"method", "type"}}
    GET    /client/v4/zones/{zone}/custom_hostnames/quota
    GET    /client/v4/zones/{zone}/custom_hostnames/{id}
    DELETE /client/v4/zones/{zone}/custom_hostnames/{id}

Error answers use the codes Cloudflare documents for custom hostnames: 409/1406 duplicate,
404/1436 not found, 400/1432 bad validation method, 400/1433 bad validation type,
403/1413 custom_metadata (not available on the Free plan). Any bearer token is accepted,
and so is none; refusals come only from the token mode below.

Control endpoints (never recorded in the call log):
    POST /_control/activate?hostname=                   status and ssl status become active, errors cleared
    POST /_control/state?hostname=&status=&ssl=&error=  set any of them; error (may be empty) replaces
                                                        verification_errors and ssl.validation_errors
    POST /_control/token?mode=ok|account|invalid|forbidden|down
         ok        verify says active, everything works
         account   verify answers 401 (an account-owned token), everything else works
         invalid   verify answers 401, zone calls answer 403 code 10000
         forbidden verify says active, zone calls answer 403 code 10000
         down      every API call answers 503
    POST /_control/reset[?scope=calls]                  without scope: hostnames, calls and the token mode
                                                        (back to ok); scope=calls clears only the call log
    GET  /_control/calls                                {"calls": [{"method", "path", "query", "status"}]}
                                                        for every API call, in order; path has no query
    GET  /_control/hostnames                            {"hostnames": [...]} every stored record, all zones
"""

import argparse
import json
import re
import sys
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

API_PREFIX = "/client/v4"
HOSTNAMES_PATH = re.compile(r"^/client/v4/zones/([^/]+)/custom_hostnames(?:/([^/]+))?$")
TOKEN_MODES = ("ok", "account", "invalid", "forbidden", "down")
DCV_METHODS = ("http", "txt", "email")
MAX_PER_PAGE = 1000
DEFAULT_PER_PAGE = 20
MIN_PER_PAGE = 5
NOT_CNAMED = "custom hostname does not CNAME to this zone."

lock = threading.Lock()
state = {"zones": {}, "calls": [], "token": "ok"}


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def envelope(result, result_info=None):
    body = {"success": True, "errors": [], "messages": [], "result": result}
    if result_info is not None:
        body["result_info"] = result_info
    return body


def failure(code, message):
    return {"success": False, "errors": [{"code": code, "message": message}], "messages": [], "result": None}


def new_record(hostname, method):
    record_id = str(uuid.uuid4())
    return {
        "id": record_id,
        "hostname": hostname,
        "status": "pending",
        "ssl": {
            "id": str(uuid.uuid4()),
            "type": "dv",
            "method": method,
            "status": "pending_validation",
            "settings": {"min_tls_version": "1.2"},
            "bundle_method": "ubiquitous",
            "wildcard": False,
            "certificate_authority": "google",
            "validation_errors": [],
        },
        "verification_errors": [NOT_CNAMED],
        "ownership_verification": {
            "type": "txt",
            "name": "_cf-custom-hostname." + hostname,
            "value": str(uuid.uuid4()),
        },
        "ownership_verification_http": {
            "http_url": "http://" + hostname + "/.well-known/cf-custom-hostname-challenge/" + record_id,
            "http_body": str(uuid.uuid4()),
        },
        "created_at": now(),
    }


def find_by_hostname(hostname):
    for records in state["zones"].values():
        for record in records.values():
            if record["hostname"] == hostname:
                return record
    return None


def int_param(query, name, default):
    try:
        return int(query.get(name, [default])[0])
    except (TypeError, ValueError):
        return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "fake-cloudflare/1.0"
    verbose = False

    def log_message(self, format, *args):
        if self.verbose:
            sys.stderr.write("fake-cloudflare: %s\n" % (format % args))

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")

    def do_PUT(self):
        self.route("PUT")

    def do_PATCH(self):
        self.route("PATCH")

    def route(self, method):
        parts = urlsplit(self.path)
        query = parse_qs(parts.query, keep_blank_values=True)
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length) if length > 0 else b""
        if parts.path.startswith("/_control/"):
            with lock:
                status, body = self.control(method, parts.path, query)
            self.reply(status, body)
            return
        with lock:
            status, body = self.api(method, parts.path, query, raw_body)
            state["calls"].append({"method": method, "path": parts.path, "query": parts.query, "status": status})
        self.reply(status, body)

    def control(self, method, path, query):
        if method == "GET" and path == "/_control/calls":
            return 200, {"calls": list(state["calls"])}
        if method == "GET" and path == "/_control/hostnames":
            return 200, {"hostnames": [record for records in state["zones"].values() for record in records.values()]}
        if method != "POST":
            return 405, {"error": "use POST"}
        if path == "/_control/reset":
            if query.get("scope", ["all"])[0] == "calls":
                state["calls"].clear()
            else:
                state["zones"].clear()
                state["calls"].clear()
                state["token"] = "ok"
            return 200, {"ok": True}
        if path == "/_control/token":
            mode = query.get("mode", [""])[0]
            if mode not in TOKEN_MODES:
                return 400, {"error": "mode must be one of " + ", ".join(TOKEN_MODES)}
            state["token"] = mode
            return 200, {"ok": True, "mode": mode}
        if path in ("/_control/activate", "/_control/state"):
            hostname = query.get("hostname", [""])[0].strip().lower()
            record = find_by_hostname(hostname)
            if record is None:
                return 404, {"error": "no such hostname: " + hostname}
            if path == "/_control/activate":
                record["status"] = "active"
                record["ssl"]["status"] = "active"
                record["verification_errors"] = []
                record["ssl"]["validation_errors"] = []
            else:
                if "status" in query:
                    record["status"] = query["status"][0]
                if "ssl" in query:
                    record["ssl"]["status"] = query["ssl"][0]
                if "error" in query:
                    error = query["error"][0]
                    record["verification_errors"] = [error] if error else []
                    record["ssl"]["validation_errors"] = [{"message": error}] if error else []
            return 200, envelope(record)
        return 404, {"error": "unknown control endpoint"}

    def api(self, method, path, query, raw_body):
        mode = state["token"]
        if mode == "down":
            return 503, failure(1500, "Internal Server Error")
        if method == "GET" and path == API_PREFIX + "/user/tokens/verify":
            if mode in ("account", "invalid"):
                return 401, failure(1000, "Invalid API Token")
            return 200, envelope({"id": "fake-token-id", "status": "active"})
        match = HOSTNAMES_PATH.match(path)
        if match is None:
            return 400, failure(7003, "Could not route to " + path + ", perhaps your object identifier is invalid?")
        if mode in ("invalid", "forbidden"):
            return 403, failure(10000, "Authentication error")
        records = state["zones"].setdefault(match.group(1), {})
        item = match.group(2)
        if item is None:
            if method == "GET":
                return self.list_hostnames(records, query)
            if method == "POST":
                return self.create(records, raw_body)
            return 405, failure(10405, "Method not allowed")
        if item == "quota" and method == "GET":
            return 200, envelope({"allocated": 50000, "used": len(records), "exceeded": False, "hard_cap": 100000})
        record = records.get(item)
        if method == "GET":
            return (200, envelope(record)) if record else (404, failure(1436, "The custom hostname was not found."))
        if method == "DELETE":
            if record is None:
                return 404, failure(1436, "The custom hostname was not found.")
            del records[item]
            return 200, envelope({"id": item})
        return 405, failure(10405, "Method not allowed")

    def list_hostnames(self, records, query):
        page = int_param(query, "page", 1)
        per_page = int_param(query, "per_page", DEFAULT_PER_PAGE)
        if page is None or per_page is None or page < 1:
            return 400, failure(1001, "Invalid page or per_page")
        per_page = max(MIN_PER_PAGE, min(per_page, MAX_PER_PAGE))
        found = list(records.values())
        if "hostname" in query:
            wanted = query["hostname"][0].strip().lower()
            found = [record for record in found if record["hostname"] == wanted]
        start = (page - 1) * per_page
        chunk = found[start:start + per_page]
        total_pages = (len(found) + per_page - 1) // per_page
        info = {"page": page, "per_page": per_page, "count": len(chunk), "total_count": len(found), "total_pages": total_pages}
        return 200, envelope(chunk, info)

    def create(self, records, raw_body):
        try:
            body = json.loads(raw_body.decode("utf-8") or "{}")
        except (UnicodeDecodeError, ValueError):
            return 400, failure(1000, "Malformed JSON in request body")
        if not isinstance(body, dict):
            return 400, failure(1000, "Malformed JSON in request body")
        if "custom_metadata" in body:
            return 403, failure(1413, "No custom metadata access has been allocated for this zone.")
        hostname = str(body.get("hostname") or "").strip().lower()
        if not hostname or "." not in hostname:
            return 400, failure(1409, "Invalid custom hostname.")
        ssl = body.get("ssl") or {}
        if not isinstance(ssl, dict):
            return 400, failure(1434, "The SSL attribute is invalid.")
        if ssl.get("method") not in DCV_METHODS:
            return 400, failure(1432, "The validation method is not supported. Only http, email, or txt are accepted.")
        if ssl.get("type") != "dv":
            return 400, failure(1433, "The validation type is not supported. Only 'dv' is accepted.")
        if find_by_hostname(hostname) is not None:
            return 409, failure(1406, "Duplicate custom hostname found.")
        record = new_record(hostname, ssl["method"])
        records[record["id"]] = record
        return 201, envelope(record)

    def reply(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    parser = argparse.ArgumentParser(description="A fake Cloudflare custom hostnames API.")
    parser.add_argument("--port", type=int, default=18099)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--verbose", action="store_true", help="log every request line (never headers)")
    arguments = parser.parse_args()
    Handler.verbose = arguments.verbose
    server = ThreadingHTTPServer((arguments.host, arguments.port), Handler)
    server.daemon_threads = True
    sys.stderr.write("fake-cloudflare listening on http://%s:%d%s\n" % (arguments.host, arguments.port, API_PREFIX))
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
