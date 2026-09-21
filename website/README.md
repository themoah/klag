# klag.dev website

Docs + promotional site for [Klag](https://github.com/themoah/klag), built with
[Astro](https://astro.build) + [Starlight](https://starlight.astro.build) and deployed as a
**Cloudflare Worker with static assets** at [klag.dev](https://klag.dev).

The Worker (`src/worker.ts`) sits in front of the built site. It serves the documentation
MCP endpoint at `/mcp`, rewrites `/sitemap.xml`, negotiates `Accept: text/markdown`, and
adds the agent-discovery `Link` headers. Everything else falls through to `dist/` via the
`ASSETS` binding.

## Local development

```bash
cd website
npm install
npm run dev        # http://localhost:4321 — Astro only, no Worker
npm run preview    # build + `wrangler dev` — the real thing, Worker included
```

Node >= 22.12 (see `.node-version`).

## Build

```bash
npm run build      # astro build -> dist/, then gen-llms.mjs and gen-skills.mjs
npm run check      # wrangler types + astro check
npm test           # generator tests (node --test)
```

`npm run build` writes several **generated** artifacts into `dist/` — never edit them by
hand, and never commit them:

| Output | Produced by |
| --- | --- |
| `llms.txt`, `llms-full.txt` | `scripts/gen-llms.mjs` |
| `<slug>.md` markdown twin of every page | `scripts/gen-llms.mjs` |
| `<section>/llms.txt` scoped indexes | `scripts/gen-llms.mjs` |
| `src/generated/docs.json` (the corpus the Worker bundles) | `scripts/gen-llms.mjs` |
| `skills/**` + `.well-known/agent-skills/index.json` | `scripts/gen-skills.mjs`, from `../plugin` |
| `openapi.json` re-stamped with the app version | `scripts/gen-skills.mjs`, from `build.gradle.kts` |

`public/og.png` is generated too, but by hand — run `npm run gen:og` after changing the
wording or the logo, and commit the result.

## Content

All docs live in `src/content/docs/**` as Markdown/MDX. The sidebar is configured in
`astro.config.mjs`. Add a page by dropping a `.md`/`.mdx` file with `title` +
`description` frontmatter and adding it to the sidebar.

`README.md` and `AGENTS.md` are repository reference material. Update the relevant pages
under `src/content/docs/**` manually when those project facts change; the generator reads
those pages rather than these root files.

## Agent-facing surface

Hand-written files under `public/.well-known/` (`ai-catalog.json`, `agent-card.json`,
`api-catalog`, `mcp/server-card.json`), plus `public/openapi.json`, `public/pricing.md`,
and `public/agents.md`. `/developers/` documents the whole surface for humans. When you add
a resource, add it to `ai-catalog.json` as well or agents will not find it.

## Analytics

Cloudflare Web Analytics (cookieless). The beacon is injected only when
`CF_ANALYTICS_TOKEN` is set in the build environment.

## CI and deploy

**GitHub Actions** (`.github/workflows/website.yml`) builds, tests, and type-checks every
push and PR touching `website/**` or `plugin/**`. It does not publish.

**Cloudflare Workers Builds** (Git integration on the `klag` Worker) deploys on push:
`main` runs `npm run build` then `wrangler deploy` to **klag.dev**; other branches upload
preview versions. Configure build commands and branch filters in the Cloudflare dashboard.

Optional Cloudflare build env:

| Variable | Purpose |
| --- | --- |
| `CF_ANALYTICS_TOKEN` | Without it the analytics beacon is simply not injected. |

`klag.dev` is attached to the `klag` Worker as a custom domain (`wrangler.jsonc`); there is
no Pages project. There is deliberately no `npm run deploy` script — a local
`wrangler deploy` would ship whatever happens to be in the working tree.

**Break glass.** If Cloudflare Builds is down and the site must ship, build and publish by
hand from `website/` on Node 22+, and say so in the PR:

```bash
npm ci && npm run build && npx wrangler deploy
```
