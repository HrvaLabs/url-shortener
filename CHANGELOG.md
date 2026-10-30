# Changelog

All notable changes to hrva.cc — the URL shortener.

---

## [v2.17] — 2026-10-29

Custom domains: apply for your own subdomain, like go.example.com, and make short links on it.

### 🔗 Links
- Apply for a custom domain in Settings, under Custom domains. Once it's approved, add one CNAME record and hrva.cc sets up the certificate for you.
- Pick your domain when you shorten a link. Aliases on your domain are your own: go.example.com/menu and hrva.cc/menu can both exist.
- Short links on your domain work like any other: stats, visit limits, passwords and expiry dates.

### 🔒 Privacy
- Visitors to your domain get the same privacy as visitors to hrva.cc. Cloudflare receives your domain's name to issue its certificate.

### 🔧 Admin
- A new Domains page: review applications, see every domain with its owner, links and clicks, turn domains off, retry, or remove them with their links moved or turned off.
- Cloudflare custom hostnames are created and deleted automatically after approval, never beyond the cap of 90, and checked against Cloudflare every night.
- Pending applications count in the review badge, and the stats page shows domains against the cap.

### 🌐 Site
- A new help page explains custom domains, with the exact DNS record for common providers.

---

## [v2.15.3] — 2026-10-29

Sign in with GitHub, verified email addresses for Google and GitHub sign-ins, and hrva.cc as an installable app.

### 🛡️ Security
- Sign in with GitHub. hrva.cc uses your GitHub account's verified primary email.
- Signing in with Google now needs a verified email address.
- When new sign-ups are paused, Google and GitHub can't create accounts either.
- Signing in with Google or GitHub to an account whose email address was never confirmed confirms it and turns off the password it was created with.
- If signing in with Google or GitHub fails, you come back to hrva.cc with a message instead of a bare error page.

### 🌐 Site
- hrva.cc can be installed as an app. On Android you can share a link to it from any app to shorten it.
- A Support section on the About page.

---

## [v2.15.2] — 2026-10-29

Admins can block the domains of several links at once.

### 🔧 Admin
- Block domains on All URLs blocks the domains of the ticked links at once, after showing how many active links on the site each one turns off.

---

## [v2.15.1] — 2026-10-29

Notification settings that are easier to find and fix, and faster bio pages.

### 🔔 Notifications
- Adding or editing a channel shows every problem at once instead of one at a time.
- Notifications now sits right below your profile and password in Settings, and a link marked "Destination looks dead" offers "Get an email when this happens" while dead-link emails are off.

### 🔗 Links
- Bio pages and their share images load faster: they're kept for up to 5 minutes, so saved changes can take up to 5 minutes to show, and your old handle can keep working for up to 5 minutes after you change it or delete your page. View page in the editor always shows the saved version.

---

## [v2.15] — 2026-10-29

Emails that match the site, and new pages: about, developers, a free QR code generator and UTM builder.

### 🌐 Site
- Emails have a new look that matches the site, easier to read on phones and in dark mode.
- New About page: who runs hrva.cc, how it is paid for and how to reach it.
- New page on how hrva.cc counts visits without tracking, with what it keeps, what it never stores and the source code to check.
- New developers page with an API quickstart in curl, JavaScript and Python, the request fields, errors and limits. The MCP page now has example prompts and says what your assistant can see.
- Free QR code generator at /qr: no account, PNG and SVG download, and an optional short link to count scans.
- Free UTM builder at /utm: tag any link without an account, copy it or shorten it.
- New link in bio page explaining bio pages, linked from the header and from the bottom of every bio page.
- The homepage API example now shows the real response code, 200 OK.

---

## [v2.14.1] — 2026-10-29

Clearer notification channel messages: a deleted Discord or Slack webhook says so, saved secrets keep a visible note, and a channel with no events ticked says it gets nothing.

