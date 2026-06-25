# Padly landing

Next.js 15 + Tailwind + Framer Motion. Deploys on Vercel.

## Local

```sh
pnpm install
pnpm dev
```

Open <http://localhost:3000>.

## Deploy

```sh
pnpm install -g vercel
vercel link        # one-time, picks the project
vercel --prod
```

Or push to a Git repo connected to Vercel — it builds on every push to `main`.

## Updating downloads

Drop signed builds into `public/downloads/`:

- `public/downloads/padly-android.apk`
- `public/downloads/Padly.dmg`

The placeholders shipped here are empty stubs that explain how to get the real builds.

## Configuration

Edit `lib/site.ts` to change:

- product name, tagline
- version strings
- GitHub URL
- contact email
