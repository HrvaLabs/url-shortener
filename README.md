# [URL-Shortener](https://app.hrva.cc)

## Overview
hrva.cc is a privacy-first URL shortener with built-in link safety. This repository is the backend: a Spring Boot API that creates and redirects short links, checks them for phishing and malware, and counts clicks without tracking anyone. The frontend lives in [HrvaLabs/URL-Shortener-FE](https://github.com/HrvaLabs/URL-Shortener-FE).

The changelog is shown at [app.hrva.cc/changelog](https://app.hrva.cc/changelog) and kept in [CHANGELOG.md](CHANGELOG.md). [AGENTS.md](AGENTS.md) documents the architecture, commands, endpoints and configuration.

## Features

### Links
- Random short links, or a custom alias when you're signed in. Links can also be made without an account, with a stricter rate limit.
- Expiry dates, visit limits, password protection and tags.
- Bulk creation, and an API with API keys.
- A campaign tag (UTM) builder.
- QR codes that count their scans.

### Bio pages
- One page at `hrva.cc/@yourname`, with a button for each of your short links.
- Views are counted per day, and changing your handle keeps the old one held for 30 days.

### Safety
- Every new link, every click and a nightly rescan go through:
  - Google Safe Browsing
  - admin-managed blocklists (HaGeZi, OpenPhish, PhishTank, Phishing.Database, URLhaus)
  - Spamhaus DBL
  - domain age (RDAP)
  - redirect-chain and look-alike checks
- Visitors can report links and bio pages.
- Suspicious links go to a review queue rather than being blocked blindly.
- A phishing-wave alert warns when many new links point at the same new domain.

### Privacy-first analytics
- Clicks are stored only as daily totals per link: visitors, referrers, devices, browsers, countries, and QR scans against other clicks.
- Unique visitors come from a one-way hash under a key that changes every day, kept only in Redis.
- Bots are counted apart.
- The public [hrva.cc in numbers](https://app.hrva.cc/stats) page shows site-wide totals.

### Accounts
- Sign in with email and password, Google, or a passkey or security key.
- Two-step verification works with every sign-in method, using an authenticator app or a passkey, with recovery codes.
- You can export your data and delete your account.

### AI connector
- AI apps such as Claude use hrva.cc over MCP at `https://hrva.cc/mcp`, signing in with OAuth 2.1.

### Admin
- Pages for stats, the review queue, moderation, blocklists, domain rules, reserved aliases, bio pages and the audit log.
- Discord alerts, a weekly summary and a daily decisions email.

## Roadmap
- **Notifications for link owners:** opt-in alerts by Discord, Slack, Telegram, ntfy, a signed webhook or email. Nothing is sent without consent.
- **Custom domains:** short links on your own domain.

## Technologies Used
- **Backend:** Java 25, Spring Boot 4.1, Spring Security and Spring Authorization Server, Spring AI (MCP)
- **Database:** PostgreSQL with Liquibase migrations, and Redis
- **Frontend:** Next.js 16 on Cloudflare Workers (separate repository)
- **Deploy:** GitHub Actions builds the Docker image and triggers a Portainer stack webhook. The site runs behind Cloudflare.

## Running it
Build, test and run commands, local dev users and the end-to-end check (`scripts/mcp-e2e.sh`) are in [AGENTS.md](AGENTS.md). The short version: `docker compose -f docker/docker-compose.yml up -d` for Postgres and Redis, then `mvn spring-boot:run -Dspring-boot.run.profiles=local`. For tests, run `mvn -B test`.

## Production requirements
- **PostgreSQL 15 or newer** (the analytics counters use `MERGE`) and **Redis 6.2 or newer** (`GETDEL`, `SMISMEMBER`).
- **Environment variables:** the full list is in AGENTS.md (CI / Deploy, and MCP connector → Configuration). `APP_MCP_SIGNING_KEY` is required, and the app won't start without it. Generate a private RSA JWK and keep it out of git and logs:
  ```bash
  node -e "const {generateKeyPairSync, randomUUID} = require('node:crypto'); const {privateKey} = generateKeyPairSync('rsa', {modulusLength: 2048}); console.log(JSON.stringify({...privateKey.export({format: 'jwk'}), kid: randomUUID(), use: 'sig', alg: 'RS256'}))"
  ```
- **`APP_NOTIFICATIONS_KEY` is required** too, and the app won't start without it. It encrypts the notification channels' URLs and tokens and signs the unsubscribe links, so keep it next to the MCP signing key: losing it means everyone re-adds their channels. Generate 32 random bytes:
  ```bash
  openssl rand -base64 32
  ```
- **Cloudflare-only origin.** The per-network rate limits trust `CF-Connecting-IP`, and the country counts trust `CF-IPCountry`. The origin must therefore accept traffic only from Cloudflare, through a Cloudflare Tunnel or a firewall limited to [Cloudflare's IP ranges](https://www.cloudflare.com/ips/). Otherwise anyone can forge those headers.
- **Cloudflare rules for the MCP paths.** MCP clients aren't browsers, and a challenge breaks them:
  - Exempt `/mcp`, `/oauth2/*` and `/.well-known/*` from WAF managed challenges and Super Bot Fight Mode. If Bot Fight Mode on the Free plan challenges them, turn it off.
  - Don't cache `GET /oauth2/authorize` or `/.well-known/*` at the edge.
- **Smoke test after a deploy.** Expect issuer `https://hrva.cc`, resource `https://hrva.cc/mcp`, and 401 from `/mcp` without a token:
  ```bash
  curl -s https://hrva.cc/actuator/health
  curl -s https://hrva.cc/.well-known/oauth-authorization-server | jq '{issuer, registration_endpoint}'
  curl -s https://hrva.cc/.well-known/oauth-protected-resource/mcp | jq '{resource, authorization_servers}'
  curl -s -o /dev/null -w '%{http_code}\n' -X POST https://hrva.cc/mcp
  ```

## Contribution
We welcome contributions from the community!

## License
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contact
For questions, issues, or feature requests, please open an issue on GitHub or contact me directly via [email](mailto:url-shortener@hrva.cc).

Thank you for using URL-Shortener or just viewing this page!