### 🔔 Notifications
- If Discord or Slack no longer knows a channel's webhook, Send test and the channel say it may have been deleted and ask you to copy the webhook URL again.
- When you edit a channel, the note that a saved address or token is kept if you leave the field empty stays visible while you type.
- The channel dialog says when no event is ticked, so the channel won't get any messages yet.
- Pasting an ntfy topic URL into a webhook channel now calls it an ntfy topic URL.

---

## [v2.14] — 2026-10-29

Notifications you choose: dead-link checks, a weekly summary, and alerts to Discord, Slack, Telegram, ntfy, a webhook or email.

### 🔗 Links
- hrva.cc now checks every active link's destination each night. If it fails three different nights, with no working answer in between (the page is gone, or the website no longer exists), the link is marked "Destination looks dead" on your dashboard and in its stats.

### 🔔 Notifications
- New Notifications settings: get an email when a link stops working, a weekly summary every Monday, or a reminder before a link expires. Everything is off until you turn it on.
- Add channels for Discord, Slack, Telegram, ntfy or your own webhook (signed JSON), and pick which events each gets: a link stopped working, a link works again, a daily click summary or the weekly summary. Each channel has Send test and shows its last delivery.
- Expiry reminders are now opt-in. If you want them, turn on Expiry reminders in Settings, under Notifications.
- Every notification email has a one-click unsubscribe link.

### 🔒 Privacy
- Notifications carry counts, short links and destination hosts only, never visitors' IP addresses, browsers or referrers. Channel URLs and tokens are encrypted. After you save them, URLs and bot tokens are shown masked, and other tokens are never shown again.
- We no longer send a welcome email after you verify your address.

### 🔧 Admin
- All URLs can show only links whose destination looks dead, and the stats page counts them, plus notification opt-ins and channels per type.

### 🌐 Site
- A new help page explains notifications, channels and webhook signatures step by step.

---

## [v2.13.1] — 2026-10-27

Admins can delete expired and revoked API keys, logs are no longer kept forever, pages load faster, and three API key fixes.

### 🔑 API Keys
- Admins can delete expired and revoked API keys from All API keys.

### 🔒 Privacy
- Email logs are deleted after 90 days and the admin audit log after 12 months.

### 🐛 Fixes
- Deleting an API key that was already removed says so instead of asking you to revoke it.
- Keyboard focus stays in the API keys table after you revoke a key.
- Creating a link with an API key no longer undoes a revoke made at the same moment, and links created at once with one key all count.
- Pages load faster and no longer fail when many people visit at once.

---

## [v2.13] — 2026-10-27

A tidier header and footer, expired and revoked API keys you can delete, and short links guaranteed unique.

### 🌐 Site
- The header now has Bio pages, Developers, Help and Check a link; Blog and Changelog moved to the footer, and the stats page is listed as Public stats.

### 🔑 API Keys
- Expired and revoked API keys can be deleted, one at a time or all at once, and they're tucked away below your active keys.

### 🐛 Fixes
- Short links are now guaranteed unique in the database.
- On the report page, sending keeps keyboard focus on the Send button even after a double click.

---

## [v2.12] — 2026-10-27

Bio pages: one page with all your links, for your Instagram, TikTok or any other bio.

### 🔗 Links
- Bio pages: make a page at hrva.cc/@yourname with your name, a short bio, an emoji or initials avatar and a button for each of your short links. Pick a colour and a light, dark or automatic theme, reorder buttons by dragging or with the keyboard, hide one without deleting it, and see a live preview as you edit.
- Your bio keeps its line breaks, up to five lines, so each detail can go on its own line.
- If your bio page changed in another tab or window, saving an older copy doesn't overwrite it: the editor loads the latest version, keeps your unsaved changes and asks you to check them and save again.
- Every button is one of your own short links, so each click gets the same safety checks as any other link. A link that stops working, is blocked or is waiting for review disappears from your page, and the editor shows why.
- Share your page with a copy-link button or a QR code from the Bio page screen.
- When you share your page in a chat or on social media, its preview image shows your name, your @handle and your initials in your colour.

