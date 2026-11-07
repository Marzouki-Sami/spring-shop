# Security policy

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's
[private vulnerability reporting](https://github.com/Marzouki-Sami/spring-shop/security/advisories/new)
with steps to reproduce. You can expect an acknowledgement within a few days.

## Automated checks on every pull request

- Build and tests on Java 21, plus the full test suite against a real MySQL.
- [CodeQL](.github/workflows/codeql.yml) static analysis.
- [gitleaks](.github/workflows/secret-scan.yml) scans code and history for secrets.
- Dependency review blocks new dependencies with high-severity advisories.
- [Dependabot](.github/dependabot.yml) proposes dependency and Actions updates weekly.

Secrets (database password, JWT signing key) are read from the environment and are never committed.

## Deploying safely

- Set `FORWARD_HEADERS=framework` only behind a reverse proxy you control; otherwise clients can forge their address in the audit log.
- The `demo` profile switches staff two-factor off and creates sample accounts. It refuses to start when `APP_BASE_URL` is not a localhost address. Never use it for a real shop.
- Keep `SESSION_COOKIE_SECURE` at its default (`true` with MySQL) once the site is served over HTTPS.
- Set a strong `JWT_SECRET` and, ideally, a separate `MFA_TOTP_KEY`.
