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
- Docker: `Dockerfile` copies `target/*.jar`, multi-arch CI build

## Local Dev Users
`LocalDataInitializer` (only with `local` profile) bootstraps two email-verified local accounts:

| Email | Password | Role | Slots |
|-------|----------|------|-------|
| `admin@localhost` | `admin123` | ROLE_ADMIN | 10 |
| `user@localhost` | `user123` | ROLE_USER | 4 |

Idempotent — safe on restart. Liquibase seeds `ROLE_USER` and `ROLE_ADMIN` authorities.

## Local Dependencies
- **Postgres** (`localhost:5432`, `root:root`, db `url_shortener`)
- **Redis** (`localhost:6379` — overridden in `application-local.yaml`, no longer needed to set manually)
- **MailHog** (`localhost:8025`, SMTP on `localhost:1025`)

## Testing
- `mvn test` (or `mvn -B verify`) — 427 tests, JUnit 5 + Mockito + AssertJ
- Mostly plain `@ExtendWith(MockitoExtension.class)` unit tests. The MCP connector's security tests are `@SpringBootTest` + MockMvc through the real filter chains: each class boots its own nested `TestApplication` on embedded H2 with a subset Liquibase changelog (`db/changelog/017-oauth2-authorization-server.xml` or `db/mcp-connector-test-changelog.xml`) and mocks the rest
- No `@ActiveProfiles`, no Testcontainers
- `src/test/` mirrors `src/main/` package structure