### 📊 Analytics
- A Bio page view on the Analytics page shows views per day and, for each button, clicks that came from hrva.cc pages and all clicks on its link.

### 🔒 Privacy
- Bio page views are counted once per visitor per day with the same daily-changing one-way hash as link statistics. No IP address or browser details are stored, and the page loads no third-party scripts, fonts or trackers.
- Search engines are asked not to list your page unless you turn on "Allow search engines to index this page".

### 🛡️ Link Safety
- Anyone can report a bio page with "Report this page" at the bottom of it, or on the Report a link page with hrva.cc/@name.
- Reports have a new category, "Pretends to be someone", for a link or bio page that impersonates a person or a business.

### 🔧 Admin
- Bio pages: search pages by handle, name or owner, hide a page with a reason, and change or revoke a handle. A revoked handle is added to the reserved aliases.
- Reports, and text that looks like impersonation or phishing, put a bio page in the review queue with Looks fine and Hide page. Discord hears about new bio page reviews and reports, and the daily decisions email counts them.
- The stats page shows how many bio pages there are and how many views they had.

### 🌐 Site
- A new help page explains bio pages step by step, linked from the Bio page screen and the Help center.

---

## [v2.11] — 2026-10-26

QR scans in link analytics, a public page with hrva.cc in numbers, a weekly summary and phishing-wave alerts for the admin, and the AI connector is sign-in only.

### 📊 Analytics
- QR codes made by hrva.cc now count their scans: link analytics and the admin stats split clicks into QR scans and other clicks. Codes made before this release count as other clicks, so download a new code to count scans from it.

### 🌐 Site
- hrva.cc in numbers: a public page with site-wide totals for the last 30 days, such as active links, clicks per day, harmful links blocked and how fast reports are reviewed. It never shows anything about a single link or person.
- A new help page explains the public numbers and QR scans, linked from both and from the Help page.

### ✨ AI Connector
- The AI connector without an account (hrva.cc/mcp/anon) is gone. Connect hrva.cc/mcp and sign in instead.

### 🔧 Admin
- Weekly summary: every Monday morning, Discord and email get last week's clicks, visitors, bot share, new links and users, reviews and automatic blocks, the top 5 links, and how many reviews are waiting right now. Clicks, new links and new users are compared with the week before. Discord and email can be turned off separately.
- Phishing-wave alert: Discord warns when 5 or more new links to the same new or unknown domain appear within an hour from at least 2 different accounts or networks, with a link to those links. Nothing is blocked automatically.
- All URLs opens with a search from its address, so the alert's link lands on the matching links.

### 🔒 Privacy
- The public numbers page stores nothing about you beyond a rate-limit counter for your network, which expires after an hour. QR scans are a daily count per link, like the other link statistics.

### 🐛 Fixes
- Discord alerts for the admin show links, domains, reasons and visitor details in full instead of cutting them off with "…". Alerts that don't fit in one message are counted as "and N more".
- Searching links, users or the review queue for % or _ now finds those characters instead of matching everything.
- Searching links for a domain finds it in either spelling, such as čokolada.hr and xn--okolada-i6a.hr.
- A web address whose domain name is longer than 253 characters, the most a domain name can have, is now refused as not a valid web address.
- Signing in again from an admin page brings you back to the page you opened.
- The report and unavailable pages no longer show the footer twice.
- Password managers no longer offer to fill your username into a passkey's name.

---

## [v2.10] — 2026-10-26

Passkeys and security keys, and two-step verification for every sign-in method.

