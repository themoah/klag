# klag.dev website

Docs + promotional site for [Klag](https://github.com/themoah/klag), built with
[Astro](https://astro.build) + [Starlight](https://starlight.astro.build) and deployed to
**Cloudflare Pages** at [klag.dev](https://klag.dev).

## Local development

```bash
cd website
npm install
npm run dev        # http://localhost:4321
```

## Build

```bash
npm run build      # runs scripts/gen-llms.mjs, then `astro build` -> dist/
npm run preview    # serve the built dist/ locally
```

The `build` script regenerates the GEO files (`public/llms.txt`,
`public/llms-full.txt`) from the docs before building, so they never drift. Those two
files are generated (git-ignored).

## Content

All docs live in `src/content/docs/**` as Markdown/MDX. The sidebar is configured in
`astro.config.mjs`. Add a page by dropping a `.md`/`.mdx` file with `title` +
`description` frontmatter and adding it to the sidebar.

Content is sourced from the repo's `README.md`, `CLAUDE.md`, and `docs/agent-skill.md` —
keep them in sync when project facts change.

## Analytics

Cloudflare Web Analytics (cookieless). The beacon is injected only when
`CF_ANALYTICS_TOKEN` is set in the build environment — set it in the Cloudflare Pages
project settings after enabling Web Analytics for the domain.

## Deploy (Cloudflare Pages)

Connect the GitHub repo as a Pages project with:

- **Root directory:** `website`
- **Build command:** `npm run build`
- **Output directory:** `dist`
- **Environment:** `NODE_VERSION=20`, and `CF_ANALYTICS_TOKEN=<token>` once available.

Add `klag.dev` as the custom domain (DNS is already in Cloudflare). Branch pushes get
preview deployments automatically.
