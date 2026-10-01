// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import sitemap from '@astrojs/sitemap';

const SITE = 'https://klag.dev';
const REPO = 'https://github.com/themoah/klag';

// Cloudflare Web Analytics (cookieless). Set CF_ANALYTICS_TOKEN in the
// Cloudflare Pages build env once the project exists — the beacon is only
// injected when the token is present, so local/dev builds stay clean.
const cfToken = process.env.CF_ANALYTICS_TOKEN;
/** @type {NonNullable<Parameters<typeof starlight>[0]>['head']} */
const head = [
  // SEO: explicit indexing; welcome AI crawling/training (mirrors robots.txt).
  {
    tag: 'meta',
    attrs: { name: 'robots', content: 'index, follow, max-image-preview:large' },
  },
  // GEO: advertise the machine-readable docs corpus (llms.txt convention). This head is
  // site-wide, so it can only name the corpus index — rel="index", not rel="alternate",
  // which would claim /llms.txt is a rendering of whichever page you happen to be on.
  // The per-page .md twin is advertised as rel="alternate" in the Worker's Link header
  // (see discoveryLinks in src/worker.ts), where the request path is known.
  {
    tag: 'link',
    attrs: { rel: 'index', type: 'text/markdown', href: '/llms.txt', title: 'llms.txt' },
  },
  // Agent discovery: the ARD catalog lists the MCP server, skills, and OpenAPI spec.
  {
    tag: 'link',
    attrs: { rel: 'service-desc', type: 'application/json', href: '/.well-known/ai-catalog.json' },
  },
  // Social card. Absolute URLs — scrapers do not resolve relative og:image.
  { tag: 'meta', attrs: { property: 'og:image', content: `${SITE}/og.png` } },
  { tag: 'meta', attrs: { property: 'og:image:width', content: '1200' } },
  { tag: 'meta', attrs: { property: 'og:image:height', content: '630' } },
  {
    tag: 'meta',
    attrs: { property: 'og:image:alt', content: 'Klag — Kafka consumer lag exporter' },
  },
  { tag: 'meta', attrs: { name: 'twitter:image', content: `${SITE}/og.png` } },
  // Favicons: higher-res PNG + Apple touch icon (Starlight injects the base /favicon-32.png).
  { tag: 'link', attrs: { rel: 'icon', type: 'image/png', sizes: '48x48', href: '/favicon-48.png' } },
  { tag: 'link', attrs: { rel: 'apple-touch-icon', sizes: '180x180', href: '/apple-touch-icon.png' } },
];
if (cfToken) {
  head.push({
    tag: 'script',
    attrs: {
      defer: true,
      src: 'https://static.cloudflareinsights.com/beacon.min.js',
      'data-cf-beacon': JSON.stringify({ token: cfToken }),
    },
  });
}