### 🛡️ Security
- Sign in with a passkey: your fingerprint, face or screen lock, a password manager such as Bitwarden, Proton Pass or 1Password, or a security key such as a YubiKey. No password to type.
- Add as many passkeys and security keys as you like in Settings, and rename or remove them there. You get an email whenever one is added.
- Two-step verification can use a passkey or security key instead of a code, and can be turned on with passkeys only.
- Google and GitHub sign-ins now ask for your second step when two-step verification is on, and accounts that use Google or GitHub can turn it on.
- Recovery and authenticator codes work however you type them, with or without spaces, dashes or capitals.
- Sign-in messages now say how many tries are left and when you can try again.
- Deleting an account that signs in with Google or GitHub needs a sign-in within the last 15 minutes, as adding or removing a passkey does.

### 🔒 Privacy
- Passkeys store only a public key and a few details, such as the name you give it, when it was added and last used, and whether it's synced. Your fingerprint, face and PIN never leave your device.

### 🌐 Site
- A new help page explains passkeys and security keys step by step, linked from Settings and the footer.

---

## [v2.9] — 2026-10-26

Campaign tracking tags for your links, a full statistics page and reserved short link names for the admin, and a simpler admin menu.

### 🔗 Links
- Campaign tracking (UTM): when you create or edit a link, add Source, Medium and Campaign tags in a few clicks, with a preview of the final link. Your analytics tool reads them; hrva.cc doesn't.

### 📊 Analytics
- Link analytics shows the full destination's campaign tags (UTM) when the link has them.

### 🔧 Admin
- Stats: one page with Traffic, Links, Users, Safety and System tabs for the last 7, 30, 90 or 365 days. It shows clicks and visitors across all links, top links and referrers, links created per day and how they were made, sign-ups, the review queue and blocks, and the server's health.
- Long values are shown in full and wrap, instead of being cut off with "…".
- The stats are totals only. Unique visitors are counted per link per day and added up, and nothing identifies a visitor.
- Reserved aliases: keep short link names back, each with a reason. Nobody can create a link with one, random links never pick one, and links that already use one keep working.
- New links remember how they were made: on the website, with an API key, in bulk or through the AI connector, with or without an account.
- The admin pages have their own sidebar, grouped into Overview, Moderation, Content and Logs, in place of one long menu.
- The dashboard's redirect timing, request count, cache hit ratio, app version and database and Redis status now show real values.

### 🐛 Fixes
- Admin pages show times in your own time zone.
- Removing a reserved alias or a domain rule keeps keyboard focus on the list.
- Look-alike links that mix alphabets, such as a Cyrillic letter in a well-known brand, are now caught and sent for review.
- If an admin blocks a whole domain or several links at once, each owner now gets one email listing their blocked links instead of one email per link.
- Sign-up refuses an email address that's too long before sending any email.

---

## [v2.8.3] — 2026-10-26

Fixes for reactivating links, addresses with accented letters, admin link edits and blocklist refreshes.

### 🐛 Fixes
- Reactivating a link works again, including when an admin unblocks a blocked link.
- Links to addresses with accented letters, such as čokolada.hr, now redirect.
- Older links whose address contains curly braces now redirect instead of showing an error.
- When an admin changes a link's destination, the old destination's redirect details no longer come back, and a block made at the same moment is kept.
- A refused link edit, such as an expiry date in the past, is no longer saved anyway.
- Refreshing a blocklist source no longer undoes a change another admin made during the download, such as turning it off.

---

## [v2.8.2] — 2026-10-25

Fixes for long links and for two people picking the same short link name.

### 🐛 Fixes
- Links whose destination is longer than 255 characters no longer fail. Destinations can be up to 2048 characters, and longer ones get a clear message.
- If two people pick the same custom short link name at the same moment, the second now gets "This short URL is already taken. Try another." instead of an error.
- Bulk creation now answers an invalid link with a clear message instead of a server error.

---

## [v2.8.1] — 2026-10-25

Small fixes: header links when signed in, an accurate AI connector page and an up-to-date changelog.

