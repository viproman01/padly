# Padly landing

Next.js 16 + Tailwind + Framer Motion, static export (`output: 'export'` → `out/`).
Live: <https://padly.109-172-47-162.sslip.io> (nginx on a VPS, no Node on the server).

## Local

```sh
pnpm install
pnpm dev
```

Open <http://localhost:3000>.

## Deploy

```sh
bash deploy/vps/deploy.sh          # build out/ + rsync to the VPS + check pages (SKIP_BUILD=1 to reuse out/)
```

nginx config for the site: `deploy/vps/nginx-padly.conf`. The stubs in `public/downloads/` are not uploaded.

## Downloads

Download buttons point at GitHub Releases (`releases/latest/download/...`), built by `.github/workflows/release.yml` on a `v*` tag.
While no release exists, `releasesReady: false` in `lib/site.ts` shows "скоро" instead of dead links and points to the source code.
After the first release, set it to `true` and redeploy.

## Configuration

Edit `lib/site.ts` to change:

- product name, tagline
- version strings
- GitHub URL
- release status (`releasesReady`)
