# URL-Shortener Agent Guide

## Build & Run
- **Java 25**, **Spring Boot 4.1.1**, **Maven**. No Gradle artifacts.
- `.mvn/wrapper/` exists but `mvnw` is missing — use system `mvn`.
- Entry point: `cc.hrva.urlshortener.UrlShortenerApplication`
- Local dev:
  ```bash
  # Start backing services first
  docker compose -f docker/docker-compose.yml up
  # Then run the app with local profile
  mvn spring-boot:run -Dspring-boot.run.profiles=local
  ```
- MCP end-to-end check on its own throwaway containers: `scripts/mcp-e2e.sh` (see MCP connector)
- Build: `mvn clean package` → output in `target/`
- Docker: `Dockerfile` copies `target/*.jar`, multi-arch CI build; runs with `-XX:MaxRAMPercentage=75` (the JVM default is 25 % of the container limit), as a blocklist refresh holds up to millions of entries in memory

## Local Dev Users
`LocalDataInitializer` (only with `local` profile) bootstraps two email-verified local accounts:

| Email | Password | Role | Slots |
|-------|----------|------|-------|
| `admin@localhost` | `admin123` | ROLE_ADMIN | 10 |
| `user@localhost` | `user123` | ROLE_USER | 4 |

Idempotent — safe on restart. Liquibase seeds `ROLE_USER` and `ROLE_ADMIN` authorities.

