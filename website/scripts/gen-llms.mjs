// Generates GEO (generative-engine-optimization) files for klag.dev:
//   public/llms.txt       - concise index: project summary + linked page list
//   public/llms-full.txt  - full concatenated docs for direct LLM ingestion
//
// Walks src/content/docs/**, reads frontmatter (title/description) and body,
// so the files never drift from the actual docs. Run at build time via the
// "build" npm script before `astro build`.

import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DOCS = join(ROOT, 'src', 'content', 'docs');
const PUBLIC = join(ROOT, 'public');
const SITE = 'https://klag.dev';

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(full)));
    else if (entry.name.endsWith('.md') || entry.name.endsWith('.mdx')) out.push(full);
  }
  return out;
}

// Minimal frontmatter parse: grab title/description, return body without the block.
function parseFrontmatter(raw) {
  const m = raw.match(/^---\n([\s\S]*?)\n---\n?/);
  if (!m) return { data: {}, body: raw };
  const data = {};
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^(\w[\w-]*):\s*(.*)$/);
    if (kv) data[kv[1]] = kv[2].replace(/^["']|["']$/g, '').trim();
  }
  return { data, body: raw.slice(m[0].length) };
}

// file path -> site URL path (strip extension, drop /index)
function toUrlPath(file) {
  let p = relative(DOCS, file).replace(/\\/g, '/').replace(/\.(md|mdx)$/, '');
  if (p === 'index') return '/';
  if (p.endsWith('/index')) p = p.slice(0, -'/index'.length);
  return `/${p}/`;
}

const files = (await walk(DOCS)).sort();
const pages = [];
for (const file of files) {
  const raw = await readFile(file, 'utf8');
  const { data, body } = parseFrontmatter(raw);
  const urlPath = toUrlPath(file);
  // Skip the splash landing page from the doc body dump but keep it in the index.
  pages.push({ urlPath, url: SITE + urlPath, title: data.title || urlPath, description: data.description || '', body: body.trim(), splash: data.template === 'splash' });
}

// ---- llms.txt (index) ----
const summary =
  'Klag is an open-source Kafka consumer lag exporter built with Vert.x and ' +
  'Micrometer. It monitors consumer lag, lag velocity, hot partitions, time-based ' +
  'lag, data-loss risk, and consumer-group state, and exports to Prometheus, ' +
  'Datadog, or OTLP (OpenTelemetry). It also exposes an opt-in read-only MCP ' +
  'endpoint for AI agents.';

let index = `# Klag\n\n> ${summary}\n\n`;
index += `Source: ${SITE} | Repository: https://github.com/themoah/klag\n\n## Docs\n\n`;
for (const p of pages) {
  if (p.splash) continue;
  index += `- [${p.title}](${p.url})${p.description ? `: ${p.description}` : ''}\n`;
}
index += `\n## Full text\n\n- [Full documentation, concatenated](${SITE}/llms-full.txt)\n`;

// ---- llms-full.txt (full concatenated docs) ----
let full = `# Klag — Full Documentation\n\n> ${summary}\n\nSource: ${SITE}\n\n`;
for (const p of pages) {
  if (p.splash) continue;
  full += `\n\n---\n\n# ${p.title}\nURL: ${p.url}\n`;
  if (p.description) full += `\n${p.description}\n`;
  full += `\n${p.body}\n`;
}

await mkdir(PUBLIC, { recursive: true });
await writeFile(join(PUBLIC, 'llms.txt'), index, 'utf8');
await writeFile(join(PUBLIC, 'llms-full.txt'), full, 'utf8');
console.log(`gen-llms: wrote llms.txt + llms-full.txt (${pages.length} pages)`);