### 🐛 Fixes
- The Features and API links in the header now work when you're signed in: the home page shows those sections below your shorten form.
- The AI connector page no longer lists the no-account server, which can't create links while anonymous link creation is turned off.
- This changelog covers everything since May again.

### 🌐 Site
- Blog posts and the privacy and terms pages link to the author's site, karlo.cc.

---

## [v2.8] — 2026-10-24

Privacy-first link analytics with referrers, countries and devices, plus Discord alerts and bulk review for the admin.

### 📊 Analytics
- Rebuilt analytics for each link: clicks per day, daily unique visitors and bot clicks, with its top referrers, countries, devices, browsers and operating systems.
- Rebuilt analytics overview across all your links, with a daily trend and your top 10 links by clicks.
- Pick a range of 7, 30, 90 or 365 days. Times are shown in your own time zone.
- Bots, link previews and crawlers, including the check Outlook runs before it opens a link, are counted separately and never as clicks or visits.
- Bots and link previews can no longer use up a visit-limited link: they aren't sent to its destination.
- A link's visit count now counts each person once per day and leaves bots out.
- Daily statistics start on 24 October 2026. Earlier clicks are included only in a link's all-time visit count.

### 🔒 Privacy
- Link statistics are daily totals per link. Nothing is recorded about an individual click, and IP addresses are never stored for link statistics.
- To count unique visitors, the IP address and browser identifier are combined with the link and a random key that changes every day into a one-way hash. The hashes and the key are kept only in Redis, never in the database, and expire a few hours after the day ends, so a visitor can't be followed across days or links.
- The country comes from Cloudflare, which carries hrva.cc's traffic; hrva.cc doesn't look up locations itself.
- The old visit records, which held a hashed IP address for each visitor, have been deleted.
- The privacy policy and the home page describe the new daily statistics.

### ✨ AI Connector
- link_stats now takes a range of 7, 30, 90 or 365 days and shows a link's top referrers, countries and devices, next to its clicks, daily unique visitors and bot clicks.

### 🔧 Admin
- Discord alerts: hrva.cc posts to the admin's Discord channel when a link is flagged for review or reported by a visitor, when a link is blocked automatically, when someone signs up, and when a blocklist fails to load or loads again. Alerts are sent together every 30 seconds and never include email or IP addresses.
- Review queue: select up to 100 entries and resolve them in one go. A domain they share is blocked or trusted once for all of them.
- Audit log: every entry has a readable action name, failed actions are marked, and the log can be filtered by action.

---

## [v2.7] — 2026-10-22

Layered checks against phishing and malware, a page for reporting links, and a review queue for doubtful ones.

### 🛡️ Link Safety
hrva.cc now checks where links lead when they're created, every night, and on every click.

- New links are checked against public blocklists of harmful domains and exact links (HaGeZi, OpenPhish, PhishTank, URLhaus and Phishing.Database). hrva.cc downloads the lists to its own server and refreshes them regularly, so checking a link against them sends it nowhere.
- hrva.cc also checks how old a destination's domain is, looks it up in the Spamhaus domain blocklist, and watches for names that imitate well-known brands or pose as login pages.
- A destination known to be harmful can't be shortened. A doubtful one is saved and sent to a person for review instead of being blocked.
- After a link is created, hrva.cc follows its redirects in the background and checks every address along the way.
- Every night, all active links are checked again against Google Safe Browsing and the latest lists.
- Every click is checked against the blocking lists first. A link whose destination has been listed since it was created stops working on that click.
- If one of your links is blocked, you get an email. If a whole account is blocked, its owner gets a single email.

### 🌐 Reports & Unavailable Links
- Anyone can report a hrva.cc link for phishing, malware, spam or other abuse on the new Report a link page. Reports go to a person for review and never block a link on their own.
- A short link that doesn't work now opens a page saying why: it was disabled for leading to a harmful site, it expired, it reached its visit limit, its owner turned it off, or it doesn't exist. The page has a button to report the link.
- hrva.cc publishes its abuse contact, abuse@hrva.cc, at hrva.cc/.well-known/security.txt.

