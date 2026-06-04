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
  // GEO: advertise the machine-readable docs corpus (llms.txt convention).
  {
    tag: 'link',
    attrs: { rel: 'alternate', type: 'text/markdown', href: '/llms.txt', title: 'llms.txt' },
  },
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
            { label: 'MCP Endpoint', slug: 'ai/mcp' },
            { label: 'Agent Skill', slug: 'ai/agent-skill' },
          ],
        },
        {
          label: 'Deployment',
          items: [
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
      ],
    }),
    sitemap(),
  ],
});
