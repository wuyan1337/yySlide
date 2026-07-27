# yySlide website

This directory contains the static public website. It can be served directly by
any static web server; no build step is required.

For local preview:

```powershell
python -m http.server 8080 --directory website
```

Production should additionally send `Content-Security-Policy`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy`, and a restrictive
`Permissions-Policy` as HTTP response headers. The legacy deployment tarball is
archived outside the repository; edit and deploy the source files instead.
The same-origin Caddy example in `server/DEPLOY.md` serves this directory while
proxying only `/health` and `/v1/*` to yySlide Cloud.

Published screenshots contain only documentation-range example addresses. If
new screenshots are captured from a real host, replace the public fields with
`python scripts/redact-public-screenshots.py` (requires Pillow) before commit.