### 🔒 Blog & Policies
- New blog post: How hrva.cc stops phishing links. It covers the scam links found in September, what hrva.cc checks now, and what it still can't catch.
- The privacy policy and terms of use have new sections on how links are checked, reported and moderated, and on which services see a destination's domain or URL.

### 🔧 Admin
- Blocklists page: add, edit and refresh list sources, each with fallback mirrors and its own refresh interval; see each list's size and last error; manage Block and Allow rules for domains; and test any URL to see every check that matches it.
- Review queue for flagged and reported links, with Looks fine, Trust domain, Block link, Block domain and Block user. The admin menu shows how many entries are waiting.
- Blocking a domain switches off every active link to it and its subdomains at once. Trusting a domain clears its reviews and, after checking them again, restores its links that a blocklist, scam signals or a domain rule had blocked.
- Blocking a user deactivates the account and blocks its links; unblocking restores both, after a confirmation that names the user.
- All URLs shows why a link was blocked, and domains and users can be blocked straight from the table.
- Accounts that behave like typical scam accounts are marked as possible scammers, with a filter on the Users page. The marker never blocks anything by itself.
- The daily admin email became a decisions email: it's sent only when something needs a decision and lists open reviews, automatic blocks, possible scammers and failing lists, without the text visitors wrote.
- Admins can moderate from Claude or ChatGPT through the AI connector: read the review queue and possible scammers, check a URL, block links, domains and users, and delete links. These tools can't unblock or trust anything and won't change more than 50 links at once. Deleting a link, blocking a domain or blocking a user needs a flag or block from the automatic checks first, since a visitor report alone isn't enough. Users appear only as masked email addresses.

---

## [v2.6] — 2026-10-21

Fixes from a security scan, working email sign-up, an hourly limit on new links, and admin tools for spotting abuse.

### 🛡️ Security
- Account details returned by the API, including the response to a password reset, no longer include the password hash.
- Previews, QR codes and other public lookups of a short link no longer show the owner's email or tags, and keep a password-protected link's destination hidden until it's unlocked.
- Wrong passwords on protected links are rate limited, and wrong two-factor codes count against the account.
- Setting up two-factor authentication again no longer switches off the one that's already active.
- A custom alias stays taken after its link expires or is turned off, so nobody else can claim it.
- Link previews never fetch private or internal network addresses, on any redirect.
- New passwords need at least 8 characters.
- Link passwords are no longer written to the admin audit log, and older entries were cleaned up.

### 🔗 Links
- Each account can create up to 60 links an hour, across the website, API keys and AI apps. Every link in a bulk upload counts.
- Links to other URL shorteners, or back to hrva.cc, are refused. Share links such as t.co, lnkd.in and Google's share links still work.
- You can reactivate links you turned off yourself.
- The nightly Safe Browsing check no longer skips links, and a link it blocks can't be reactivated by its owner.
- Reactivating a link checks it with Safe Browsing again and makes sure no other link holds its alias.
- Short links keep working when the cache is down, and a link stops as soon as it reaches its visit limit.

### 🐛 Fixes
- Signing up with email and password works again. The form now confirms the sign-up and shows what to fix in each field instead of "Request failed".
- Reminder emails for links that are about to expire are sent again.
- Signing in or out clears data left over from the previous session, and a network error no longer signs you out.
- Deleting the last link on the last page goes back a page, and selecting links for a bulk action only picks the ones on the page you see.
- Check a link says when a link is password protected instead of showing an empty destination.
- Settings shows your sign-in email as read-only, instead of an email form that couldn't save.

