# AGENTS.md

Instructions for AI coding agents working in this repository. `CLAUDE.md` is the detailed
reference — architecture, every environment variable, every metric. Read it before changing
code; this file is the short version plus the rules that are easy to violate.

## What this is

**Klag** — a Kafka consumer lag exporter. Java 21 on Vert.x 4.5.34, Micrometer for metrics,
Apache-2.0. It polls Kafka's AdminClient read-only and exports lag and derived signals to
Prometheus, Datadog, or OTLP. Docs: https://klag.dev (machine-readable at
https://klag.dev/llms.txt).

Are you trying to *use* Klag rather than develop it — install it, connect it, read its
metrics? Then you want the skill, not this repo: `/plugin marketplace add themoah/klag`,
or fetch https://klag.dev/.well-known/agent-skills/index.json. There is also a read-only
documentation MCP server at `https://klag.dev/mcp`.

## Build and test

Java 21 required (`sdk use java 21.0.9-tem`). Use `./gradlew` locally; CI calls `gradle`
directly, as no wrapper JAR is committed.

```bash
./gradlew compileJava
./gradlew test
./gradlew assemble              # fat JAR
./scripts/test-helm-chart.sh    # chart templating, offline
./scripts/check-plugin.sh       # plugin manifest shape
cd website && npm run build && npm test   # docs site + generators
```

`./scripts/e2e-test.sh` and `./scripts/e2e-strimzi-test.sh` spin up real clusters — slow,
and they need Docker. Do not run them speculatively.

## Invariants — do not break these

1. **Admin request volume must stay independent of topic count.** Each collection cycle
   fetches committed offsets per group, then makes *one* batched `getLogEndOffsets(Set)`
   call for the union of their topics. Reintroducing a per-topic fetch turns one call into
   four requests × every topic × every cycle. `MetricsCollectorBatchingTest` pins this.
2. **Deleted topics are filtered before `describeTopics`.** One unknown topic fails the whole
   batch, and committed offsets outlive a deleted topic for `offsets.retention.minutes`. A
   failed `listTopics` must propagate, never fall through unfiltered.
3. **Partial cycles hold the failed groups' series and clean up the rest.** Cleaning against
   the cycle's incomplete key set alone would delete live series, so the failed groups'
   previous keys are added back first. The MCP snapshot still publishes, but an *empty*
   snapshot is never published.
4. **Adding a metric means updating `dashboard/demo-dashboard.json`** and the metrics tables
   in `CLAUDE.md` and `website/src/content/docs/metrics/`.
5. **Version bumps.** One bump of `version` in `build.gradle.kts` per PR that changes the
   application, and `charts/klag/Chart.yaml` `appVersion` plus the artifacthub annotation
   must match it. Website-only or docs-only changes do not bump.

## Style

Async operations return `Future<T>`. Java 21 records for DTOs. SLF4J + Logback. Config
resolves classpath `application.properties` → external `KLAG_CONFIG_FILE` → `KAFKA_*` env
vars, and every `Env`-backed variable also resolves from `-DNAME` and `-Dname.dotted`.
Match the surrounding code's comment density and naming rather than importing a new style.

## The website (`website/`)

Astro + Starlight on Cloudflare Workers, Node >= 22.12. Content lives in
`website/src/content/docs/`. Several agent-facing artifacts are **generated at build time and
must not be hand-edited**: `dist/llms.txt`, `dist/llms-full.txt`, the per-page `.md` twins,
`dist/skills/**`, `dist/.well-known/agent-skills/index.json`, and `src/generated/docs.json`.
Change `website/scripts/gen-llms.mjs` or `gen-skills.mjs` instead. The plugin under
`plugin/` is the single source of truth for the skills the site serves.

Cloudflare Workers Builds publishes the site on git push; `.github/workflows/website.yml`
only builds, tests, and type-checks. Do not run `wrangler deploy` from a working tree.