## Package Structure
```
cc.hrva.urlshortener
├── client/          # GoogleSafeBrowsingApi (external API client)
├── configuration/   # Beans, props, cache, security, web config, exception handler
├── controller/      # REST controllers
├── converter/       # DTO↔entity converters (Spring Converter interface)
├── dto/             # Request/response DTOs (records and classes)
├── exception/       # Standard-named exceptions (*NotFoundException, Invalid*Exception)
├── listener/        # Spring event listeners (auth success/failure)
├── mcp/             # MCP connector: authorization/, bridge/, tools/, anon/, apps/
├── model/           # JPA entities, codebook, enums
├── repository/      # Spring Data JPA repositories
├── security/        # JWT filter, token provider, auth entry point, ClientIpResolver
├── service/         # Interfaces + impl/ implementations
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
- **Exception → HTTP status mapping**: `UserNotFoundException`/`ApiKeyNotFoundException` → 404, `EmailExistsException`/`ShortUrlAlreadyExistsException` → 409, `NoAuthorizationException` → 403, `BadCredentialsException` → 401, `CommonException` (catch-all for all custom exceptions not handled above) → 400, everything else → 500.
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
| GET | `/api/v1/admin/audit-log` | ROLE_ADMIN | Paginated admin audit trail |
| GET | `/api/v1/admin/email-log` | ROLE_ADMIN | Paginated email delivery log |
| GET | `/api/v1/admin/login-attempts` | ROLE_ADMIN | IP → attempt count map |
| DELETE | `/api/v1/admin/login-attempts` | ROLE_ADMIN | Clear all rate limits |
| GET | `/api/changelog` | Public | Changelog JSON |
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
- **Redis caching**: `@Cacheable("urls")` on URL lookup (`peekUrlByShortUrl` only). 60-min TTL. `@CacheEvict` on mutations.
- **Graceful degradation**: `CacheErrorHandler` logs and falls through to DB when Redis is down — no 500s.
- **Read-only transactions**: `@Transactional(readOnly = true)` at class level on all services, overridden with `@Transactional` on write methods.
- **SafeBrowsing client**: Singleton bean (not built per-request). Graceful degradation on API error (returns `List.of()`).

## Security
- JWT in `Authorization: Bearer` header. Stateless sessions on the API chain; the MCP authorization server chain keeps a short HTTP session for the login bridge (see MCP connector).
- Login rate limiting: Guava cache, 20 attempts/day per IP, 1-day expiry.
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
| POST | `/mcp` | Bearer MCP access token | Signed-in MCP server, STATELESS protocol. Tools `list_links` (20 per page), `shorten_url` (always adds the tag `claude`), `link_stats` (owner only, even for admins). Run as the token's user. Other methods → 405 |
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
Redis fixed windows (`RedisFixedWindowRateLimiter`, key `rate-limit:<name>:<sha256 of the client network>`: the IPv4 address or the IPv6 /64, never a raw IP). Both fail closed when Redis is down.
- **`/mcp/anon` `shorten_url`**: 10 per 60 s per network (`mcp-anon`). Every attempt counts. The refusal is a tool error: "Too many anonymous links from this network. Try again in a minute, or connect https://hrva.cc/mcp to use your account."
- **`/oauth2/register`**: 20 per hour per network (`dcr`) → 429 with `Retry-After: 3600`; 503 when Redis is down.
- The client IP comes from `ClientIpResolver`, which trusts `CF-Connecting-IP` (see Security). That is safe only while the origin accepts traffic from Cloudflare alone.
- **Body size**: `McpRequestBodyLimitFilter` (a servlet filter before `DcrEmptyScopeFilter` and Spring Security) caps POST bodies on `/oauth2/register`, `/mcp` and `/mcp/anon` at 64 KB. A larger `Content-Length` → 413 `{"error":"request_too_large"}`; a body without a length (chunked) fails once it passes 64 KB: 413 on `/oauth2/register`, 400 "Invalid message format" from the MCP transport. `DcrEmptyScopeFilter` also reads at most 64 KB on its own.
- Redis must be 6.2 or newer (`GETDEL`).

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
- Unit tests: `mcp/tools/LinkToolsTest`, `McpToolResultsTest`, `mcp/anon/RateLimiterTest`, `McpMessageBodyLogFilterTest`, `McpRequestBodyLimitFilterTest`, `security/ClientIpResolverTest`, `configuration/properties/AppPropertiesTest`. Helpers: `mcp/McpAccessTokens`, `mcp/bridge/InMemoryRedis`.

### End-to-end check (`scripts/mcp-e2e.sh`)
Runs the whole flow against a throwaway stack: Postgres 18 and Redis 8 in disposable containers (`docker run --rm`, random `127.0.0.1` ports, label `hrva-mcp-e2e`) and the jar with the `local` profile on port 8091 (issuer `http://localhost:8091`). It never touches `docker/docker-compose.yml`, its `db` volume or ports 5432, 6379 and 8080, and a `trap` removes the containers, the app and the temp files on exit.
```bash
scripts/mcp-e2e.sh                        # builds the jar, then runs every step
MCP_E2E_SKIP_BUILD=1 scripts/mcp-e2e.sh   # reuses target/url-shortener-*.jar
MCP_E2E_PORT=8092 scripts/mcp-e2e.sh      # another app port
```
- Needs Docker, Java 25, Maven, Node (`npx`), `jq`, `curl` and `openssl`. It runs MCP Inspector CLI 2.11.0 (`MCP_E2E_INSPECTOR` overrides), with the Inspector's state in the temp dir.
- **curl** plays the client, browser and frontend: discovery, DCR, `/oauth2/authorize` → 302 to `/connect`, sign-in as `user@localhost` via `/api/v1/auth/login`, approve, continue, code → token with PKCE and `resource`, one refresh (rotation, replay refused), the connected-apps list, and a disconnect after which `/mcp` returns 401.
- **The Inspector CLI** signs in on its own (discovery, DCR, PKCE). `scripts/mcp-e2e/fake-browser/open` stands in for the browser and the frontend, so this step needs macOS. It then calls `tools/list`, `list_links`, `shorten_url` (the link must carry `claude`) and `link_stats`, and finally `/mcp/anon` `shorten_url` without a token.
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
- **URL expiry**: Daily at 8 AM, sends email notification for URLs expiring within 24 hours (configurable via `app.url-expiration-notification-hours`).
- **IP cleanup**: Every minute, deactivates and deletes old IP address records.
- **Reset tokens**: Every minute, deactivates and deletes expired password reset tokens.
- **URL deactivation**: Every minute, deactivates expired URLs.
- **API key deactivation**: Every hour, deactivates expired API keys.
- **User deactivation**: Daily at 10 AM, deactivates unused accounts after configured inactivity period.
- **MCP cleanup**: Daily at 03:30, deletes expired OAuth authorizations and DCR clients unused for 30 days (`DcrClientCleanupJob`).

