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
- `mvn test` (or `mvn -B verify`) — 2115 tests, JUnit 5 + Mockito + AssertJ
- Surefire runs them with `-Duser.timezone=UTC` (`pom.xml`), the container's zone, so stored timestamps and day boundaries in the tests match production on any machine.
- Mostly plain `@ExtendWith(MockitoExtension.class)` unit tests. Each `@SpringBootTest` class boots its own nested `TestApplication` (a trimmed context) on embedded H2 and mocks the rest:
  - MockMvc through the real filter chains, with a subset Liquibase changelog (`db/changelog/017-oauth2-authorization-server.xml` or `db/mcp-connector-test-changelog.xml`): the MCP connector's security tests, `SecurityTxtControllerTest`, `ReportEndpointSecurityTest`, `AdminReviewQueueSecurityTest`, `AdminStatsSecurityTest` (each tab: admin 200, user 403, nobody signed in 401, `days=14` and `days=abc` 400), `AdminReservedAliasSecurityTest` (list, reserve, remove; invalid 400, duplicate 409, unknown id 404)
  - the full Liquibase changelog: `DynamicLinkChecksSchemaTest` (024's tables, seeds and constraints), `DefaultScammerSignalServiceTest`, `LinkStatsRepositoryTest` (026's daily stats tables, their `MERGE` upserts, the range reads, the owner index and the `ip_address` drops, both `EXECUTED`), `AdminStatsAndReservedAliasesSchemaTest` (027's nine changesets: the range indexes, `created_via`, `reserved_alias`, the unique `short_url` as stored and the 2048-character destination), and the admin stats repository tests in `repository/stats/` (`HourlyCountRepositoryTest`, `TrafficStatsRepositoryTest`, `LinksStatsRepositoryTest`, `UsersStatsRepositoryTest`, `SafetyStatsRepositoryTest`, `SystemStatsRepositoryTest`)
  - the full Liquibase changelog, open-in-view and `NOT_SUPPORTED`: `CurrentUserAuthoritiesOutsideTransactionTest` (the signed-in user's roles stay readable after a query outside a transaction), and MockMvc through the real filter chains, open-in-view interceptor and `AdminAuditAspect`: `ReactivateLinkFlowTest` (an owner reactivates their link, an admin unblocks one, both 200), `UpdateLinkFlowTest` (the real `DefaultUrlService`, `DefaultUrlValidator` and `DefaultLinkCheckService`: an admin's new destination drops `finalUrl` and `chainCheckedAt`, a block made right after the save or during an admin's or an owner's checks is kept, and a destination refused for the role, the blocklist or as a shortener, or a refused expiry date, is never saved) and `RefreshBlocklistSourceFlowTest` (the real `BlocklistRefresher` and a fake download: a source disabled, switched to links or deleted during the download keeps that change, its Redis set is not re-created and the download is dropped)
  - the user, API key, URL and `ip_address` tables, a hand drop of `ip_address` and its sequence, then 026 (`db/ip-address-gone-test-changelog.xml`): `IpAddressDropMigrationTest` (both drops `MARK_RAN`)
  - every changelog through 026, two `url` rows sharing the short URL `dup` inserted by hand, then 027 (`db/short-url-duplicates-test-changelog.xml`): `ShortUrlUniqueIndexGuardTest` (`027-url-short-url-unique` `MARK_RAN`, both rows kept, `enforced()` false and the startup warning) and `DuplicateShortUrlLookupTest` (which of two links sharing an alias the lookup picks)
  - 008, legacy audit rows, then 025 (`db/audit-action-test-changelog.xml`): `AuditActionMigrationTest`
  - a Hibernate-generated schema, no Liquibase: `UrlSpecificationTest`, `LinkReviewSpecificationTest`, `AdminPageQueriesTest` (statement counts of the admin pages), `UrlRepositoryTest` (targeted updates and the newest 10 links)
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
├── dto/             # Request/response DTOs (records and classes); stats/ (the admin stats tabs' responses)
├── exception/       # Standard-named exceptions (*NotFoundException, Invalid*Exception)
├── listener/        # Spring event listeners (auth success/failure)
├── mcp/             # MCP connector: authorization/, bridge/, tools/, anon/, apps/
├── model/           # JPA entities, codebook, enums
├── repository/      # Spring Data JPA repositories, ShortUrlUniqueIndex; stats/ (one JDBC repository per admin stats tab, HourlyCountRepository and HourlySeries)
├── security/        # JWT filter, token provider, auth entry point, ClientIpResolver, ClickRequestResolver
├── service/         # Interfaces + impl/ implementations; blocklist/ (download, parse, Redis sets); alert/ (Discord alert events and lines); analytics/ (click classifiers, unique visitors, analytics day); stats/ (admin stats: one collector per tab, StatsDays, StatsWindow, DailyBuckets, RuntimeMetrics, HealthStatuses, AppVersion, RedisKeyCounter)
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
- **Exception → HTTP status mapping**: `UserNotFoundException`/`ApiKeyNotFoundException`/`BlocklistSourceNotFoundException`/`DomainRuleNotFoundException`/`ReservedAliasNotFoundException` → 404, `EmailExistsException`/`ShortUrlAlreadyExistsException` → 409, `NoAuthorizationException` → 403, `BadCredentialsException` → 401, `RateLimitExceededException` → 429, a malformed body, a mistyped or missing parameter → 400 (an unknown enum value in a body names the allowed ones: `{"error": "Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"}`; enums are read by name only, never by number), an unsupported media type → 415, `CommonException` (catch-all for all custom exceptions not handled above) → 400, everything else → 500.
- **Validation errors**: Return `{"field": "message"}` map on 400. That covers a body (`MethodArgumentNotValidException`) and the items of a list body such as `/urls/bulk` (`HandlerMethodValidationException`, which would otherwise be a 500); an invalid item refuses the whole request before any link is created.
- **Destination length**: at most 2048 characters (`UrlValidator.MAX_LONG_URL_LENGTH`). `@Size` on `CreateUrlDto.longUrl` and `UrlUpdateDto.longUrl` answers 400 `{"longUrl": "Destination URL must be 2048 characters or fewer"}`; `checkIfLongUrlIsValid` checks the same limit first (on create after `CreateUrlToUrlConverter` has put `https://` before a scheme-less destination, so 2041–2048 characters without a scheme pass `@Size` and fail here), so an overlong destination (`{"error": …}`) never reaches the shortener, blocklist or Safe Browsing checks. The `URL.LONG_URL` column is `VARCHAR(2048)` since 027.

## Key Endpoints
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/v1/urls` | Optional `X-Api-Key` | Create short URL |
| GET | `/api/v1/urls/{short}` | None | Redirect info (records a click (bots are counted separately and refused on visit-limited links)) |
| GET | `/api/v1/urls/{short}/peek` | None | Quick peek (longUrl, shortUrl, createDate) |
| GET | `/api/v1/urls/{short}/preview` | None | Rich preview (title, description, og:image) |
| GET | `/api/v1/urls/{short}/qr` | None | QR code PNG (`?size=300`) |
| POST | `/api/v1/urls/bulk` | ROLE_USER | Bulk create (JSON array); an invalid item → 400 `{"field": "message"}` and nothing is created |
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
| GET | `/api/v1/analytics/overview` | Required | Analytics across the caller's links, `?days=7\|30\|90\|365` (default 30): range totals, zero-filled daily series, top 10 links by clicks, all-time counters |
| GET | `/api/v1/analytics/urls/{id}` | Required | Per-link analytics (owner/admin), `?days=7\|30\|90\|365` (default 30): summary, range totals, zero-filled daily series, top 10 + other per dimension |
| GET | `/api/v1/admin/stats` | ROLE_ADMIN | Health page numbers: real DB and Redis health, newest 10 links, link cache hit ratio, combined redirect timing, app version from `changelog.json` |
| GET | `/api/v1/admin/stats/traffic`, `/links`, `/users`, `/safety`, `/system` | ROLE_ADMIN | One admin stats tab each, `?days=7\|30\|90\|365` (default 30; anything else → 400 `days must be 7, 30, 90 or 365`). System uses `days` only for the emails series. See Admin stats |
| GET / POST | `/api/v1/admin/reserved-aliases` | ROLE_ADMIN | List: the built-in words first (`builtIn: true`, `id: null`, reason `Used by hrva.cc itself`), then the custom entries by alias, each `{id, alias, reason, builtIn, createdAt, createdBy, inUse: [{id, shortUrl, ownerEmail, active}]}`. Add: `{alias, reason}` → the entry; alias checked like a custom alias (letters, digits, `-`, `_`, ≤ 32), reason required, ≤ 200 (→ 400 `{"field": "message"}`); a duplicate or built-in word → 409 `This alias is already reserved.` |
| DELETE | `/api/v1/admin/reserved-aliases/{id}` | ROLE_ADMIN | Remove a custom entry → 204 (built-in ones have no id); an unknown id → 404 `Reserved alias not found` |
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
| GET | `/{short}` | None | Redirect after the click check. A blocked, expired, over-limit, deactivated or missing link → 302 `{frontendUrl}/unavailable?s={short}&reason=blocked\|expired\|limit\|inactive\|missing` (the list is never named); a bot on a visit-limited link → `reason=missing`. The destination goes out through `RedirectLocations.normalized`, from the cache and the database alike, because Tomcat drops a non-ASCII `Location`: `\` is read as `/`; the host is written in punycode with `IDN.toASCII` (an all-ASCII label keeps its case, and a numeric IPv4 form is left as stored); spaces, control characters, non-ASCII and `` "<>{}\|^` `` are percent-encoded as UTF-8; valid `%XX` sequences are kept. The destination redirects don't expand URI templates (`setExpandUriTemplateVariables(false)`), so a `{…}` in a destination sent as stored can't fail the redirect; a host that can't be converted is logged with the link id and sent as stored |
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
- **Redis fails fast**: Lettuce rejects commands while its connection is down (`RedisClientConfiguration`: `REJECT_COMMANDS`, auto-reconnect kept), so a dropped Redis fails each call at once; `spring.data.redis.timeout: 3s` only bounds a hung Redis. Every Redis caller must therefore stay fail-soft, catching the failure and degrading on purpose (cache → database, click-time blocklist check → let the visitor through, `/unlock` rate limit → allow, unique visitor check → a new visitor). Only the documented rate limits under MCP connector fail closed.
- **Read-only transactions**: `@Transactional(readOnly = true)` at class level on all services, overridden with `@Transactional` on write methods.
- **SafeBrowsing client**: Singleton bean (not built per-request). Graceful degradation on API error (returns `List.of()`).

## Security
- JWT in `Authorization: Bearer` header. Stateless sessions on the API chain; the MCP authorization server chain keeps a short HTTP session for the login bridge (see MCP connector).
- Login rate limiting: Guava cache, 20 attempts/day per IP, 1-day expiry. Wrong 2FA codes also count per account (`2fa:<userId>` key) and stop further checks once the limit is hit.
- Public lookups (`GET /urls/{short}`, `/peek`, `/preview`, `/qr`) never return the owner's email or tags; a password-protected link's destination is left out (no preview fetched, no click recorded) until `/unlock`. `/qr` encodes the short link (`app.server-url` + alias), size clamped to 64–1024.
- `/unlock` is rate limited through `RedisFixedWindowRateLimiter`: 10 attempts per client network and 50 per link per 15 minutes (allowed if Redis is down).
- Link creation is rate limited in `DefaultUrlService` through `RedisFixedWindowRateLimiter`, per hour: 10 links per client network without an account (`create:anon`, IP from `ClientIpResolver` in `UrlController`), 60 per account with one (`create:user:<id>`, shared by the web, API keys and the signed-in MCP `shorten_url`). Admins are exempt and each link of `/urls/bulk` counts. Over the limit → 429 `"You've created a lot of links in a short time. Try again in an hour."` Allowed if Redis is down. Cheap refusals come first, so junk can't trigger the slow link check: an API key is checked (unknown, expired, revoked, over its limit) → the create rate limit → the anonymous-disabled refusal → the URL checks (`validateUrl`, the 2048-character limit first; up to a few seconds on a cold RDAP or Spamhaus cache) → the write transaction, where an API key's use is recorded and a lost alias race becomes a 409 (see Unique short URLs). `/mcp/anon` passes no client IP, so only its own limit applies. `RateLimitExceededException` is a 429 everywhere, `/unlock` included.
- Link previews only fetch public addresses: loopback, private, link-local, CGNAT and ULA targets are refused, and each redirect hop (max 3) is re-checked.
- New passwords need at least 8 characters (register, reset, change). `User.password` is `@JsonIgnore`d.
- Direct `Cache` calls in `DefaultUrlService` go through `getFromCache`/`putInCache`/`evictFromCache`, which log and fall back to the database when Redis fails; a unique visit that reaches the limit evicts the cached entry (`DefaultClickAnalyticsService`, see Click analytics).
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
| POST | `/mcp` | Bearer MCP access token | Signed-in MCP server, STATELESS protocol. Tools `list_links` (20 per page), `shorten_url` (always adds the tag `ai`), `link_stats` (owner only, even for admins, optional `days`), and the admin-only moderation tools (see Moderation tools). Run as the token's user. Other methods → 405 |
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
- **The Inspector CLI** signs in on its own (discovery, DCR, PKCE). `scripts/mcp-e2e/fake-browser/open` stands in for the browser and the frontend, so this step needs macOS. It then calls `tools/list`, `list_links`, `shorten_url` (the link must carry `ai`) and `link_stats` after one browser visit (Chrome on Windows, `Referer: https://t.co/x`, `CF-IPCountry: HR`) and one plain `curl` visit, which must show one click, one daily unique visitor, one bot click, `t.co`, `HR` and `desktop`, with the matching `link_daily_stats` row and 5 `link_daily_breakdown` rows; and finally `/mcp/anon` `shorten_url` without a token, after which the signed-in link has `created_via` `MCP` and the anonymous one `ANONYMOUS_MCP`. Right after startup it also checks on Postgres that 026 ran both `ip_address` drops and left neither the table nor its sequence, that 027 ran all nine changesets, and that `ux_url_short_url` exists.
- **Admin stats and reserved aliases** (section 14): signs in as `admin@localhost` and calls all five stats tabs with `days=7` on Postgres. Traffic: one human and one bot click, the shortened link on top, no anonymous clicks, referrers filled, and `days=14` → 400 with the range message. Links: one `MCP` and one `ANONYMOUS_MCP` link in the channel series. Users: at least one connected MCP user and registered client. Safety answers 200. System: database and Redis `UP`, `PostgreSQL`, `shortUrlUnique` true, a database size, Redis memory and keys read and not capped, 7 days of emails, no connection URL or host anywhere in the answer, and it prints the Postgres version. The legacy `/api/v1/admin/stats` has `databaseActive` and `cacheActive` true and `appVersion` equal to the newest `changelog.json` release. Reserved aliases: `mcp` is listed as built-in, `E2E-Reserved` is stored as `e2e-reserved`, `user@localhost` creating `e2e-RESERVED` gets 409 `This short URL is reserved. Try another.`, and the entry is removed (204). Last, after the traffic totals so its visit doesn't change them, `user@localhost` shortens `https://čokolada.hr/torta` and the redirect answers 302 with `Location: https://xn--okolada-i6a.hr/torta`.
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
- `action` is an `AuditAction` (`model/enums`), stored by name (`@Enumerated(EnumType.STRING)`): one constant per audited controller method (`REVOKE_URL`, `UPDATE_USER`, `CREATE_SOURCE`, …), the `ModerationService` actions (`RESOLVE_REVIEW`, `BLOCK_LINK`, `BLOCK_DOMAIN`, `ALLOW_DOMAIN`, `REMOVE_DOMAIN_RULE`, `BLOCK_USER`, `REACTIVATE_USER`, `DELETE_LINK`), the reserved-alias actions (`RESERVE_ALIAS`, `UNRESERVE_ALIAS`, target type `ReservedAlias`) and `UNKNOWN`, which only migrated rows carry.
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
- A blocked link has a `blockReason` (`SAFE_BROWSING`, which also sets `threatType`, `BLOCKLIST`, `DOMAIN_RULE`, `ADMIN`, `USER_BLOCKED`, `SCAM_SIGNALS`), a `blockDetail` (the list, rule or signal, cut to 255 characters with `StringUtils.abbreviate` like a rule's reason, so a long admin or MCP reason can't fail the block) and `blockedAt`; its status is BLOCKED. Owners can't reactivate it; admins can, which re-runs the checks unless the domain is allow-listed. Every reactivation re-checks Safe Browsing and that no other active link holds the alias. `Url` has no `@Version`, so `updateUrl` and `activateUrl` run their checks outside a transaction (`NOT_SUPPORTED`), detach the copy loaded at the start, re-read the row in the write transaction and refuse with `This link was blocked while you were editing it. Reload and try again.` when it gained a block meanwhile; otherwise the edit is applied to the re-read row, so a stale copy never writes back `active` or the block fields. Open-in-view does **not** share one persistence context through such a method: the first query that runs without a transaction (a derived query such as `existsUrlByShortUrlAndActiveTrue`, or the link check's rule lookups) ties the request's `EntityManager` to the method's empty transaction, and until the method returns every `@Transactional` call opens its own short-lived `EntityManager`. So inside a `NOT_SUPPORTED` method: (1) an entity a `@Transactional` call returns may be detached, so load what you read from it later with it (`UserRepository.findByEmail` fetches `authorities` with an `@EntityGraph`), and compare entities loaded on both sides of such a query by id, not by `equals` (`User` has none; `verifyUserAdminOrOwner` runs before any such query); (2) any entity loaded in the method outside the write transaction stays managed by the request's `EntityManager`, whether or not such a query ran, and the next `REQUIRED` call on that `EntityManager` (the audit row `AdminAuditAspect` writes after the controller returns, a failure's row included) flushes any change to it as a full-row `UPDATE`. Before v2.8.3 an owner's refused destination change was saved exactly this way, with no such query run: `verifyUserAdmin` threw and the failure's audit row flushed the edit. So never modify it; edit a copy (`UrlUpdateDtoToUrlConverter` returns one, with its own tag set) and apply the copy to the row re-read in the write transaction. A blocked link's owner is emailed (`url_blocked.html`), except when the whole account is blocked.
- A custom alias stays taken while any row (active or not) holds it, so an expired link's alias can't be claimed by someone else.
- A reserved alias (the built-in words and the admin's `reserved_alias` entries, see ReservedAlias) can't be taken on any create path, in any case, and generated aliases skip it. Reserving an alias a link already holds changes nothing for that link; the admin list shows it under `inUse`.
- **Link checks** (`LinkCheckService.check`, called by `validateUrl` and `updateUrl` on every create path, MCP included, before the write transaction; `explain` runs every lookup and lists every match for the url-check and `check_url`). The verdict is for the destination host, matched on the host and each parent domain, lowercased, trailing dot stripped. In order:
  1. **Allowed**: an admin Allow rule skips the domain lists, domain age, Spamhaus and the heuristics. The most specific matching rule wins, so a Block on `evil.example.com` beats an Allow on `example.com`. Exception (R35): an exact-link entry on an enabled `LINKS` Block source still blocks, because it names that one URL.
  2. **Block**: an admin Block rule (`DOMAIN_RULE`), an enabled `BLOCK` source (`BLOCKLIST`, the source name), Spamhaus DBL phish, malware or botnet (`BLOCKLIST`, `Spamhaus DBL: <type>`), or a domain younger than 30 days with a lookalike or login-lure heuristic (`SCAM_SIGNALS`).
  3. **Flag**: a lookalike plus a lure on an older domain, an enabled `FLAG` source, Spamhaus spam or an abused legitimate domain, a domain younger than 30 days, or any other `SuspiciousUrlService` reason.
  4. Otherwise clean. An unknown domain age never counts. A blank destination is clean; a non-blank one whose host can't be read is a Flag (`destination host could not be read`), which only the nightly scan and redirect hops can meet, because creating such a link fails `checkIfLongUrlIsValid` first. Safe Browsing and the shortener refusals run whatever the verdict.
- On create or a destination change, a Block → 400 "This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us." (the list is never named); a Flag saves the link and opens a review entry with its reasons.
- **Domain age** (`DefaultDomainAgeService`): RDAP through the IANA bootstrap registry for the registry domain, 3 s timeout; failures and registries without dates (`.de`, `.hr`) are unknown. **Spamhaus DBL** (`DefaultDomainReputationService`): only with `SPAMHAUS_DQS_KEY`, `<name>.<key>.dbl.dq.spamhaus.net` for the host and its registrable domain, 2 s timeout; on create and nightly, never on a click.
- **Blocklist sources** (`blocklist_source`, managed in the dashboard; nothing depends on HaGeZi). Kind `DOMAINS`: plain, hosts (`0.0.0.0 d`), adblock (`||d^`) and wildcard (`*.d`) lines; `@@` exceptions and full-URL lines are skipped. Kind `LINKS`: one `http(s)://` link per line, or a CSV's `url` column; keys drop the scheme, default port and fragment (`LinkKeys`), percent-encode raw spaces, non-ASCII and `"<>{}|^` in the path and query with `RedirectLocations.percentEncoded` (so a raw and an encoded spelling are one key, on the feed side and the link side alike), and match with or without the query. Downloads: https and public addresses only (re-checked on every hop), the main URL then up to 3 fallbacks in order, ETag/Last-Modified sent only to `lastSuccessUrl`, caps of 100 MB and 5 M entries. Zero entries, or fewer than 90 % of the count a header declares, fail. **Shrink guard**: when the list declares no count, a scheduled refresh (`refreshDue`, `reloadMissing`) that reads fewer than half of a stored `domainCount` of 1,000 or more, while that set is still in Redis, fails with "The new download has N entries, less than half of the M loaded before. Kept the old list." (the next URL is tried); Refresh now skips the guard, so a list that really shrank can be accepted. A failure keeps the old set and records `lastError` (every failed attempt, even when a fallback then succeeded). After the download, `BlocklistRefresher` detaches the source it started with and re-reads it, so the re-read hits the database even inside the Refresh now request; a source disabled, deleted or switched to another kind meanwhile keeps that change, its Redis set is not re-created and the download is dropped, and the result is saved on the re-read row, so another admin's change made during the download stays. A source URL can carry an access key (URLhaus Auth-Key), so download log lines name the source and the attempt (`main`, `fallback 1`), never the URL, and the audit details of a source create or update leave out `url` and `fallbackUrls`. **Memory guard**: a load that would take Redis past 80 % of `maxmemory`, or past `app.blocklists.max-redis-mb` across all sets when there is no `maxmemory` (estimated at 100 bytes per domain, 200 per link), is refused.
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
  - `analytics:salt:<yyyy-MM-dd>` and `analytics:visitors:<urlId>:<yyyy-MM-dd>`: the day's salt and each link's visitor set, both expiring at the next midnight in `app.analytics.zone` plus 4 hours (see Click analytics)
- **Redirect chains** (`RedirectChainService`): after a create or a destination change (`@Async("redirectCheckExecutor")`, 2 threads and a 200-check queue, once the save has committed; a full queue is logged and left to the nightly), and in the nightly scan for up to 500 active links whose `chainCheckedAt` is null or over 7 days old, the destination's HTTP redirects are followed: at most 5 hops in 5 s, GET with headers only, http or https, each hop through the public-address guard (a refused destination is a fetch error; a refused later hop ends the chain unrequested). A `Location` is read like a browser does before the guard sees it (`RedirectLocations`: UTF-8 bytes, `\` as `/`, space, non-ASCII and `"<>{}|^` percent-encoded, IDN host to ASCII). Every hop after the destination goes through `LinkCheckService.check` and Safe Browsing and the worst hop wins: a block blocks the link (`via redirect to <host>`), a flag or scam signals queue it (`redirects to <host>: <reason>`), unless the link was repointed meanwhile. `finalUrl` and `chainCheckedAt` are stamped with targeted updates; a fetch error stamps only `chainCheckedAt`, and a Safe Browsing outage stamps nothing (the nightly skips or stops its redirect checks) so a later run retries. The fetcher is the `RedirectFollower` bean (not a second `PublicHttpFetcher` bean, which would break by-type injection).
- **Executors** (`AsyncExecutorConfiguration`): declaring `redirectCheckExecutor` switches off Boot's own `applicationTaskExecutor` and its `@Async` default, so the configuration declares `applicationTaskExecutor` itself from Boot's `ThreadPoolTaskExecutorBuilder` (still `spring.task.execution.*`), also named `taskExecutor` so unqualified `@Async` (emails, `reloadMissing`) and click recording keep using it. `redirectCheckExecutor` (threads `redirect-check-1`, `-2`) waits up to 15 s on shutdown for running checks. `applicationTaskExecutor` (unbounded queue) waits up to 10 s on shutdown for its running and queued tasks (`spring.task.execution.shutdown.await-termination` and `await-termination-period` in `application.yaml`, which Boot's builder applies), so the clicks still queued when a redeploy stops the container are recorded; Boot's default would cancel the queue and interrupt the running ones. The wait runs when the beans are destroyed, after `LettuceConnectionFactory` has stopped (lifecycle phase 0) but before the connection pool closes, so a click recorded then takes the Redis-down path and counts as a new unique visitor.

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
- `GET /users/me/export` (GDPR JSON: profile + urls + api keys + emails); `DELETE /users/me` (hard delete; clears reset/verification/recovery tokens, urls, api keys).
- URL/API-key responses carry a `status` reason (ACTIVE / EXPIRED / LIMIT_REACHED / REVOKED / DEACTIVATED / BLOCKED).

### ReservedAlias (`reserved_alias` table, migration `027-admin-stats-and-reserved-aliases.xml`)
- `ID`, `ALIAS` (≤ 32, unique constraint `UX_RESERVED_ALIAS_ALIAS`), `REASON` (≤ 200), `CREATED_AT`, `CREATED_BY` (the admin's email), all `NOT NULL`. Aliases are stored trimmed and lower-cased, so the plain unique constraint is the case-insensitive one (a `lower()` index isn't portable Liquibase).
- `ReservedAliasService.isReserved` is the one place that answers "is this alias reserved": exact and case-insensitive after trimming, against the built-in words and the table. The built-in words are the old hardcoded list, `actuator`, `api`, `auth`, `error`, `login`, `logout`, `mcp`, `oauth2`, `swagger-ui`, `v3` and `webjars`, listed with the reason "Used by hrva.cc itself"; they are never stored and can't be removed. A custom entry is one `existsByAlias` lookup on the unique index, with no in-memory cache, so a reservation applies at once.
- Every create path checks it: a custom alias on the web, with an API key, in bulk or through the signed-in MCP `shorten_url` goes through `checkIfShortUrlIsReserved` → 409 `This short URL is reserved. Try another.` (the admin's reason never leaves the admin endpoints), and random generation (`nextFreeShortUrl`, also for anonymous links) tries up to 10 candidates, skipping reserved and taken ones, then → 409 `Couldn't pick a free short URL. Try again.` Aliases can't be edited (`UrlUpdateDto` has no `shortUrl`); an alias edit, if ever added, must call the same check.
- Reserving refuses a duplicate, a built-in word or a lost insert race (`DataIntegrityViolationException`) with 409 `This alias is already reserved.` The alias is validated like a custom alias: the FE trims it before sending, and the API refuses one with spaces with the pattern message on purpose, as it does for a custom alias.
- `inUse` lists the links that already hold the alias, in any case (`UrlRepository.findByLowerShortUrlIn`, one query for the whole list). Reserving such an alias leaves those links working; nothing is deactivated.

### Link creation channel (`url.created_via`, migration 027)
- A nullable `VARCHAR(20)` holding `CreatedVia` by name: `WEB`, `API_KEY`, `BULK`, `MCP`, `ANONYMOUS_WEB`, `ANONYMOUS_MCP`. Links created before 027 keep null, shown as `unknown`; there is no backfill, not even from the `ai` tag.
- The callers pass the signed-in value: `POST /urls` is `WEB` (`API_KEY` with an `X-Api-Key`), `/urls/bulk` is `BULK` with or without a key, `shorten_url` on `/mcp` and `/mcp/anon` is `MCP`. `saveUrlRouting` stores `createdVia.anonymous()` when nobody is signed in, so `WEB` becomes `ANONYMOUS_WEB` and `MCP` becomes `ANONYMOUS_MCP` (`/mcp/anon` ignores tokens). A reused anonymous link keeps its own value. The Links tab stacks the links created per day by it.

### Unique short URLs (`UX_URL_SHORT_URL`, migration 027)
- `Url.shortUrl` was always `@Column(unique = true)`, but Liquibase never created the index. `027-url-short-url-unique` creates the unique index `UX_URL_SHORT_URL` on `URL(SHORT_URL)` (case as stored) behind a `sqlCheck` precondition with `onFail="MARK_RAN"`: a database that already holds duplicate short URLs marks it ran and skips the index instead of failing Liquibase, and with it the startup of the only container. Its rollback is `drop index if exists`, so it also rolls back where it was skipped.
- `ShortUrlUniqueIndex.enforced()` reads that changeset's `exectype` from `databasechangelog` (`EXECUTED` → true; no row or a database error → false). When it isn't enforced, startup logs once `short_url uniqueness not enforced: duplicate short URLs exist, so 027-url-short-url-unique was skipped`, and the System tab shows `database.shortUrlUnique: false`.
- A create that loses an alias race to a concurrent one now fails on the index: `insertNewLink` saves and flushes (`Url` ids come from a sequence, so a plain `save` would only insert at commit), and a `DataIntegrityViolationException` that `ShortUrlUniqueIndex.violatedBy` matches to `UX_URL_SHORT_URL` becomes 409, `This short URL is already taken. Try another.` for a custom alias or `Couldn't pick a free short URL. Try again.` for a generated one, instead of a 500. Any other integrity violation is rethrown. The flush runs before the link is cached, so the request that lost never caches its destination under the winner's alias.
- 027 has nine changesets: the five range indexes (`IDX_LINK_DAILY_STATS_STAT_DATE`, `IDX_LINK_DAILY_BREAKDOWN_DATE_DIMENSION`, `IDX_URL_CREATE_DATE`, `IDX_USER_ACCOUNT_CREATE_DATE`, `IDX_LINK_REVIEW_FLAGGED_AT`), `027-url-created-via`, `027-url-short-url-unique`, `027-reserved-alias` and `027-url-long-url-length` (`URL.LONG_URL` from `VARCHAR(255)` to `VARCHAR(2048)`, still `NOT NULL`; its rollback is empty, because a wider column is harmless and narrowing it would fail on longer rows).

## Cache Architecture
- **Cache name**: `urls`
- **TTL**: 1 hour (configurable in `AppCacheConfiguration`), which bounds how long a stale entry can outlive a missed eviction, as a cached hit never rereads the block fields
- **Used by**: `redirectResultUrl` (redirect) and `peekUrlByShortUrl` (peek) — both use manual `CacheManager` access
- **Validation on cache hit**: `isUrlRedirectValid()` checks active, expirationDate, visitLimit/visits before serving
- **Eviction**: `@CacheEvict` on revokeUrl, activateUrl, updateUrl (by key), deleteUrl (all entries). A moderation block or reactivation evicts once it commits and again 2 s later on the scheduler (`TaskScheduler`, fail-soft), so a click that read the row just before the commit can't leave the old answer cached
- **Warmup**: `CacheWarmup` loads top 20 most visited, most recent, and recently accessed URLs on startup
- **Priming**: New URLs cached immediately on creation via `cacheUrlResponse()`
- **Graceful degradation**: `CacheErrorHandler` logs and falls through to DB on Redis failure
- **Statistics**: on (`spring.cache.cache-names: urls` and `spring.cache.redis.enable-statistics: true` in `application.yaml`; Boot builds the `RedisCacheManager`, the app declares only its `RedisCacheConfiguration`). `RedisCache.getStatistics()` counts hits and misses in memory since the last restart, for the link cache hit ratio on the System tab and the Health page (see Admin stats).

## Click analytics
Daily per-link counts with no raw IP, full User-Agent, full Referer or salt ever stored in Postgres, logged, put in a metric or returned.
- **A click** is a request that sends someone on to the destination: the redirect `/{short}` (from the database or a cache hit), `GET /api/v1/urls/{short}` for a link without a password, and a successful `/unlock`. `/peek`, `/preview`, `/qr` and the admin views are not clicks, and a click the click check refuses (`reason=blocked`) records nothing. A password-protected link's `GET /urls/{short}` records nothing; its click is the unlock.
- **Recording**: the controllers build a `ClickRequest` with `ClickRequestResolver` (the IP from `ClientIpResolver`, `User-Agent`, `Referer`, a single `CF-IPCountry`; `toString()` redacts), and `DefaultUrlService` hands it to `ClickAnalyticsService.recordClick(urlId, shortUrl, click)`. The recorder takes the day on the request thread and does the rest on `applicationTaskExecutor`, which finishes its queue on shutdown (see Executors). It never throws; each write is fail-soft and a failure logs the link id, the step and the exception class only.
- **Derived and stored** (the full User-Agent and Referer are dropped after classification):
  - day, and bot or human
  - referrer: the host lowercased without `www.`, `direct` (no header), `unknown` (unlock), `app:<package>` (`android-app://`), the configured server host (hrva.cc in production) for the server and frontend hosts, `other` (unparsable)
  - country: from `CF-IPCountry` only, never from the IP: ISO alpha-2 upper case, `unknown` (missing or `XX`), `tor` (`T1`)
  - device `mobile`, `tablet`, `desktop`, `unknown`; browser `chrome`, `safari`, `firefox`, `edge`, `samsung`, `opera`, `in-app`, `other`; os `ios`, `android`, `windows`, `macos`, `linux`, `chromeos`, `other`
- **Counting rules**:
  - A bot (`UserAgentClassifier`: a blank User-Agent, a named bot, link-preview fetcher or HTTP library (among them Outlook desktop's `Microsoft Office/` probe, which fetches a clicked link before it opens the browser), or the generic `bot`, `crawler`, `spider`, `preview` once the phone brand `cubot` is removed) adds one `BOT_CLICKS` and nothing else: no breakdowns, no unique visitor, no `Url.visits`, no `lastAccessed`.
  - On a visit-limited link (`visitLimit > 0`) a bot never gets the destination: the redirect (database or cache hit) goes to `reason=missing`, `GET /urls/{short}` and `/unlock` answer 404 `URL doesn't exist`. The request is still recorded, as a bot click. The check runs on the request thread: on the redirect and `/unlock` it runs before the click check, on `GET /urls/{short}` the click check runs first. A classifier failure counts as human. Links without a limit redirect bots as before.
  - A human click adds one `CLICKS` and one breakdown row per dimension. Unlock clicks record the referrer as `unknown`, since the unlock comes from the frontend.
- **Unique visitors** (`UniqueVisitorCounter`): `HMAC-SHA256(daySalt, ip + "\n" + userAgent + "\n" + urlId)`, its first 16 bytes as 22 Base64url characters, added to the link's set for the day. A new member adds one `UNIQUE_VISITORS` plus `recordVisit` (`visits` + 1 and `lastAccessed`, active links only) and `deactivateAtVisitLimit`, which evicts the cached entry when it switches the link off. `Url.visits` is therefore daily unique human visitors, summed.
  - `analytics:salt:<yyyy-MM-dd>`: Base64 of 32 `SecureRandom` bytes, created with set-if-absent. `analytics:visitors:<urlId>:<yyyy-MM-dd>`: the set. Both expire at the next midnight in the zone plus 4 hours; every write sets the expiry, so no analytics key lives without one.
  - Redis down, or a failing check → a new visitor, so a visit-limited link closes early rather than late (one warn line, without the IP). A lost salt is recreated and that day over-counts once.
- **Tables** (`026-privacy-first-analytics.xml`, `LinkStatsRepository`, deleted with the link): `LINK_DAILY_STATS(URL_ID, STAT_DATE, CLICKS, UNIQUE_VISITORS, BOT_CLICKS)` and `LINK_DAILY_BREAKDOWN(URL_ID, STAT_DATE, DIMENSION, DIMENSION_VALUE, CLICKS)`, `DIMENSION` one of `REFERRER`, `COUNTRY`, `DEVICE`, `BROWSER`, `OS`. 026 also drops the old `ip_address` table and its sequence in two changesets, `026-drop-ip-address` and `026-drop-ip-address-seq` (no rollback), each behind a `tableExists` or `sequenceExists` precondition with `onFail="MARK_RAN"`: a database where either is already gone marks that changeset ran instead of failing Liquibase, and with it the startup of the only container. `table/ip_address.xml` stays in the changelog, so a fresh database creates both and 026 drops them.
  - Upserts are standard SQL `MERGE … USING (VALUES …) AS s(…)` with every parameter wrapped in `CAST(:p AS <type>)`, because Postgres can't type bare parameters inside `VALUES`. Never `ON CONFLICT` or H2's `MERGE … KEY`: the same SQL runs on H2 in the tests and on Postgres 15 or newer. A duplicate-key race is retried once.
  - Caps: at most 50 named values per dimension per link per day; a further new value, or one over 255 characters, is stored as `other`. The per-link view shows the top 10 named values per dimension plus one `other` (stored `other` plus the rest), left out when 0.
- **Reads** (`DefaultAnalyticsService`, for `GET /api/v1/analytics/urls/{id}`, MCP `link_stats` and `GET /api/v1/analytics/overview`): `days` 7, 30, 90 or 365 (`AnalyticsRange`, default 30; anything else → 400 `days must be 7, 30, 90 or 365`, which the service checks before it reads the caller or the link). The range ends today in the analytics zone and starts `days − 1` days earlier. `findDaily` returns only the stored days and the service zero-fills the rest, so `daily` always has `days` entries; the totals are their sums. `findBreakdownTotals` sums each value over the range, clicks descending then value ascending; the service keeps the top 10 named values and appends `other` last. The summary has the destination host only, never the full URL. The overview sums the caller's links per day (`findOwnerDaily`) and lists the top 10 links by clicks in the range (`findOwnerTopLinks`: clicks descending then link id, bot-only links left out), each with the short URL and destination host from the caller's own links; its unique visitors are a sum over links and days, so one person on two links counts twice. `IDX_URL_OWNER_ID` (026) indexes `URL.OWNER_ID` for these owner-scoped reads, as Postgres doesn't index a foreign key column by itself.
- **Day**: `app.analytics.zone` (`APP_ANALYTICS_ZONE`, default `Europe/Zagreb`; the container runs in UTC), taken by `AnalyticsDays` on the request thread when the click arrives. The salt and set expiry follow the same day boundary.
- **Write budget per human click**: one stats `MERGE`, one 5-row breakdown `MERGE` and one read for the cap; a new unique visitor adds `recordVisit` and `deactivateAtVisitLimit`. A bot click is one statement. Counts go straight to Postgres, never buffered in memory (a redeploy would lose them).
- **No IP logging**: click-path log lines name the short code or link id only.
- **Never cache redirects**: every click has to reach the backend through Cloudflare (`cf-cache-status: DYNAMIC`), or analytics silently undercounts.

## Admin stats
Site-wide aggregates for the FE stats page (`/admin/dashboard`), one endpoint per tab (`GET /api/v1/admin/stats/{traffic|links|users|safety|system}`, see Key Endpoints), each loaded on its own. `AdminStatsController` → `DefaultAdminStatsService`, which checks `days` and builds the `StatsWindow` → one collector per tab in `service/stats/` → one JDBC repository per tab in `repository/stats/`.
- **Range**: `days` 7, 30, 90 or 365 (default 30) through `AnalyticsRange.fromDays`, the same check and 400 as the analytics endpoints. The window ends today in `app.analytics.zone` and starts `days − 1` days earlier, and every daily series has exactly `days` entries, zero-filled. The System tab checks `days` too and uses it only for the emails series.
- **Days of stored timestamps** (`StatsDays`): entity timestamps are `LocalDateTime` in the JVM zone (UTC in the container). `window()` turns the range into stored-time bounds (`storedStartOf(from)` up to `storedStartOf(to + 1 day)`), `dayOfStored` puts a stored time on its analytics-zone day, and `storedNow()` is the current time in the stored zone. "Now" figures bind `storedNow()` as a parameter (`:now`, `:today`), never the database's `current_timestamp`, so a database session zone can't skew them and a fixed clock can test them.
- **Hour buckets**: links created, sign-ups, reviews opened, blocks and emails are counted per stored date and hour in SQL (`HourlyCountRepository`: `cast(ts as date)`, `extract(hour from ts)` and a key, one `HourlySeries` per chart), and `DailyBuckets` assigns each hour to its analytics-zone day, so these series use the same days as the click series. That relies on the zone's offset being whole hours, as Europe/Zagreb's is. A series lists its known keys first, then any other key that turns up. A key is never null (`DailyBuckets` would throw): a nullable key column is wrapped in `coalesce(…, 'unknown')` (`created_via`, `block_reason`) or `coalesce(auth_provider, 'local')`.
- **Traffic** (`link_daily_stats`, `link_daily_breakdown`, `url`): clicks, unique visitors and bot clicks in range, the daily series, clicks on anonymous and on account links, the top 10 links by clicks (short URL, destination host, owner email or null; bot-only links left out), the site-wide breakdowns (top 10 named values plus `other`, as per link) and all-time `sum(url.visits)`. Unique visitors are **per link per day, added up**: the visitor hash includes the link and the day, so no site-wide unique count exists, and one person on two links or on two days counts twice.
- **Links** (`url`, `url_tags`): links created per day stacked by `created_via` (the six values plus `unknown`); created in range: total, account, anonymous and tagged `ai`; now: total, active, inactive (total − active), expired (`expiration_date` before now, active or not), password-protected, with a visit limit, with an expiry date, and the blocked links by `block_reason`; the top 10 tags; the top 10 destination hosts of active links (`DomainNames.hostOf` in Java over every active `long_url`, fine at today's size); and the newest 10 links (`create_date` then id, descending, with owner and channel).
- **Users** (`user_account`, authorities, `api_key`, the OAuth tables): sign-ups per day stacked by sign-in method (`coalesce(auth_provider, 'local')`: `local`, `google`, `github`); now: total, active, deactivated (total − active), admins, email verified, 2FA on, and by provider; signed in within the last 7 and 30 days (`last_login`); API keys: active, expired and the sum of `api_calls_used`, where a key on its expiry day already counts as expired (`expiration_date <= :today` on the `DATE` column), as `DefaultApiKeyValidator` and `ApiKeyResponse` see it; MCP "connected now": the distinct `principal_name`s in `oauth2_authorization` with an access or refresh token unexpired at `:now` (current state, as the cleanup job prunes expired rows), and the count of `oauth2_registered_client`.
- **Safety** (`link_review`, `url.block_reason` and `blocked_at`, `domain_rule`, the blocklist sources, `ScammerSignalService`): reviews opened per day as `SYSTEM` or `VISITOR` by the entry's current `system_flagged`; the open reviews and the visitor reports among them; reviews resolved in range by resolution, with the median and p90 minutes to resolve (nearest rank; null without resolved reviews; a negative time counts as 0); links blocked per day by `block_reason`, which counts links still blocked by their `blocked_at`, since unblocking clears it; the Block and Allow rule counts; the possible scammer count; and each source's name, action, enabled flag, entry count, memory, last success and last error. The last error is redacted: the source's own URL, fallbacks and last success URL (with and without the scheme) and then any other `http(s)://` address become `[url]`, because a source URL can carry an access key.
- **System**: the app version and JVM start time, uptime, Java version, active profiles and JVM heap; per redirect source (`cache`, `db`) the `redirect.duration` count, mean and max (count and mean since the last restart, Micrometer's max only over the last ~2 minutes; no percentiles are published); the `http.server.requests` count, all and `SERVER_ERROR`; the link cache's hits, misses and hit ratio (null without lookups); DB and Redis health; Redis used and max memory and the key counts; the database product, version as `major.minor` from `DatabaseMetaData` (for example `18.6`), size (`pg_database_size`, Postgres only, null on H2) and `shortUrlUnique` (see Unique short URLs); and emails per day by status (`SENT`, `FAILED`, `SENDING`, `RESENT`).
- **Health**: `HealthStatuses.status("db" | "redis")` asks `HealthEndpoint.healthForPath` through an `ObjectProvider`, not `HealthContributorRegistry`: with `reactor-core` on the classpath Boot registers the reactive `redisHealthContributor` and the blocking one backs off, so the blocking registry has no `redis`, while `HealthEndpoint` also consults the reactive one. A missing endpoint, a null result or any exception → `UNKNOWN`. When Redis is `DOWN` or `OUT_OF_SERVICE`, the System tab skips the Redis memory and key lookups (all three fields null) instead of waiting out more timeouts; with `UNKNOWN` it still tries, and each lookup is fail-soft (a failure → null and one warn line).
- **Redis keys** (`RedisKeyCounter`): `SCAN` in batches of 1,000, never `KEYS`, counting the prefixes `blocklist:`, `rate-limit:`, `analytics:`, `rdap:`, `dbl:`, `mcp:connect:` and `urls::` (the link cache), plus `other` and `scanned`. It stops after 100,000 keys with `capped: true`, and the counts are then lower bounds.
- **Cache hit ratio**: `RedisCache.getStatistics()` of the `urls` cache, which needs the cache statistics on (see Cache Architecture).
- **App version** (`AppVersion`): `releases[0].version` of `src/main/resources/changelog.json` without its leading `v`, read once at startup; an unreadable file or a missing version → `unknown`. The public changelog is the version of record (`pom.xml` stays at 1.4.2), so the newest release there is the version the System tab and the Health page show.
- **Legacy `GET /api/v1/admin/stats`** keeps its JSON shape for the Health page and reuses `RuntimeMetrics`, `HealthStatuses` and `AppVersion`: `databaseActive` and `cacheActive` are true only for `UP`, the redirect average and max combine both sources, the cache hit ratio is a percentage or `—`, and `recentUrls` are the newest 10 links with their owners.
- **No IPs**: no stats query, response or log line holds an IP address, a User-Agent or a referrer URL; the stats come only from the daily analytics tables and entity counts. The one exception is the login-throttling panel the stats page shows, the existing `GET /api/v1/admin/login-attempts` (IP → attempt count, in memory for a day), unchanged.

## Changelog
Every user-facing change, in this repo or in URL-Shortener-FE, adds an entry to the public changelog in the same push: `src/main/resources/changelog.json`, which the FE `/changelog` page renders via `GET /api/changelog`, and its Markdown twin `CHANGELOG.md`, kept item for item in sync. Add to the newest release, or start a new version and move `"badge": "Latest"` onto it. Write plain user-facing English, put admin-only features in an "Admin" section, and leave out internals (dependency bumps, refactors, test counts). The page currently ignores `icon`, but keep using names from the existing entries.

## CI / Deploy
- `.github/workflows/build.yml`: `mvn clean package` → Docker build/push to `ghcr.io`
- Multi-arch: `linux/amd64,linux/arm64`
- Pushes on `main` branch. PRs only build (no push).
- Portainer env vars: `OAUTH2_GOOGLE_CLIENT_ID`, `OAUTH2_GOOGLE_CLIENT_SECRET`, `OAUTH2_GITHUB_CLIENT_ID`, `OAUTH2_GITHUB_CLIENT_SECRET`, `APP_MCP_SIGNING_KEY` (required), `APP_ANONYMOUS_URL_CREATION_ENABLED` (optional), `SPAMHAUS_DQS_KEY` (optional; the Spamhaus DBL lookups are off without it), `DISCORD_WEBHOOK_URL` (optional; the Discord admin alerts are off without it).
- Before the first MCP connector deploy: the production checklist in `README.md`.
