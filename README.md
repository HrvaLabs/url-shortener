# [URL-Shortener](https://app.hrva.cc)

## Overview
Welcome to the URL-Shortener project! This application allows you to shorten URLs efficiently and securely with additional features such as API keys, custom URLs, and comprehensive link management.

## Features
- **API Keys for External Access**: Generate API keys to access the URL shortening service programmatically.
- **Automatic Link Deactivation**: Links can automatically deactivate after a certain time or after being accessed by a set number of unique users.+
- **Custom Expiration Dates**: Allow users to set custom expiration dates for their shortened URLs.
- **Dashboard**: View all URLs, their access counts, and more in a user-friendly dashboard.
- **Custom Short URLs**: Logged-in users can create custom short URLs; non-logged-in users receive a randomly generated URL.
- **Google Safe Browsing Integration**: URLs are scanned using Google Safe Browsing to ensure they are not malicious.
- **URL Validation**: Regular users can validate URLs through an external site to ensure safety.
- **Login Security**: Accounts are locked after multiple failed login attempts to prevent unauthorized access.

## Roadmap
1. **SSO Logins**: Integrate single sign-on (SSO) options with GitHub, Google, etc.
2. **Frontend Redesign**: Overhaul the frontend with a new design for improved user experience.
3. **Backend Refactor**: Refactor the backend to Java 21, adhering to best practices.
4. **Easy Deploy**: Streamline the deployment process for ease of use.
5. **Caching Database**: Implement database caching to provide faster responses.
6. **Improved API Documentation**: Enhance the API documentation for better clarity and usability.

## Technologies Used
- **Backend**: Java, Spring Boot
- **Frontend**: Next
- **Database**: Postgres

## Upcoming Features Suggestions in Review
- **QR Code Generation**: Automatically generate QR codes for each shortened URL for easy sharing.
- **User Roles and Permissions**: Implement user roles (e.g., admin, editor, viewer) with different levels of access and control.
- **Two-Factor Authentication (2FA)**: Enhance security by adding two-factor authentication for user logins.
- **URL Categorization**: Allow users to categorize and tag their URLs for better organization.
- **Bulk URL Shortening**: Enable users to shorten multiple URLs at once through batch processing.
- **Link Preview**: Show a preview of the destination website before redirection.
- **Integration with Other Services**: Integrate with popular services like Slack, Discord, and Microsoft Teams for easier sharing and notifications.
- **Dark Mode**: Add a dark mode option for the dashboard to reduce eye strain and improve usability in low-light environments.

## Production Checklist: MCP Connector
Before the first deploy of the MCP connector (AI apps such as Claude using hrva.cc at `https://hrva.cc/mcp`). How it works: `AGENTS.md` → MCP connector.

1. **Signing key.** Generate a private RSA JWK and set it as the secret env var `APP_MCP_SIGNING_KEY`; the app doesn't start without it. Keep it out of git and logs, and never reuse the committed `local` profile key.
   ```bash
   node -e "const {generateKeyPairSync, randomUUID} = require('node:crypto'); const {privateKey} = generateKeyPairSync('rsa', {modulusLength: 2048}); console.log(JSON.stringify({...privateKey.export({format: 'jwk'}), kid: randomUUID(), use: 'sig', alg: 'RS256'}))"
   ```
2. **Cloudflare-only origin.** The per-IP limits (anonymous MCP links, app registrations, logins) trust the `CF-Connecting-IP` header, so the origin must accept traffic from Cloudflare only: a Cloudflare Tunnel, or a firewall that lets only [Cloudflare's IP ranges](https://www.cloudflare.com/ips/) reach the app. Otherwise anyone can forge the header.
3. **Redis 6.2 or newer.** The login bridge redeems one-time codes with `GETDEL`.
4. **Anonymous MCP.** Decide `APP_ANONYMOUS_URL_CREATION_ENABLED` (`app.anonymous-url-creation-enabled`, `false` in `application.yaml`). While it is off, `/mcp/anon` refuses every call ("Sign in to create short URLs"), yet the frontend `/mcp` page advertises it. The same flag controls anonymous links on the website.
5. **The `mcp` alias.** `mcp` is now reserved and `/mcp` no longer redirects. Run `select short_url from url where lower(short_url) = 'mcp';` and resolve any row before deploying.
6. **Merge `fix/long-url-validation` first.** It gives the tools specific errors for malformed URLs instead of a generic one.
7. **Deploy the backend, then the frontend** `feature/mcp-connector`: the `/connect` page, Settings → Connected apps, and the `/mcp` page.
8. **Smoke test.** Expect issuer `https://hrva.cc`, resource `https://hrva.cc/mcp`, and 401 from `/mcp` without a token.
   ```bash
   curl -s https://hrva.cc/.well-known/oauth-authorization-server | jq '{issuer, registration_endpoint}'
   curl -s https://hrva.cc/.well-known/oauth-protected-resource/mcp | jq '{resource, authorization_servers}'
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://hrva.cc/mcp
   ```
9. **Sign in once from each Claude client.** In Claude Code, `claude mcp add --transport http hrva https://hrva.cc/mcp`, then `/mcp` → authenticate. In the Claude app, Settings → Connectors → Add custom connector with `https://hrva.cc/mcp`.

## Contribution
We welcome contributions from the community!

## License
This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contact
For questions, issues, or feature requests, please open an issue on GitHub or contact me directly via [email](mailto:url-shortener@hrva.cc).

Thank you for using URL-Shortener or just viewing this page!