### 🔧 Admin
- Resend failed emails from the email log. Emails stuck sending for 10 minutes are marked failed, and stored email content is deleted once it's sent, or after 7 days if sending failed.
- All URLs: search by owner email or a pasted hrva.cc/abc link, show anonymous links only, sort by owner, and see a Suspicious marker with its reasons. The delete button works again.
- Suspicious-link flags for lookalike brand names, login lures, high-risk domain endings, dynamic DNS hosts, raw IP addresses, punycode and chained shorteners. They only flag; nothing is switched off automatically.
- A daily email lists new links from accounts younger than a week and links flagged as suspicious.
- Audit log entries list only the fields that were sent, show a password as changed or removed, and label failed actions clearly.
- Editing an API key's limits works again, and the health page reports email delivery from the latest email.

---

## [v2.5] — 2026-10-20

Connect Claude, ChatGPT and other AI apps to your hrva.cc account.

### ✨ AI Connector (MCP)
hrva.cc now has an MCP server at hrva.cc/mcp.

- Connect Claude (web, desktop and phone), Claude Code, ChatGPT, Cursor, VS Code or another MCP app that supports remote servers with sign-in, and it can shorten links into your account, list them and check their visits.
- The app signs in through hrva.cc: you log in as usual, with your password and two-factor code or with Google, then allow or deny the app on a consent page.
- Links an AI app creates are tagged "ai".
- Settings → Connected apps lists each connected app with when it was first connected and last used. Disconnecting an app cuts off its access at once.
- New MCP server page with setup steps for each app.
- The privacy policy explains what is stored for connected apps and for how long.

### 🔗 Links & Sign-in
- A destination must be a real web address: http or https with a proper domain name. Anything else gets a clear error instead of becoming a broken link. A bare domain such as example.com still gets https:// added.
- Signing in takes you back to the page you were trying to open.

### 🛡️ Security
- Fixed a gap that let a signed-in user edit a link they didn't own. Only a link's owner or an admin can edit it now.

### 🔧 Admin
- Admins can change where a short link points. The new destination is checked with Google Safe Browsing first.

---

## [v2.4] — 2026-10-19

A new look, a new logo, and privacy and terms pages.

### 🎨 Redesign
- A fresh, modern design across the site, with a new logo and a cleaner light theme. Dark mode is still there and follows your system setting.
- Every public page now shares the same header and footer.
- The dashboard, admin pages, blog and changelog were restyled to match.

### 🔒 Privacy & Terms
- New Privacy Policy and Terms of Use pages, linked from the footer.
- hrva.cc is a non-commercial hobby project of HrvaLabs, and the footer, privacy policy and terms link to the HrvaLabs website.

### 🐛 Fixes
- The home page fits on narrow phones, and the footer links wrap onto a new line instead of running off the screen.

---

## [v2.3] — 2026-05-31

### ✨ Added
- **Email verification** — new registrations must confirm their email before login; resend supported. Existing users grandfathered as verified; Google accounts auto-verified.
- **Two-factor authentication (TOTP)** — enroll an authenticator app (QR), confirm a code, receive one-time recovery codes. Login gains a second step. Local accounts only.
- **Account self-service** — delete your account (password-confirmed) along with all your URLs and API keys; download all your data as JSON (GDPR).
- **Password-protected links** — lock a short URL behind a password (registered users); visitors enter it before redirect.
- **URL tags** — tag links, filter by tag, manage tags on create/edit.
- **Click analytics** — per-URL and overview analytics (visits, unique recent visitors, 30-day new-visitor trend).
- **Customizable QR codes** — choose colors, size, and error-correction level; download as PNG or SVG.
- **Admin signup email + stats** — admin notified on new registrations; dashboard shows new-user counts (7d / 30d).

### 🔒 Security
- `active` flag now enforced at login (password + OAuth) and per-request; deactivating a user revokes their API keys.
- API keys are valid only while both the key and its owner are active.
- Admin-only endpoints hidden from the public API docs.