// https://astro.build/config
export default defineConfig({
  site: SITE,

  // Short links for the README and external references.
  redirects: {
    '/migration': '/getting-started/migrating-from-kafka-lag-exporter/',
    '/agent-setup': '/ai/agent-setup/',
    '/getting-started/comparison': '/comparisons/overview/',
    // One entry only: Astro normalises the trailing slash, and defining both collides.
    '/comparisons': '/comparisons/overview/',
  },

  integrations: [
    starlight({
      title: 'Klag',
      description:
        'Klag is a Kafka consumer lag exporter built with Vert.x. Monitor consumer lag, lag velocity, hot partitions, and group state with Prometheus, Datadog, or OTLP.',
      tagline: 'Know when your consumers fall behind, before it becomes a problem.',
      logo: { src: './src/assets/klag-logo.png', alt: 'Klag', replacesTitle: true },
      favicon: '/favicon-32.png',
      head,
      social: [
        { icon: 'github', label: 'GitHub', href: REPO },
      ],
      editLink: { baseUrl: `${REPO}/edit/main/website/` },
      lastUpdated: true,
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { label: 'Introduction', slug: 'getting-started/introduction' },
            { label: 'Quick Start', slug: 'getting-started/quick-start' },
            { label: 'Installation', slug: 'getting-started/installation' },
            { label: 'Migrating from kafka-lag-exporter', slug: 'getting-started/migrating-from-kafka-lag-exporter' },
          ],
        },
        {
          label: 'Comparisons',
          items: [
            { label: 'Overview', slug: 'comparisons/overview' },
            { label: 'Klag vs Burrow', slug: 'comparisons/klag-vs-burrow' },
            { label: 'Klag vs KMinion', slug: 'comparisons/klag-vs-kminion' },
            { label: 'Klag vs AKHQ', slug: 'comparisons/klag-vs-akhq' },
            { label: 'Klag vs Confluent Control Center', slug: 'comparisons/klag-vs-confluent-control-center' },
            { label: 'Klag vs Redpanda Console', slug: 'comparisons/klag-vs-redpanda-console' },
            { label: 'Klag vs Grafana', slug: 'comparisons/klag-vs-grafana' },
            { label: 'Klag vs Cruise Control', slug: 'comparisons/klag-vs-cruise-control' },
          ],
        },
        {
          label: 'Configuration',
          items: [
            { label: 'Reference', slug: 'configuration/reference' },
            { label: 'Group Filtering', slug: 'configuration/group-filtering' },
          ],
        },
        {
          label: 'Metrics',
          items: [
            { label: 'Overview', slug: 'metrics/overview' },
            { label: 'Lag Velocity', slug: 'metrics/lag-velocity' },
            { label: 'Time-Based Lag', slug: 'metrics/time-based-lag' },
            { label: 'Hot Partitions', slug: 'metrics/hot-partitions' },
            { label: 'Data Loss Prevention', slug: 'metrics/data-loss-prevention' },
            { label: 'ISR Monitoring', slug: 'metrics/isr' },
            { label: 'Topic Data Skew', slug: 'metrics/data-skew' },
          ],
        },
        {
          label: 'Guides',
          items: [
            { label: 'How Kafka Consumer Lag Works', slug: 'guides/how-kafka-consumer-lag-works' },
            { label: "Why Lag Value Alone Isn't Enough", slug: 'guides/why-lag-value-is-not-enough' },
            { label: 'Common Monitoring Mistakes', slug: 'guides/consumer-monitoring-mistakes' },
            { label: 'Detect Stuck Consumers', slug: 'guides/detect-stuck-consumers' },
            { label: 'Troubleshooting', slug: 'guides/troubleshooting' },
          ],
        },
        {
          label: 'Kafka',
          items: [
            { label: 'ACL Permissions', slug: 'kafka/acl-permissions' },
          ],
        },
        {
          label: 'Integrations',
          items: [
            { label: 'Prometheus', slug: 'integrations/prometheus' },
            { label: 'Datadog', slug: 'integrations/datadog' },
            { label: 'OTLP & Grafana Cloud', slug: 'integrations/otlp-grafana' },
            { label: 'Grafana Dashboard', slug: 'integrations/grafana-dashboard' },
          ],
        },
        {
          label: 'AI Agents',
          items: [
            { label: 'Agent Setup', slug: 'ai/agent-setup' },
            { label: 'MCP Endpoint', slug: 'ai/mcp' },
            { label: 'MCP Evaluation', slug: 'ai/evaluation-checklist' },
            { label: 'Developers', slug: 'developers' },
          ],
        },
        {
          label: 'Deployment',
          items: [
            { label: 'Confluent Cloud', slug: 'deployment/confluent-cloud' },
            { label: 'Kubernetes (Helm)', slug: 'deployment/kubernetes' },
            { label: 'Strimzi', slug: 'deployment/strimzi' },
            { label: 'Native Image', slug: 'deployment/native-image' },
          ],
        },
        {
          label: 'Development',
          items: [
            { label: 'Build from Source', slug: 'development/build' },
            { label: 'Contributing', slug: 'development/contributing' },
          ],
        },
        {
          label: 'Project',
          items: [
            { label: 'About', slug: 'about' },
            { label: 'Privacy', slug: 'privacy' },
          ],
        },
      ],
    }),
    // lastmod is the build timestamp: the site is statically rebuilt on every content
    // change, so "last built" and "last changed" are the same event here.
    sitemap({ lastmod: new Date() }),
  ],
});