## Local Dependencies
- **Postgres** (`localhost:5432`, `root:root`, db `url_shortener`)
- **Redis** (`localhost:6379` — overridden in `application-local.yaml`, no longer needed to set manually), 6.2 or newer
- **MailHog** (`localhost:8025`, SMTP on `localhost:1025`)
- The app downloads the enabled blocklist sources into Redis at startup (each source's `memoryBytes` shows the cost) and needs outbound https for them, RDAP and the redirect checks.

## End-to-end check of the link checks
No test boots the whole app, so check it by hand on a throwaway Postgres 18 and Redis 8 without Docker (Homebrew binaries; any free ports, kept off 5432, 6379 and 8080):
```bash
E2E=$(mktemp -d)
/opt/homebrew/opt/postgresql@18/bin/initdb -D "$E2E/pg" -U e2e --auth=trust
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D "$E2E/pg" -l "$E2E/pg.log" -o "-p 55432 -c listen_addresses=127.0.0.1 -c unix_socket_directories=''" start
/opt/homebrew/opt/postgresql@18/bin/createdb -h 127.0.0.1 -p 55432 -U e2e url_shortener
redis-server --port 56379 --bind 127.0.0.1 --save '' --appendonly no --daemonize yes --logfile "$E2E/redis.log"
mvn -q -DskipTests package && cp target/url-shortener-*.jar "$E2E/app.jar"
java -jar "$E2E/app.jar" --spring.profiles.active=local --server.port=18080 \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/url_shortener --spring.datasource.username=e2e --spring.datasource.password= \
  --spring.data.redis.port=56379 --app.mcp.issuer=http://localhost:18080 \
  --spring.security.oauth2.client.registration.google.client-id=dummy --spring.security.oauth2.client.registration.google.client-secret=dummy
```
- Sign in as `admin@localhost` / `admin123` (`POST /api/v1/auth/login`) for the admin endpoints; `GET /api/v1/admin/blocklist-sources` shows each source's download.
- Send `CF-Connecting-IP: <ip>` to act as different visitors: every anonymous create counts toward that network's 10 per hour, refusals included.
- Run the jar from a copy, so `mvn clean` doesn't pull it from under the running app.

## Testing
- `mvn test` (or `mvn -B verify`) — 1708 tests, JUnit 5 + Mockito + AssertJ
- Mostly plain `@ExtendWith(MockitoExtension.class)` unit tests. Each `@SpringBootTest` class boots its own nested `TestApplication` (a trimmed context) on embedded H2 and mocks the rest:
  - MockMvc through the real filter chains, with a subset Liquibase changelog (`db/changelog/017-oauth2-authorization-server.xml` or `db/mcp-connector-test-changelog.xml`): the MCP connector's security tests, `SecurityTxtControllerTest`, `ReportEndpointSecurityTest`, `AdminReviewQueueSecurityTest`
  - the full Liquibase changelog: `DynamicLinkChecksSchemaTest` (024's tables, seeds and constraints), `DefaultScammerSignalServiceTest`
  - 008, legacy audit rows, then 025 (`db/audit-action-test-changelog.xml`): `AuditActionMigrationTest`
  - a Hibernate-generated schema, no Liquibase: `UrlSpecificationTest`, `LinkReviewSpecificationTest`, `AdminPageQueriesTest` (statement counts of the admin pages)
- `UrlShortenerApplicationTests` has no test methods, so no test boots the whole app. The real app on Postgres and Redis is checked by hand (see End-to-end check of the link checks).
- No `@ActiveProfiles`, no Testcontainers
- `src/test/` mirrors `src/main/` package structure

## Package Structure
```
cc.hrva.urlshortener
├── audit/           # AdminAuditAspect, @Audited
├── client/          # GoogleSafeBrowsingApi, PublicHttpFetcher (public-address guard), RedirectFollower, DiscordWebhookClient
├── configuration/   # Beans, props, cache, security, web config, exception handler, executors, Redis client
├── controller/      # REST controllers
├── converter/       # DTO↔entity converters (Spring Converter interface)
├── dto/             # Request/response DTOs (records and classes)
├── exception/       # Standard-named exceptions (*NotFoundException, Invalid*Exception)
├── listener/        # Spring event listeners (auth success/failure)
├── mcp/             # MCP connector: authorization/, bridge/, tools/, anon/, apps/
├── model/           # JPA entities, codebook, enums
├── repository/      # Spring Data JPA repositories
├── security/        # JWT filter, token provider, auth entry point, ClientIpResolver
├── service/         # Interfaces + impl/ implementations; blocklist/ (download, parse, Redis sets); alert/ (Discord alert events and lines)
├── util/            # DomainNames, LinkKeys, PublicAddresses, RedirectLocations
└── validator/       # Interfaces + impl/ validators
```
**No `beans/` package** — contents split into `security/`, `client/`, `configuration/`.

## API Patterns
- **Plural resource names**: `/api/v1/urls`, `/api/v1/api-keys`, `/api/v1/users`, `/api/v1/auth`
- **Proper HTTP methods**: `POST` for create, `DELETE` for delete, `PATCH` for state changes, `GET` for reads
- **API keys in header**: `X-Api-Key` header (NOT in URL path)
- **Pagination**: `@PageableDefault(size=20)` on all list endpoints — pass `?page=0&size=20` query params
- **Response DTOs**: Controllers return `UrlResponse`, `ApiKeyResponse`, `UserDto` — NOT JPA entities directly
- **Error format**: `{"error": "message"}` via `GlobalExceptionHandler` (`@ControllerAdvice`)
- **Exception → HTTP status mapping**: `UserNotFoundException`/`ApiKeyNotFoundException`/`BlocklistSourceNotFoundException`/`DomainRuleNotFoundException` → 404, `EmailExistsException`/`ShortUrlAlreadyExistsException` → 409, `NoAuthorizationException` → 403, `BadCredentialsException` → 401, `RateLimitExceededException` → 429, a malformed body, a mistyped or missing parameter → 400 (an unknown enum value in a body names the allowed ones: `{"error": "Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"}`; enums are read by name only, never by number), an unsupported media type → 415, `CommonException` (catch-all for all custom exceptions not handled above) → 400, everything else → 500.
- **Validation errors**: Return `{"field": "message"}` map on 400.

## Key Endpoints
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/v1/urls` | Optional `X-Api-Key` | Create short URL |
| GET | `/api/v1/urls/{short}` | None | Redirect info (records visit) |
| GET | `/api/v1/urls/{short}/peek` | None | Quick peek (longUrl, shortUrl, createDate) |
| GET | `/api/v1/urls/{short}/preview` | None | Rich preview (title, description, og:image) |
| GET | `/api/v1/urls/{short}/qr` | None | QR code PNG (`?size=300`) |
| POST | `/api/v1/urls/bulk` | ROLE_USER | Bulk create (JSON array) |
| PUT | `/api/v1/urls/{id}` | Required | Update URL |
| PATCH | `/api/v1/urls/{id}/deactivate` | Required | Deactivate |
| DELETE | `/api/v1/urls/{id}` | Required | Delete |
| POST | `/api/v1/api-keys` | ROLE_USER | Generate key |
| PATCH | `/api/v1/api-keys/{id}/revoke` | Required | Revoke key |
| POST | `/api/v1/auth/register` | None | Register |
| POST | `/api/v1/auth/login` | None | Login (returns JWT) |
| POST | `/api/v1/auth/password-reset` | None | Request reset |
| POST | `/api/v1/auth/password-reset/confirm` | None | Confirm reset |
| POST | `/api/v1/auth/verify-email/confirm` | None | Confirm email with token |
| POST | `/api/v1/auth/verify-email/resend` | None | Resend verification email (always 202) |
| POST | `/api/v1/auth/2fa/setup` | Required | Start TOTP enrollment (local accounts) |
| POST | `/api/v1/auth/2fa/enable` | Required | Confirm code, enable 2FA, get recovery codes |
| POST | `/api/v1/auth/2fa/disable` | Required | Disable 2FA (TOTP or recovery code) |
| GET | `/api/v1/users/me` | Required | Current user |
| GET | `/api/v1/users/me/export` | Required | GDPR data export (JSON) |
| DELETE | `/api/v1/users/me` | Required | Self-delete (password-confirmed for local) |
| GET | `/api/v1/urls/tags` | ROLE_USER | Distinct tags for current user |
| GET | `/api/v1/analytics/overview` | Required | Aggregate URL analytics for current user |
| GET | `/api/v1/analytics/urls/{id}` | Required | Per-URL analytics (owner/admin) |
| GET | `/api/v1/admin/stats` | ROLE_ADMIN | Dashboard stats (users, URLs, cache, etc.) |
| GET | `/api/v1/admin/audit-log` | ROLE_ADMIN | Paginated admin audit trail, newest first; each entry has `action` (an `AuditAction` name) and `failed`. `?action=` keeps one action (an unknown name → 400) |
| GET | `/api/v1/admin/email-log` | ROLE_ADMIN | Paginated email delivery log |
| GET | `/api/v1/admin/login-attempts` | ROLE_ADMIN | IP → attempt count map |
| DELETE | `/api/v1/admin/login-attempts` | ROLE_ADMIN | Clear all rate limits |
| GET | `/api/v1/users` | ROLE_ADMIN | Users page; `?possibleScammer=true` keeps the accounts with the signal (filtered in SQL, so totals are right) |
| GET | `/api/v1/admin/blocklist-sources` | ROLE_ADMIN | Sources with `domainCount`, `listVersion`, `lastSuccessUrl`, `lastError` and `memoryBytes` (Redis `MEMORY USAGE`) |
| POST | `/api/v1/admin/blocklist-sources` | ROLE_ADMIN | Add `{name, url, kind, action, enabled, refreshIntervalHours, fallbackUrls}`; https and public addresses only, up to 3 fallbacks, interval 1–168 h |
| PUT / DELETE | `/api/v1/admin/blocklist-sources/{id}` | ROLE_ADMIN | Edit / remove; disabling or removing drops the Redis set |
| POST | `/api/v1/admin/blocklist-sources/{id}/refresh` | ROLE_ADMIN | Download now (synchronous), returns the source |
| GET | `/api/v1/admin/domain-rules` | ROLE_ADMIN | `?type=BLOCK` or `ALLOW` |
| POST | `/api/v1/admin/domain-rules` | ROLE_ADMIN | `{domain, type, reason}` → `{rule, linksAffected}`. A Block deactivates the matching links at once; an Allow resolves the domain's queue entries and restores its `BLOCKLIST`, `SCAM_SIGNALS` and `DOMAIN_RULE` blocks after a fresh check |
| DELETE | `/api/v1/admin/domain-rules/{id}` | ROLE_ADMIN | Remove a rule (links stay as they are) |
| GET | `/api/v1/admin/review-queue` | ROLE_ADMIN | Open entries, newest first; `?reason=` (substring) and `?olderThanDays=`. Each entry carries `host` and `registrableDomain` (null when the destination has no readable host), the defaults of Trust domain and Block domain |
| POST | `/api/v1/admin/review-queue/{urlId}/resolve` | ROLE_ADMIN | By link id. `{action: OK \| TRUST_DOMAIN \| BLOCK_LINK \| BLOCK_DOMAIN \| BLOCK_USER, domain?}` → 204. Block domain defaults to the registrable domain, Trust domain to the exact host. A missing action → 400 `{"action": …}`, an unknown one → 400 `{"error": …}` |
| POST | `/api/v1/admin/review-queue/resolve` | ROLE_ADMIN | Bulk: `{urlIds: [1–100 distinct ids], action}` → 200 `{resolved: [ids], failed: [{urlId, error}]}`. Each id goes through the single resolve with the default domain, in request order, each in its own transactions, and one failure never stops the rest: an id without an open entry fails with "This link is no longer in the review queue.", a refused one with the refusal (no owner, an admin account, a missing link), anything unexpected with "Resolving this link failed. Try again." (logged). A Block or Trust domain runs once per domain: a later link whose host that domain covers only has its entry resolved under it (`links: 0` in its audit entry). Admin resolves send no Discord alert, like single ones |
| POST | `/api/v1/admin/users/{id}/block`, `/unblock` | ROLE_ADMIN | Deactivate the account and block its links (`USER_BLOCKED`), or undo both → `{linksAffected}` |
| GET | `/api/v1/admin/possible-scammers` | ROLE_ADMIN | Ids of the accounts with the signal |
| POST | `/api/v1/admin/url-check` | ROLE_ADMIN | `{url}` → `{outcome, reasons, blockReason, blockDetail, host, registrableDomain}`, every matching rule, list and heuristic |
| POST | `/api/v1/reports` | Public, 5/h per network | Report a link `{shortUrl, category: PHISHING \| MALWARE \| SPAM \| OTHER, details (≤ 500)}`: opens or extends its review entry. Always 202 for a well-formed report, whether or not the link exists; never blocks by itself. Body capped at 64 KB |
| GET | `/{short}` | None | Redirect after the click check. A blocked, expired, over-limit, deactivated or missing link → 302 `{frontendUrl}/unavailable?s={short}&reason=blocked\|expired\|limit\|inactive\|missing` (the list is never named) |
| GET | `/api/changelog` | Public | Changelog JSON |
| GET | `/.well-known/security.txt` | Public | RFC 9116 abuse contact (`abuse@hrva.cc`), `text/plain; charset=utf-8`; `Expires` is 365 days from the request |
| GET | `/actuator/health` | Public | Health check |
| GET | `/actuator/**` | ROLE_ADMIN | Metrics, caches, env, beans |

MCP, OAuth 2.1, login-bridge and Connected-apps endpoints: see MCP connector.

## SSO (OAuth2)
- Google and GitHub OAuth2 login supported via `spring-boot-starter-oauth2-client`.
- **Login flow**: Frontend links to `/oauth2/authorization/google` (or `/github`). Backend handles callback, creates/finds user by email, issues JWT, redirects to `{frontendUrl}/auth/callback?token=...`.
- **OAuth2 users** get `authProvider = "google"` or `"github"` on the User record. Their email cannot be changed (blocked at service level).
- Configured via `application-oauth.yaml` (gitignored) or environment variables for production.
- OAuth2 client secrets for local dev go in `application-oauth.yaml` (excluded from git via `.gitignore`).

## Database
- **Liquibase** root: `src/main/resources/db/dbchangelog.xml`
- Migrations auto-run on startup. Codebook seeds ROLE_USER(id=1) and ROLE_ADMIN(id=2).
- User accounts have `AUTH_PROVIDER` column — `"local"` for email/password users, `"google"`/`"github"` for SSO.

## Performance
- **Redis caching**: `@Cacheable("urls")` on URL lookup (`peekUrlByShortUrl` only). 1-hour TTL. `@CacheEvict` on mutations.
- **Graceful degradation**: `CacheErrorHandler` logs and falls through to DB when Redis is down — no 500s.
- **Redis fails fast**: Lettuce rejects commands while its connection is down (`RedisClientConfiguration`: `REJECT_COMMANDS`, auto-reconnect kept), so a dropped Redis fails each call at once; `spring.data.redis.timeout: 3s` only bounds a hung Redis. Every Redis caller must therefore stay fail-soft, catching the failure and degrading on purpose (cache → database, click-time blocklist check → let the visitor through, `/unlock` rate limit → allow). Only the documented rate limits under MCP connector fail closed.
- **Read-only transactions**: `@Transactional(readOnly = true)` at class level on all services, overridden with `@Transactional` on write methods.
- **SafeBrowsing client**: Singleton bean (not built per-request). Graceful degradation on API error (returns `List.of()`).

## Security
- JWT in `Authorization: Bearer` header. Stateless sessions on the API chain; the MCP authorization server chain keeps a short HTTP session for the login bridge (see MCP connector).
- Login rate limiting: Guava cache, 20 attempts/day per IP, 1-day expiry. Wrong 2FA codes also count per account (`2fa:<userId>` key) and stop further checks once the limit is hit.
- Public lookups (`GET /urls/{short}`, `/peek`, `/preview`, `/qr`) never return the owner's email or tags; a password-protected link's destination is left out (no preview fetched, no visit recorded) until `/unlock`. `/qr` encodes the short link (`app.server-url` + alias), size clamped to 64–1024.
- `/unlock` is rate limited through `RedisFixedWindowRateLimiter`: 10 attempts per client network and 50 per link per 15 minutes (allowed if Redis is down).
- Link creation is rate limited in `DefaultUrlService` through `RedisFixedWindowRateLimiter`, per hour: 10 links per client network without an account (`create:anon`, IP from `ClientIpResolver` in `UrlController`), 60 per account with one (`create:user:<id>`, shared by the web, API keys and the signed-in MCP `shorten_url`). Admins are exempt and each link of `/urls/bulk` counts. Over the limit → 429 `"You've created a lot of links in a short time. Try again in an hour."` Allowed if Redis is down. Cheap refusals come first, so junk can't trigger the slow link check: an API key is checked (unknown, expired, revoked, over its limit) → the create rate limit → the anonymous-disabled refusal → the URL checks (`validateUrl`, up to a few seconds on a cold RDAP or Spamhaus cache) → the write transaction, where an API key's use is recorded. `/mcp/anon` passes no client IP, so only its own limit applies. `RateLimitExceededException` is a 429 everywhere, `/unlock` included.
- Link previews only fetch public addresses: loopback, private, link-local, CGNAT and ULA targets are refused, and each redirect hop (max 3) is re-checked.
- New passwords need at least 8 characters (register, reset, change). `User.password` is `@JsonIgnore`d.
- Direct `Cache` calls in `DefaultUrlService` go through `getFromCache`/`putInCache`/`evictFromCache`, which log and fall back to the database when Redis fails; a visit that reaches the limit evicts the cached entry.
- CORS via `CorsConfigurationSource` bean (not headers in JwtFilter). Allowed: frontend origin + all standard methods.
- Client IP resolved via `ClientIpResolver` (never read X-Forwarded-For manually): a single `CF-Connecting-IP` header that parses as an IP literal wins (set by Cloudflare; trustworthy only while the origin accepts traffic from Cloudflare alone), otherwise `request.getRemoteAddr()` as rewritten by `ForwardedHeaderFilter`. `ForwardedHeaderFilter` runs after the Spring Security chains, so filters inside them see the proxy address unless `CF-Connecting-IP` is present.
- Logging: SLF4J via `@Slf4j`, parameterized `{}` format.
- Actuator endpoints: `/actuator/health` public, all others ROLE_ADMIN only.
- **Login gating** (in order): password auth → `emailVerified` check (`EmailNotVerifiedException` → 403) → if `twoFactorEnabled`, require `code` in `LoginDto` (missing → `{twoFactorRequired:true}`, no token; invalid → 401). `active` enforced at `UserDetails.enabled` (password) + `OAuth2LoginSuccessHandler` + per-request in `JwtFilter`.
- **2FA**: TOTP (RFC 6238) via `Totp` (hand-rolled, no dep). Local accounts only. Secret on `user_account`, recovery codes bcrypt-hashed in `two_factor_recovery_code`.
- **API docs**: admin endpoints hidden from OpenAPI/Swagger via `@Hidden` (whole `AdminController` + admin methods in shared controllers).

## MCP connector
AI apps (Claude, Claude Code, the MCP Inspector) use hrva.cc over MCP. The app is its own OAuth 2.1 authorization server (Spring Authorization Server through `mcp-authorization-server` 0.1.14), protects `/mcp` as a resource server (`mcp-server-security` 0.1.14) and serves the tools from Spring AI 2.0.1's MCP server. Code: `cc.hrva.urlshortener.mcp` (`authorization/`, `bridge/`, `tools/`, `anon/`, `apps/`).

### Endpoints
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/mcp` | Bearer MCP access token | Signed-in MCP server, STATELESS protocol. Tools `list_links` (20 per page), `shorten_url` (always adds the tag `ai`), `link_stats` (owner only, even for admins), and the admin-only moderation tools (see Moderation tools). Run as the token's user. Other methods → 405 |
| POST | `/mcp/anon` | None (a bearer token is ignored) | Anonymous MCP server, STATELESS, tool `shorten_url` only. Refuses every call unless `app.anonymous-url-creation-enabled` is true |
| GET | `/.well-known/oauth-protected-resource/mcp` | Public | Resource metadata; the 401 from `/mcp` points here in `WWW-Authenticate` |
| GET | `/.well-known/oauth-authorization-server` | Public | Authorization server metadata (adds `none` to `token_endpoint_auth_methods_supported`). `/.well-known/openid-configuration` and `/.well-known/oauth-authorization-server/**` → 404 |
| POST | `/oauth2/register` | Public, 20/h per IP | Open dynamic client registration (RFC 7591) |
| GET | `/oauth2/authorize` | AS session | Authorization code + PKCE (S256). Without a session → login bridge (below) |
| POST | `/oauth2/token` | Client; a public client sends only `client_id` | `authorization_code` and `refresh_token`. `resource` is optional; when sent it must be `${app.mcp.issuer}/mcp`, else `invalid_target`. Access tokens always get `aud` = `${app.mcp.issuer}/mcp` |
| GET | `/oauth2/connect/continue?otc=…` | Public | Login bridge: redeems the one-time code |
| GET | `/api/v1/connect/requests/{handle}` | ROLE_ADMIN or ROLE_USER | `{clientName}` for the consent screen; 410 when expired or used |
| POST | `/api/v1/connect/requests/{handle}/approve` | ROLE_ADMIN or ROLE_USER | `{continueUrl}`; 409 when already approved |
| POST | `/api/v1/connect/requests/{handle}/deny` | ROLE_ADMIN or ROLE_USER | `{redirectTo}`: the client's registered redirect URI with `error=access_denied` and `state` |
| GET | `/api/v1/users/me/connected-apps` | ROLE_ADMIN or ROLE_USER | `[{clientId, clientName, firstConnected, lastUsed}]`, most recently used first |
| DELETE | `/api/v1/users/me/connected-apps/{clientId}` | ROLE_ADMIN or ROLE_USER | Disconnect → 204; the app's access and refresh tokens stop working at once. 404 when not connected |

`/oauth2/jwks`, `/oauth2/revoke` and `/oauth2/introspect` are Spring Authorization Server defaults; revoke and introspect need client authentication, so public clients can't use them.

### Security filter chains (in order)
1. **`@Order(1)` authorization server** (`AuthorizationServerConfiguration`): the Spring Authorization Server endpoints, the two 404 discovery paths and `/oauth2/connect/continue`. It keeps an HTTP session from the first `/oauth2/authorize` until the code is issued; the `/mcp` and `/mcp/anon` chains are stateless.
   - `LocalhostWildcardPortValidator`: a localhost redirect URI matches on any port (CLIs such as Claude Code pick a new port per sign-in).
   - DCR validators: redirect URIs (no commas), scope (stored as requested; `openid` and commas rejected), grant types `authorization_code` and `refresh_token` only (`refresh_token` is always granted), response type `code`, size caps (`client_name` ≤ 100, ≤ 5 redirect URIs, column limits). `DcrEmptyScopeFilter` drops `"scope": ""`, which the Inspector sends.
   - `DcrClientSettings`: access token 1 hour, refresh token 30 days and rotated on every refresh, no consent screen (the frontend `/connect` page is the consent).
   - Public-client refresh (`PublicClientRefreshToken*`, token endpoint only), `ResourceIndicatorValidatingProvider` (the `resource` check), `AccessTokenAudienceCustomizer` (a code exchange or refresh without `resource` still gets `aud` = `${app.mcp.issuer}/mcp`; it runs after the library's `resource` customizer).
   - `DisconnectSafeAuthorizationService`: a refresh racing a disconnect gets `invalid_grant` instead of re-creating the authorization.
2. **`@Order(2)` `/mcp` resource server** (`McpResourceServerConfiguration`): `/mcp` and `/.well-known/oauth-protected-resource/**`. RS256 JWTs are checked against the local signing key (no issuer discovery): `iss` = `app.mcp.issuer`, `aud` must contain `${app.mcp.issuer}/mcp`, and `StillAuthorizedValidator` looks the token up in `oauth2_authorization` on every request, so Disconnect is immediate; a database error fails closed as 401 `invalid_token` without infrastructure text. STATELESS, CSRF off.
3. **`@Order(3)` `/mcp/anon`** (`AnonymousMcpConfiguration`): permitAll, STATELESS, CSRF off. Its MCP server is built by hand (only its `RouterFunction` is a bean), so it never picks up the signed-in tools.
4. **`@Order(LOWEST_PRECEDENCE)` API chain** (`SecurityConfiguration`): the existing JWT API and OAuth2 login, plus `/api/v1/connect/**`. `JwtFilter`'s servlet registration is disabled, so it only runs inside this chain.

`/mcp` and `/mcp/anon` never reach `UrlRedirectController`, and `mcp` is a reserved alias.

### Configuration
| Property | Env var | Production | `local` profile |
|----------|---------|------------|-----------------|
| `app.mcp.issuer` | `APP_MCP_ISSUER` | `https://hrva.cc` (`application.yaml`) | `http://localhost:8080` |
| `app.mcp.signing-key` | `APP_MCP_SIGNING_KEY` | Required: a private RSA JWK (JSON, ≥ 2048 bits). Startup fails without it, and with the committed dev key (`kid` `hrva-local-dev`) | Committed dev-only key, not a secret; refused at startup outside `local` |
| `app.anonymous-url-creation-enabled` | `APP_ANONYMOUS_URL_CREATION_ENABLED` | `false` (`application.yaml`); also gates anonymous links on the web | `true` |
| `app.frontend-url` | `APP_FRONTEND_URL` | The frontend origin; the bridge redirects to its `/connect` page | `http://localhost:3000` |

- The issuer is never derived from request headers: discovery, `WWW-Authenticate`, `iss`, `resource` and `aud` all come from `app.mcp.issuer`.
- `spring.ai.mcp.server.*` (`application.yaml`): STATELESS protocol at `/mcp`; resources, prompts and completions off.
- Session cookie (the authorization server chain's login bridge, and Google `oauth2Login`, which keeps its authorization request in a session; the API chain never reads a session for authentication): `HttpOnly`, `SameSite=Lax`, `Secure` (off in `local`).
- Generate a signing key (prints one line of JSON; store it as a secret in `APP_MCP_SIGNING_KEY`):
  ```bash
  node -e "const {generateKeyPairSync, randomUUID} = require('node:crypto'); const {privateKey} = generateKeyPairSync('rsa', {modulusLength: 2048}); console.log(JSON.stringify({...privateKey.export({format: 'jwk'}), kid: randomUUID(), use: 'sig', alg: 'RS256'}))"
  ```
  Changing the key invalidates issued access tokens only; clients refresh, because refresh tokens are opaque database rows.

### Login bridge
1. `GET /oauth2/authorize` without a session: `ConnectRedirectEntryPoint` stores the pending request in Redis (`mcp:connect:request:<handle>`, 10 min) and ties the handle to the new session → 302 `${app.frontend-url}/connect?request=<handle>`. Redis down → 503 without infrastructure text.
2. The frontend `/connect` page calls `GET /api/v1/connect/requests/{handle}` with the user's app JWT for the client name, then approve or deny.
3. Approve returns `continueUrl` = `${app.mcp.issuer}/oauth2/connect/continue?otc=<one-time code>` (60 s, single use, redeemed with Redis `GETDEL`). The app JWT never appears in a URL.
4. The frontend navigates the top-level window to `continueUrl`. The code must belong to this browser's session (another browser → 403, a replay → 400). The session id rotates, the session is authenticated as the approving user for 60 s and bound to the approved authorization query → 302 to `/oauth2/authorize?<original query>`.
5. Spring Authorization Server issues the code, and `SessionEndingAuthorizationResponseHandler` ends the session. An authenticated `/oauth2/authorize` with any other query ends the session and starts over (`BridgedAuthorizationRequestFilter`).

### Rate limits
Redis fixed windows (`RedisFixedWindowRateLimiter`, key `rate-limit:<name>:<sha256 of the client network>`: the IPv4 address or the IPv6 /64, never a raw IP). The MCP-specific limits (`mcp-anon`, `dcr`) fail closed when Redis is down.
- **`/mcp/anon` `shorten_url`**: 10 per 60 s per network (`mcp-anon`). Every attempt counts. The refusal is a tool error: "Too many anonymous links from this network. Try again in a minute, or connect https://hrva.cc/mcp to use your account."
- **`/oauth2/register`**: 20 per hour per network (`dcr`) → 429 with `Retry-After: 3600`; 503 when Redis is down.
- **Signed-in `/mcp` `shorten_url`**: counts toward the account's 60 links per hour (see Security). That limit is not MCP-specific and lets the create through when Redis is down; the refusal is a tool error with its text.
- The client IP comes from `ClientIpResolver`, which trusts `CF-Connecting-IP` (see Security). That is safe only while the origin accepts traffic from Cloudflare alone.
- **Body size**: `McpRequestBodyLimitFilter` (a servlet filter before `DcrEmptyScopeFilter` and Spring Security) caps POST bodies on `/oauth2/register`, `/mcp` and `/mcp/anon` at 64 KB. A larger `Content-Length` → 413 `{"error":"request_too_large"}`; a body without a length (chunked) fails once it passes 64 KB: 413 on `/oauth2/register`, 400 "Invalid message format" from the MCP transport. `DcrEmptyScopeFilter` also reads at most 64 KB on its own.
- Redis must be 6.2 or newer (`GETDEL`, and `SMISMEMBER` for the blocklists).

### Moderation tools
`mcp/tools/ModerationTools`, beside `LinkTools` on `/mcp` only (never on `/mcp/anon`), so an admin can moderate from Claude or ChatGPT. The queue holds text written by scammers that could carry instructions aimed at the assistant, so MCP may only **read and block**:
- Read-only: `moderation_summary` (queue size and visitor reports, blocks in 24 h, possible scammers, failing enabled sources), `review_queue` (20 per page, `reason` filter; visitor report text is left out), `possible_scammers`, `check_url` (the `explain` verdict).
- Destructive: `block_link`, `block_domain`, `block_user`, `delete_link`.
- **Admin only:** every tool first requires `ROLE_ADMIN` on the token's user (`McpUserResolver`) and otherwise refuses with "Moderation tools are for hrva.cc admins only." The tools are still listed to non-admin sessions.
- **Nothing loosens protection:** no Looks fine, Trust domain, Allow rules or unblocking; those stay in the dashboard. Only the dashboard can undo an MCP block.
- **Cap:** `block_domain` and `block_user` refuse, changing nothing, when more than 50 active links would be affected, and point to the dashboard. `block_user` refuses admin accounts.
- **`delete_link` and `block_user` need evidence (R56):** only a blocked link or one with an open `system_flagged` entry, because deleting frees the alias for anyone to register again; otherwise "Only blocked or flagged links can be deleted here. Use the dashboard." `block_user` likewise needs at least one blocked link or open `system_flagged` entry on the account, otherwise "This account has no blocked or flagged links. Use the dashboard to block it."
- **`block_domain` needs evidence:** at least one link on the domain (subdomains included) with an open `system_flagged` entry in the queue or already blocked (R52), and every affected active link with an open `system_flagged` entry (R52b), so a flagged subdomain can't unlock a big parent domain such as `github.io`. A visitor report alone is never evidence (R56), so a report can't talk the assistant into a block. A domain that is itself a public suffix (Guava `isPublicSuffix`, the PSL's private section included: `ddns.net`, `pages.dev`, `github.io`) is refused first with "That's a shared hosting domain. Use the dashboard to block it."; one site on it (`evil.github.io`) can still be blocked. The dashboard has no such limit.
- **Masking:** users appear as a masked email plus id (`r***@gmail.com #27`) and tools take user ids, never emails; destinations appear as host plus path (at most 80 characters, delimited and percent-encoded), never the query or fragment; no IPs or visit details.
- Every action goes through `ModerationService` into the audit log with `via: mcp`.

### Cleanup and revocation
- **`DcrClientCleanupJob`**, daily at 03:30: deletes fully expired `oauth2_authorization` rows, then DCR clients registered more than 30 days ago that have no authorization left (with their consent rows). It never deletes a client with a live authorization.
- Disconnect removes all of the user's authorizations for that client. Deleting or deactivating a user, or changing their email, removes all of their authorizations (`DefaultUserService`).
- Tables (Liquibase 017–021): `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`, with indexes for the token, authorization code, principal and client lookups.

### Tests
Security tests go through the real filter chains (`@SpringBootTest` + MockMvc, never `standaloneSetup`):
- `mcp/authorization/AuthorizationServerConfigurationTest`: fixed issuer despite forged forwarded headers, DCR validators, localhost port wildcard, 404 discovery paths, the dev signing key refused outside `local`.
- `mcp/authorization/PublicClientRefreshTest`: public-client refresh, rotation and replay, `resource` and `invalid_target`, the `/mcp` audience without `resource`, introspect and revoke closed to public clients.
- `mcp/McpEndpointSecurityTest` (real port): `/mcp` 401 with resource metadata, audience, revoked tokens, a database failure as 401 without infrastructure text, tools as the token's user, no stack traces or logged bodies.
- `mcp/bridge/LoginBridgeTest` (real port): the bridge (410, 409, 403 in another browser, 400 on replay, session binding, JWT never in a `Location`, 503 when Redis is down), and neither an MCP token nor the bridged session authenticates the API.
- `mcp/anon/AnonymousMcpTest`: `/mcp/anon` tool set, ignored tokens, rate limit (exact text, Cloudflare client, fails closed), the 64 KB body cap on all three unauthenticated endpoints, unknown method names never logged.
- `mcp/apps/ConnectedAppsTest`: list, disconnect, refresh racing a disconnect, DCR rate limit, cleanup.
- Unit tests: `mcp/tools/LinkToolsTest`, `ModerationToolsTest` (admin gate, masking, cap, evidence), `McpToolResultsTest`, `mcp/anon/RateLimiterTest`, `McpMessageBodyLogFilterTest`, `McpRequestBodyLimitFilterTest`, `security/ClientIpResolverTest`, `configuration/properties/AppPropertiesTest`. Helpers: `mcp/McpAccessTokens`, `mcp/bridge/InMemoryRedis`.

### End-to-end check (`scripts/mcp-e2e.sh`)
Runs the whole flow against a throwaway stack: Postgres 18 and Redis 8 in disposable containers (`docker run --rm`, random `127.0.0.1` ports, label `hrva-mcp-e2e`) and the jar with the `local` profile on port 8091 (issuer `http://localhost:8091`). It never touches `docker/docker-compose.yml`, its `db` volume or ports 5432, 6379 and 8080, and a `trap` removes the containers, the app and the temp files on exit.
```bash
scripts/mcp-e2e.sh                        # builds the jar, then runs every step
MCP_E2E_SKIP_BUILD=1 scripts/mcp-e2e.sh   # reuses target/url-shortener-*.jar
MCP_E2E_PORT=8092 scripts/mcp-e2e.sh      # another app port
```
- Needs Docker, Java 25, Maven, Node (`npx`), `jq`, `curl` and `openssl`. It runs MCP Inspector CLI 2.11.0 (`MCP_E2E_INSPECTOR` overrides), with the Inspector's state in the temp dir.
- **curl** plays the client, browser and frontend: discovery, DCR, `/oauth2/authorize` → 302 to `/connect`, sign-in as `user@localhost` via `/api/v1/auth/login`, approve, continue, code → token with PKCE and `resource`, one refresh (rotation, replay refused), the connected-apps list, and a disconnect after which `/mcp` returns 401.
- **The Inspector CLI** signs in on its own (discovery, DCR, PKCE). `scripts/mcp-e2e/fake-browser/open` stands in for the browser and the frontend, so this step needs macOS. It then calls `tools/list`, `list_links`, `shorten_url` (the link must carry `ai`) and `link_stats`, and finally `/mcp/anon` `shorten_url` without a token.
- Every step asserts its result and the script exits non-zero on the first failure. Tokens, one-time codes and handles are redacted in the output.

### Owner acceptance with the real Claude client
Not automatable (interactive sign-in). With the local dev stack, the app on port 8080 and the frontend on `http://localhost:3000`:
1. `claude mcp add --transport http hrva-local http://localhost:8080/mcp`, then `/mcp` → authenticate in Claude Code, sign in and Allow on `/connect`.
2. Record what Claude registered:
   ```bash
   docker compose -f docker/docker-compose.yml exec db psql -U root -d url_shortener -c \
     "select client_name, client_authentication_methods, authorization_grant_types, redirect_uris, scopes, client_id_issued_at from oauth2_registered_client order by client_id_issued_at desc limit 5;"
   ```
3. If no new row appears and the authorize request carries an `https://` `client_id`, Claude used Client ID Metadata Documents instead of DCR: open a follow-up to enable `mcp.cimd(true)`.

## Scheduled Tasks
Jobs run on a 4-thread scheduler (`spring.task.scheduling.pool.size: 4` in `application.yaml`, threads `scheduling-1`…`4`), so a long job such as the hourly blocklist refresh can't stall the per-minute ones.
- **Blocklist refresh**: Every hour, `refreshDue` downloads each enabled source whose own interval (`refreshIntervalHours`, 1–168, default 6; 5 minutes' tolerance) has passed or whose Redis set is missing. `reloadMissing` (`@Async`) downloads every enabled source whose set is missing, at startup (`ApplicationReadyEvent`) and every 5 minutes (`reloadMissingBlocklists`, 30 s past the minute): one `EXISTS` per source, and while Redis is down one warn line and nothing else until the next run. So a Redis restart or `FLUSHALL` heals itself within minutes, without an app restart.
- **Click-check lists**: Every 5 minutes, at startup and after any source or rule change (`BlocklistConfigurationChangedEvent`), `DefaultClickCheckService.reload` re-reads the enabled `BLOCK` source ids and the Allow-rule domains into memory.
- **URL expiry**: Daily at 8 AM, sends email notification for URLs expiring within 24 hours (configurable via `app.url-expiration-notification-hours`).
- **IP cleanup**: Every minute, deactivates and deletes old IP address records.
- **Reset tokens**: Every minute, deactivates and deletes expired password reset tokens.
- **URL deactivation**: Every minute, deactivates expired URLs with a targeted update by id (`deactivateIfActive`), never by saving loaded copies, so a block landing meanwhile stays.
- **API key deactivation**: Every hour, deactivates expired API keys.
- **User deactivation**: Daily at 10 AM, deactivates unused accounts after configured inactivity period.
- **MCP cleanup**: Daily at 03:30, deletes expired OAuth authorizations and DCR clients unused for 30 days (`DcrClientCleanupJob`).
- **Nightly scan** (`DefaultSafeBrowsingRecheckService`): Daily at 04:30, walks every active link by id (so deactivations mid-run don't skip rows) through the link check and Safe Browsing. List hits, admin Block rules and Safe Browsing block the link (deactivated, evicted from the cache, owner emailed); a `SCAM_SIGNALS` verdict and every flag go to the review queue instead, unless the same reasons were already marked fine. It then follows the redirect chains of up to 500 active links whose `chainCheckedAt` is null or over 7 days old, oldest first. Once Safe Browsing fails, the run stops calling it, finishes with the link checks only and skips the redirect checks.
- **Interrupted emails**: Every 5 minutes, `email_log` rows still `SENDING` after 10 minutes become `FAILED` ("interrupted") so they can be resent.
- **Decisions email** (`DefaultLinkDigestService`): Daily at 9 AM, emails `app.contact-email` "N decisions waiting on hrva.cc", only when something needs a decision: an open review entry, a link blocked automatically in the last 24 h (any block reason but `ADMIN` and `USER_BLOCKED`), or an enabled source whose last download failed. It lists the open count and the visitor reports among them, the newest 10 entries (each linking to the queue page; a visitor report shows only its category, never the visitor's text, through `ReviewReasons.withoutVisitorText`, which `review_queue` uses too), up to 10 possible scammers, the automatic blocks and the failing sources, plus an information-only section of links from accounts younger than 7 days that never triggers sending alone. No link in it acts by itself (mail scanners prefetch links).
- **Discord alerts** (`DefaultDiscordAlertService`): every 30 s, posts what happened since the last flush to the Discord webhook in `app.discord-webhook-url` (`DISCORD_WEBHOOK_URL`) as one message, and once more when the app shuts down (see Discord alerts).

## Config Profiles
- **`local`**: `localhost` Postgres/Redis/MailHog, hardcoded JWT secret, dev users auto-created, verbose SQL logging. MCP issuer `http://localhost:8080`, a committed dev-only signing key (not a secret; refused at startup outside `local`) and anonymous link creation on, so `/mcp/anon` works. Auto-imports `application-oauth.yaml` for SSO secrets.
- **`prod`**: Remote Postgres, no dev defaults. Secrets injected externally.
- **`oauth`**: Optional profile holding OAuth2 client credentials. Imported by `local` profile via `spring.config.import`.
- Default: `application.yaml` sets base values (cache TTL, URL length, API limits, etc.).
- Tests run without any profile; only the `@SpringBootTest` classes listed under Testing start a (trimmed) application context.
- `app.blocklists.max-redis-mb` (default 256): the Redis budget across all blocklist sets when Redis has no `maxmemory`.
- `app.discord-webhook-url` (`DISCORD_WEBHOOK_URL`, blank by default): the owner's Discord webhook for admin alerts; blank turns them off (see Discord alerts). A secret: excluded from `AppProperties.toString()`, never logged or returned.

## New Models (recently added)

### AuditLog (`audit_log` table, migrations `008-audit-log.xml`, `025-audit-log-typed-action.xml`)
- Tracks admin actions: performedBy, action, failed, targetType, targetIdentifier, details, performedAt
- `action` is an `AuditAction` (`model/enums`), stored by name (`@Enumerated(EnumType.STRING)`): one constant per audited controller method (`REVOKE_URL`, `UPDATE_USER`, `CREATE_SOURCE`, …), the `ModerationService` actions (`RESOLVE_REVIEW`, `BLOCK_LINK`, `BLOCK_DOMAIN`, `ALLOW_DOMAIN`, `REMOVE_DOMAIN_RULE`, `BLOCK_USER`, `REACTIVATE_USER`, `DELETE_LINK`) and `UNKNOWN`, which only migrated rows carry.
- Auto-logged via `AdminAuditAspect` around the controller methods annotated `@Audited(AuditAction.…)`; the annotation names the action, so a method can't be audited without one. `AuditedMethodsTest` pins the audited methods and their actions, so adding or dropping one fails until the test is updated. `targetType` is the controller name without `Controller` and a leading `Admin` (`AdminEmailLogController` → `EmailLog`).
- `details` lists only the submitted, non-null fields of the request body, alphabetically (`longUrl: …, visitLimit: 10`); a password is written as `changed`/`removed`, never its value. A failed action keeps its action with `failed = true` (`AuditLogService.logFailure`) and the exception message as details. Migration `023-audit-log-redact-url-passwords.xml` redacted the link passwords older rows held in plain text.
- Migration 025 added `failed` and rewrote the old free-text actions: the aspect's camelCase method names (every one it ever audited) became their constants, a `…_FAILED` suffix became the base action with `failed = true`, and anything else became `UNKNOWN`, its old value copied into `details` when those were empty. `AuditActionMigrationTest` runs it on legacy rows.
- Endpoint: `GET /api/v1/admin/audit-log` (`?action=`)

### EmailLog (`email_log` table, migration `009-email-log.xml`)
- Records email sending: sender, recipient (comma-separated), subject, status (SENDING/SENT/FAILED/RESENT), errorMessage, sentAt, createdAt, body
- Populated automatically by `tryToSendEmail` in `DefaultSendingEmailService`, which retries 3 times and records any failure (checked or runtime) as FAILED with its message
- `body` (migration `022-email-log-resend.xml`) holds the rendered HTML only until the email is sent, and at most 7 days for failed ones (daily 03:15 cleanup); it is `@JsonIgnore`d and exposed as the `resendable` flag (FAILED with a body)
- Endpoints: `GET /api/v1/admin/email-log`; `POST /api/v1/admin/email-log/{id}/resend` (admin, synchronous) sends a stored failed email again as a new row and marks the old one RESENT

### VerificationToken (`verification_token` table, migration `013-email-verification.xml`)
- Email verification on register; `user_account.email_verified` (existing users grandfathered `true`). OAuth users auto-verified.
- Flow: register → unverified + verification email; `POST /auth/verify-email/confirm` activates; `/resend` reissues.

### Abuse handling
- `SuspiciousUrlService` flags destinations by host: other shorteners (and our own host), free dynamic-DNS hosts, raw IPs, punycode, lookalikes of known brands on someone else's domain (`njuskaio.cam`, `jofogas.cards`), login/verification lures, high-risk TLDs. Flags only; it never deactivates anything.
- `GET /urls/all` (admin) returns `suspiciousReasons` per link (omitted when empty), and `blockReason`, `reviewOpen`, `ownerPossibleScammer` and `finalUrl` (omitted when null); searching matches the short link, destination and owner email; `?anonymous=true` keeps links without an owner; sort by owner with `sort=owner.email,asc`.
- New links and admin destination changes pointing at a generic shortener (bit.ly, tinyurl, …) or at hrva.cc are rejected (`checkIfLongUrlIsNotAShortener`). Platform share links (t.co, lnkd.in, goo.gl) are only flagged, and Google's `*.app.goo.gl` share links are neither.
- A blocked link has a `blockReason` (`SAFE_BROWSING`, which also sets `threatType`, `BLOCKLIST`, `DOMAIN_RULE`, `ADMIN`, `USER_BLOCKED`, `SCAM_SIGNALS`), a `blockDetail` (the list, rule or signal, cut to 255 characters with `StringUtils.abbreviate` like a rule's reason, so a long admin or MCP reason can't fail the block) and `blockedAt`; its status is BLOCKED. Owners can't reactivate it; admins can, which re-runs the checks unless the domain is allow-listed. Every reactivation re-checks Safe Browsing and that no other active link holds the alias. `Url` has no `@Version`, so `updateUrl` and `activateUrl` run their checks outside a transaction, then detach the loaded copy (open-in-view shares one persistence context per request), re-read the row in the write transaction and refuse with `This link was blocked while you were editing it. Reload and try again.` when it gained a block meanwhile; otherwise the edit is applied to the re-read row, so a stale copy never writes back `active` or the block fields. A blocked link's owner is emailed (`url_blocked.html`), except when the whole account is blocked.
- A custom alias stays taken while any row (active or not) holds it, so an expired link's alias can't be claimed by someone else.
- **Link checks** (`LinkCheckService.check`, called by `validateUrl` and `updateUrl` on every create path, MCP included, before the write transaction; `explain` runs every lookup and lists every match for the url-check and `check_url`). The verdict is for the destination host, matched on the host and each parent domain, lowercased, trailing dot stripped. In order:
  1. **Allowed**: an admin Allow rule skips the domain lists, domain age, Spamhaus and the heuristics. The most specific matching rule wins, so a Block on `evil.example.com` beats an Allow on `example.com`. Exception (R35): an exact-link entry on an enabled `LINKS` Block source still blocks, because it names that one URL.
  2. **Block**: an admin Block rule (`DOMAIN_RULE`), an enabled `BLOCK` source (`BLOCKLIST`, the source name), Spamhaus DBL phish, malware or botnet (`BLOCKLIST`, `Spamhaus DBL: <type>`), or a domain younger than 30 days with a lookalike or login-lure heuristic (`SCAM_SIGNALS`).
  3. **Flag**: a lookalike plus a lure on an older domain, an enabled `FLAG` source, Spamhaus spam or an abused legitimate domain, a domain younger than 30 days, or any other `SuspiciousUrlService` reason.
  4. Otherwise clean. An unknown domain age never counts. A blank destination is clean; a non-blank one whose host can't be read is a Flag (`destination host could not be read`), which only the nightly scan and redirect hops can meet, because creating such a link fails `checkIfLongUrlIsValid` first. Safe Browsing and the shortener refusals run whatever the verdict.
- On create or a destination change, a Block → 400 "This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us." (the list is never named); a Flag saves the link and opens a review entry with its reasons.
- **Domain age** (`DefaultDomainAgeService`): RDAP through the IANA bootstrap registry for the registry domain, 3 s timeout; failures and registries without dates (`.de`, `.hr`) are unknown. **Spamhaus DBL** (`DefaultDomainReputationService`): only with `SPAMHAUS_DQS_KEY`, `<name>.<key>.dbl.dq.spamhaus.net` for the host and its registrable domain, 2 s timeout; on create and nightly, never on a click.
- **Blocklist sources** (`blocklist_source`, managed in the dashboard; nothing depends on HaGeZi). Kind `DOMAINS`: plain, hosts (`0.0.0.0 d`), adblock (`||d^`) and wildcard (`*.d`) lines; `@@` exceptions and full-URL lines are skipped. Kind `LINKS`: one `http(s)://` link per line, or a CSV's `url` column; keys drop the scheme, default port and fragment (`LinkKeys`), percent-encode raw spaces, non-ASCII and `"<>{}|^` in the path and query with `RedirectLocations.percentEncoded` (so a raw and an encoded spelling are one key, on the feed side and the link side alike), and match with or without the query. Downloads: https and public addresses only (re-checked on every hop), the main URL then up to 3 fallbacks in order, ETag/Last-Modified sent only to `lastSuccessUrl`, caps of 100 MB and 5 M entries. Zero entries, or fewer than 90 % of the count a header declares, fail. **Shrink guard**: when the list declares no count, a scheduled refresh (`refreshDue`, `reloadMissing`) that reads fewer than half of a stored `domainCount` of 1,000 or more, while that set is still in Redis, fails with "The new download has N entries, less than half of the M loaded before. Kept the old list." (the next URL is tried); Refresh now skips the guard, so a list that really shrank can be accepted. A failure keeps the old set and records `lastError` (every failed attempt, even when a fallback then succeeded). A source URL can carry an access key (URLhaus Auth-Key), so download log lines name the source and the attempt (`main`, `fallback 1`), never the URL, and the audit details of a source create or update leave out `url` and `fallbackUrls`. **Memory guard**: a load that would take Redis past 80 % of `maxmemory`, or past `app.blocklists.max-redis-mb` across all sets when there is no `maxmemory` (estimated at 100 bytes per domain, 200 per link), is refused.
- **Seeds** (024): HaGeZi TIF medium and Fake (`BLOCK`), badware hosters, DynDNS and URL shorteners (`FLAG`), every 6 h from `hagezi-mirror.dnsbunker.org` with the same GitHub path as fallback; URLhaus (1 h), OpenPhish (12 h) and PhishTank (6 h) as `LINKS` `BLOCK`, URLhaus switched off (since October 2026 its downloads answer 410 without a free abuse.ch Auth-Key in the path, `https://urlhaus-api.abuse.ch/v2/files/exports/<key>/…`; put that URL in the source, then enable it); Phishing.Database active domains (`FLAG`, 24 h). Block rules for `njuskaio.cam`, `jofogas.cards`, `info-auth.com` and `app-onlinebanking.de`.
- **Click check** (`ClickCheckService.mayRedirect`): before a visitor is sent anywhere (the redirect from the cache or the database, `/unlock`), the destination's domains and link keys are looked up in the enabled `BLOCK` sources' sets in one pipelined `SMISMEMBER` round trip. A match blocks the link (`BLOCKLIST`, the source name, via `click`) and the visitor goes to `/unavailable?reason=blocked`; `GET /urls/{short}`, `/peek`, `/preview` and `/qr` answer a match like a missing link. An Allow rule skips the domain sets. A Redis failure lets the visitor through; a failure while blocking still refuses. `FLAG` sources, RDAP, Spamhaus, heuristics and Safe Browsing are never used on a click.
- **Domain rules** (`domain_rule`, a rule covers the domain and its subdomains): a Block deactivates and blocks every active matching link (evicted from the cache, owner emailed, audited), sparing links a more specific Allow trusts; an Allow resolves the domain's open queue entries as `DOMAIN_TRUSTED` and restores its `BLOCKLIST`, `SCAM_SIGNALS` and `DOMAIN_RULE` blocks after a fresh check, never `SAFE_BROWSING` or `ADMIN` ones. Block user deactivates the account and blocks its links as `USER_BLOCKED` (one account email, no per-link emails, never an admin); unblocking restores those links.
- **Review queue** (`link_review`): one open entry per link with newline-joined reasons (resolved one by one or up to 100 at a time, see Key Endpoints); a new reason extends it. After Looks fine (`OK`) the link re-enters only with a new reason. A destination change resolves the open entry as `DESTINATION_CHANGED` and re-flags if needed. Visitor reports add `reported by a visitor: <category>[: details]` (at most 20 report lines). `system_flagged` marks an entry the checks flagged: `flag` sets it, `report` never does and leaves it as it was, so an entry only visitors reported has it false. `ReviewQueueService.flag` and `resolveOpen` must never run inside a read-only transaction, which would silently drop the write.
- **Possible scammer** (`ScammerSignalService`, computed, never acted on automatically): an account younger than 7 days with 2 or more flagged or blocked links (flagged: a `system_flagged` entry not resolved `OK` or `DOMAIN_TRUSTED`; visitor reports never count), 3 or more blocked links in total, or 10 or more links within 24 h of signup (only for accounts younger than 30 days). Deactivated and admin accounts are left out. The id set is a 60 s snapshot, refreshed on block and unblock.
- **Redis keys** (all through `StringRedisTemplate`; every caller is fail-soft, see Performance):
  - `blocklist:source:<id>`: one set per source (domains, or link keys without the scheme), loaded into `blocklist:source:<id>:loading` (expires after 2 minutes) and swapped in by a Lua script that checks `SCARD`, then `RENAME`s and `PERSIST`s. Memory is read with `MEMORY USAGE` inside Lua.
  - `rdap:<registry domain>` (30 days with a date, 1 day without one, 1 hour after a failure) and `rdap:bootstrap` (1 day)
  - `dbl:<name>`: Spamhaus answers, 1 hour
  - `rate-limit:create:anon:<sha256 of the network>`, `rate-limit:create:user:<id>`, `rate-limit:report:<sha256 of the network>`, beside the MCP and `/unlock` ones
- **Redirect chains** (`RedirectChainService`): after a create or a destination change (`@Async("redirectCheckExecutor")`, 2 threads and a 200-check queue, once the save has committed; a full queue is logged and left to the nightly), and in the nightly scan for up to 500 active links whose `chainCheckedAt` is null or over 7 days old, the destination's HTTP redirects are followed: at most 5 hops in 5 s, GET with headers only, http or https, each hop through the public-address guard (a refused destination is a fetch error; a refused later hop ends the chain unrequested). A `Location` is read like a browser does before the guard sees it (`RedirectLocations`: UTF-8 bytes, `\` as `/`, space, non-ASCII and `"<>{}|^` percent-encoded, IDN host to ASCII). Every hop after the destination goes through `LinkCheckService.check` and Safe Browsing and the worst hop wins: a block blocks the link (`via redirect to <host>`), a flag or scam signals queue it (`redirects to <host>: <reason>`), unless the link was repointed meanwhile. `finalUrl` and `chainCheckedAt` are stamped with targeted updates; a fetch error stamps only `chainCheckedAt`, and a Safe Browsing outage stamps nothing (the nightly skips or stops its redirect checks) so a later run retries. The fetcher is the `RedirectFollower` bean (not a second `PublicHttpFetcher` bean, which would break by-type injection).
- **Executors** (`AsyncExecutorConfiguration`): declaring `redirectCheckExecutor` switches off Boot's own `applicationTaskExecutor` and its `@Async` default, so the configuration declares `applicationTaskExecutor` itself from Boot's `ThreadPoolTaskExecutorBuilder` (still `spring.task.execution.*`), also named `taskExecutor` so unqualified `@Async` (emails, `reloadMissing`) and visit counting keep using it. `redirectCheckExecutor` (threads `redirect-check-1`, `-2`) waits up to 15 s on shutdown for running checks.

### Discord alerts
An owner-facing alert channel next to the emails, which stay as they are: hrva.cc pings one Discord webhook (created by the owner, set in `DISCORD_WEBHOOK_URL`) when something needs the admin's attention. Blank means off: no buffering, no HTTP calls, and one startup line "Discord alerts are off: no DISCORD_WEBHOOK_URL".
- **Events** (`service/alert/AdminAlertEvent`, published with `ApplicationEventPublisher` at the chokepoints and taken by one listener, `DefaultDiscordAlertService`, through `@TransactionalEventListener(fallbackExecution = true)`, so nothing goes out for a rolled-back change):
  - a review newly opened (`DefaultReviewQueueService`: a flag from link creation, a redirect check, the Safe Browsing recheck or the nightly scan, or a visitor report that opens one). New reasons on an open review don't alert, except a visitor report that adds a line to it; a repeat of a report the entry already holds, or one past the 20-report cap, doesn't.
  - a link blocked automatically (`DefaultModerationService.blockAndTellOwner`, where the owner is emailed, when `performedBy` is `system`: the click check, the nightly scan and Safe Browsing recheck, the redirect check). The admin's own blocks (Block link, Block domain or a Block rule, Block user, from the dashboard or MCP) never alert.
  - a new account (`DefaultUserService.register`, `OAuth2LoginSuccessHandler`).
  - a blocklist source whose `lastError` goes from none to some ("HaGeZi Fake failed to load: main: HTTP 503", "(a fallback loaded)" when a fallback still stored the list) or back to none ("HaGeZi Fake loads again"), never on a repeated failure (`BlocklistRefresher`).
- **Delivery**: the listener only buffers (200 at most; the rest are counted as dropped). `flush` (every 30 s, and `@PreDestroy` on shutdown, since a redeploy restarts the single container) drains the buffer into one message through `DiscordWebhookClient` (`java.net.http`, each call capped at 5 s). A message holds at most 10 lines and 2,000 characters; what doesn't fit, plus the dropped count, becomes "…and N more, see <{frontendUrl}/admin/review>" and is not carried over. Fire and forget: on failure one warn line with the exception class or HTTP status (never the webhook URL), and the batch is dropped, except a send interrupted mid-call (the scheduler stopping on shutdown), whose alerts go back into the buffer, within its cap, for the shutdown flush. Only an HTTP 429 with `retry_after` under 10 s is retried, once, and never on shutdown, so shutdown waits at most 5 s.
- **Payload** (`DiscordAlertFormatter`): `content` only, one plain line per item, with `"allowed_mentions": {"parse": []}` (a visitor's `@everyone` can't ping) and `"flags": 4` (suppress embeds, so Discord never previews a destination; it would hide embeds too, hence no embeds). A link shows as `hrva.cc/<alias> → <destination host>` (never the path or query; an IP destination is "an IP address"), its reasons, `anonymous` or `account #<id>`, and the admin page: `/admin/review?entry=<urlId>` for reviews, `/admin/urls` for blocks, `/admin` for new accounts, `/admin/blocklists` for sources. Free text (visitor details cut to 200 characters, reasons, block details, errors) loses newlines, backticks, `<>` and `[text](url)` link syntax, gets Discord markdown escaped and web addresses wrapped in `<…>`; email addresses and IP addresses become "(email removed)" and "(IP removed)". Never an email address: a new account is its id and sign-up method (email, Google, GitHub).
- Tests: `service/alert/DiscordAlertFormatterTest` (payload rules, overflow), `client/DiscordWebhookClientTest` (a local `HttpServer`: refusals, 429, timeouts, nothing secret in the log), `service/DefaultDiscordAlertServiceTest` (off when blank, buffer cap, after-commit only, shutdown flush, end to end against a local webhook), and the publisher tests in `DefaultReviewQueueServiceTest`, `DefaultModerationServiceTest`, `DefaultBlocklistSourceServiceTest`, `DefaultUserServiceTest`, `OAuth2LoginSuccessHandlerTest`.

### URL tags (`url_tags` table, migration `014-url-tags.xml`)
- `Url.tags` `@ElementCollection` of normalized strings (`TagNormalizer`: lowercase, ≤10 × ≤30 chars). Filter via `?tag=`; list via `GET /urls/tags`.

### TwoFactorRecoveryCode (`two_factor_recovery_code` table, migration `015-two-factor.xml`)
- 2FA recovery codes (bcrypt-hashed, one-time). `user_account.two_factor_enabled` / `two_factor_secret`.

### Account self-service
- `GET /users/me/export` (GDPR JSON: profile + urls + api keys + emails); `DELETE /users/me` (hard delete; clears reset/verification/recovery tokens, ip_address rows, urls, api keys).
- URL/API-key responses carry a `status` reason (ACTIVE / EXPIRED / LIMIT_REACHED / REVOKED / DEACTIVATED / BLOCKED).

## Cache Architecture
- **Cache name**: `urls`
- **TTL**: 1 hour (configurable in `AppCacheConfiguration`), which bounds how long a stale entry can outlive a missed eviction, as a cached hit never rereads the block fields
- **Used by**: `redirectResultUrl` (redirect) and `peekUrlByShortUrl` (peek) — both use manual `CacheManager` access
- **Validation on cache hit**: `isUrlRedirectValid()` checks active, expirationDate, visitLimit/visits before serving
- **Eviction**: `@CacheEvict` on revokeUrl, activateUrl, updateUrl (by key), deleteUrl (all entries). A moderation block or reactivation evicts once it commits and again 2 s later on the scheduler (`TaskScheduler`, fail-soft), so a click that read the row just before the commit can't leave the old answer cached
- **Warmup**: `CacheWarmup` loads top 20 most visited, most recent, and recently accessed URLs on startup
- **Priming**: New URLs cached immediately on creation via `cacheUrlResponse()`
- **Graceful degradation**: `CacheErrorHandler` logs and falls through to DB on Redis failure

## CI / Deploy
- `.github/workflows/build.yml`: `mvn clean package` → Docker build/push to `ghcr.io`
- Multi-arch: `linux/amd64,linux/arm64`
- Pushes on `main` branch. PRs only build (no push).
- Portainer env vars: `OAUTH2_GOOGLE_CLIENT_ID`, `OAUTH2_GOOGLE_CLIENT_SECRET`, `OAUTH2_GITHUB_CLIENT_ID`, `OAUTH2_GITHUB_CLIENT_SECRET`, `APP_MCP_SIGNING_KEY` (required), `APP_ANONYMOUS_URL_CREATION_ENABLED` (optional), `SPAMHAUS_DQS_KEY` (optional; the Spamhaus DBL lookups are off without it), `DISCORD_WEBHOOK_URL` (optional; the Discord admin alerts are off without it).
- Before the first MCP connector deploy: the production checklist in `README.md`.