### 🎨 Improved
- URL and API-key status now reflects the real reason (Active / Expired / Limit reached / Revoked / Deactivated / Blocked) instead of a generic "Revoked".
- Fixed Google sign-in requiring two attempts; fixed the admin user deactivate-toggle 500.

### 🧪 Testing
- Backend test suite expanded to cover verification, 2FA, analytics, tags, self-delete, and status logic.

---

## [v2.1] — 2026-05-17

### ✨ Added
- **URL & User search/filtering** — Dashboard and admin pages now have search inputs, status filters (Active/Inactive/All), "Expired only" checkbox, and date range filters. Backend uses JPA Specifications for dynamic queries.
- **Pagination improvements** — "Showing X–Y of Z" count is always visible along with page size selector and prev/next buttons, even on single-page results.
- **Status page link** — Added `status.hrva.cc` link back to the footer (was lost in the rewrite).
- **More test coverage** — 6 new unit tests for URL and User filtering logic (now 146 total, all passing).

### 🎨 Improved UX
- **Footer now sticks to bottom** — Fixed double scrollbar issue. Footer stays at the bottom regardless of content height.
- **Logged-in shorten form** — Custom alias, visit limit, and expiration date are always visible (no need to click "Show advanced options").
- **Public landing page** — Removed advanced options that silently didn't work for anonymous users. Added subtle upsell ("Sign up free for custom aliases & analytics") in the hero text and after shortening a link.
- **Deactivate toggle icon** — Changed from `ToggleLeft` (ambiguous) to `ToggleRight` (clearly shows the URL is active and can be turned off).
- **URL count accuracy** — Fixed stats counter to use `totalElements` from API instead of page content length.

### 🔧 Changed
- Refactored backend filter params into `UrlSearchDto` / `UserSearchDto` instead of scattered `@RequestParam` annotations.

### 🧪 Testing
- Backend: 146 unit tests (JUnit 5 + Mockito), all passing.
- Frontend: Build clean with no errors.

---

## [v2.0] — React Rewrite

Complete rewrite of the frontend from Angular to React (Next.js). Full feature parity plus new capabilities.

### ✨ Features
- **URL shortening** — Create short links with optional custom alias, visit limit, and expiration date.
- **Anonymous shortening** — Shorten URLs without an account.
- **User accounts** — Register, login, password reset, JWT-based auth.
- **Dashboard** — View, search, sort, and manage all your URLs in one place.
- **URL analytics** — Per-link visit tracking, QR codes, link previews.
- **API keys** — Generate and manage API keys for programmatic access.
- **Bulk URL creation** — Create multiple URLs at once.
- **CSV export** — Download your URLs as a CSV file.
- **Admin panel** — User management (edit, activate/deactivate, delete), system stats.
- **OAuth2 login** — Sign in with Google or GitHub.
- **Dark/light theme** — System-aware theme with manual toggle.
- **Responsive design** — Full mobile support.

### 🛡️ Security
- Google Safe Browsing integration — every URL is checked before shortening.
- JWT stateless auth with configurable expiry.
- Rate limiting on login attempts.
- Account deactivation after inactivity.

### ⚡ Performance
- Redis caching for URL lookups with graceful degradation.
- Read-only transactions on service layer.
- Optimized Docker builds with multi-arch support.

### 🧪 Testing
- 140+ unit tests covering controllers, services, validators, converters, and models.

---

## [v1.0] — Angular Frontend + Spring Boot Backend

Initial release of hrva.cc.

### ✨ Features
- URL shortening with custom aliases
- Visit limits and expiration dates
- Link analytics dashboard
- API key management
- User authentication (email/password)
- Password reset flow
- QR code generation
- Admin user management
- Docker deployment
- Swagger API documentation

---

## v0.x — Prototype & Development

Early development phase with Spring Boot backend and Angular frontend. Core functionality established:
- URL CRUD operations
- Visit tracking
- User registration and authentication
- Basic admin capabilities

---

*hrva.cc — Short links, shaped by you.*