## Config Profiles
- **`local`**: `localhost` Postgres/Redis/MailHog, hardcoded JWT secret, dev users auto-created, verbose SQL logging. MCP issuer `http://localhost:8080`, a committed dev-only signing key (not a secret; refused at startup outside `local`) and anonymous link creation on, so `/mcp/anon` works. Auto-imports `application-oauth.yaml` for SSO secrets.
- **`prod`**: Remote Postgres, no dev defaults. Secrets injected externally.
- **`oauth`**: Optional profile holding OAuth2 client credentials. Imported by `local` profile via `spring.config.import`.
- Default: `application.yaml` sets base values (cache TTL, URL length, API limits, etc.).
- Tests run without any profile; only the MCP `@SpringBootTest` classes start a (trimmed) application context.

## New Models (recently added)

### AuditLog (`audit_log` table, migration `008-audit-log.xml`)
- Tracks admin actions: performedBy, action, targetType, targetIdentifier, details, performedAt
- Auto-logged via `AdminAuditAspect` (AOP around admin controller methods)
- Endpoint: `GET /api/v1/admin/audit-log`

### EmailLog (`email_log` table, migration `009-email-log.xml`)
- Records email sending: recipient, subject, status (SENDING/SENT/FAILED), errorMessage, sentAt, createdAt
- Populated automatically by `tryToSendEmail` in `DefaultSendingEmailService`
- Endpoint: `GET /api/v1/admin/email-log`

### VerificationToken (`verification_token` table, migration `013-email-verification.xml`)
- Email verification on register; `user_account.email_verified` (existing users grandfathered `true`). OAuth users auto-verified.
- Flow: register → unverified + verification email; `POST /auth/verify-email/confirm` activates; `/resend` reissues.

### URL tags (`url_tags` table, migration `014-url-tags.xml`)
- `Url.tags` `@ElementCollection` of normalized strings (`TagNormalizer`: lowercase, ≤10 × ≤30 chars). Filter via `?tag=`; list via `GET /urls/tags`.

### TwoFactorRecoveryCode (`two_factor_recovery_code` table, migration `015-two-factor.xml`)
- 2FA recovery codes (bcrypt-hashed, one-time). `user_account.two_factor_enabled` / `two_factor_secret`.

### Account self-service
- `GET /users/me/export` (GDPR JSON: profile + urls + api keys + emails); `DELETE /users/me` (hard delete; clears reset/verification/recovery tokens, ip_address rows, urls, api keys).
- URL/API-key responses carry a `status` reason (ACTIVE / EXPIRED / LIMIT_REACHED / REVOKED / DEACTIVATED / BLOCKED).

## Cache Architecture
- **Cache name**: `urls`
- **TTL**: 7 days (configurable in `AppCacheConfiguration`)
- **Used by**: `redirectResultUrl` (redirect) and `peekUrlByShortUrl` (peek) — both use manual `CacheManager` access
- **Validation on cache hit**: `isUrlRedirectValid()` checks active, expirationDate, visitLimit/visits before serving
- **Eviction**: `@CacheEvict` on revokeUrl, activateUrl, updateUrl (by key), deleteUrl (all entries)
- **Warmup**: `CacheWarmup` loads top 20 most visited, most recent, and recently accessed URLs on startup
- **Priming**: New URLs cached immediately on creation via `cacheUrlResponse()`
- **Graceful degradation**: `CacheErrorHandler` logs and falls through to DB on Redis failure

## CI / Deploy
- `.github/workflows/build.yml`: `mvn clean package` → Docker build/push to `ghcr.io`
- Multi-arch: `linux/amd64,linux/arm64`
- Pushes on `main` branch. PRs only build (no push).
- Portainer env vars: `OAUTH2_GOOGLE_CLIENT_ID`, `OAUTH2_GOOGLE_CLIENT_SECRET`, `OAUTH2_GITHUB_CLIENT_ID`, `OAUTH2_GITHUB_CLIENT_SECRET`, `APP_MCP_SIGNING_KEY` (required), `APP_ANONYMOUS_URL_CREATION_ENABLED` (optional).
- Before the first MCP connector deploy: the production checklist in `README.md`.
