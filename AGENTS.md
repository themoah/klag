# AGENTS.md

Instructions for AI coding agents working in this repository. This file is the
single source of truth: short overview, hard invariants, then architecture and
pointers. Full environment-variable and metric tables live on https://klag.dev
and https://klag.dev/llms.txt — do not duplicate those tables here.

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

Native image needs a GraalVM JDK 21 with `native-image`. Entry point is `KlagLauncher`
(direct `new MainVerticle()`, no reflective Vert.x launcher). Config lives in
`build.gradle.kts` (`graalvmNative`) plus `src/main/resources/META-INF/native-image/`.

```bash
gradle nativeCompile           # -> build/native/nativeCompile/klag
docker build -f Dockerfile.native -t klag:native .
```

## Invariants — do not break these

1. **Admin request volume must stay independent of topic count.** Each collection cycle
   fetches committed offsets per group, then makes *one* batched `getLogEndOffsets(Set)`
   call for the union of their topics. Reintroducing a per-topic fetch turns one call into
   four requests × every topic × every cycle. `MetricsCollectorBatchingTest` pins this.
2. **Deleted topics are filtered before `describeTopics`.** One unknown topic fails the whole
   batch, and committed offsets outlive a deleted topic for `offsets.retention.minutes`. A
   failed `listTopics` must propagate, never fall through unfiltered.
3. **Partial cycles skip stale-gauge cleanup but still publish the MCP snapshot.** Cleaning
   against an incomplete key set would delete live series. An *empty* snapshot is never
   published.
4. **Adding a metric means updating `dashboard/demo-dashboard.json`** and the metrics tables
   in `website/src/content/docs/metrics/`. Never ship a new metric without a dashboard panel.
5. **Version bumps.** One bump of `version` in `build.gradle.kts` per PR that changes the
   application, and `charts/klag/Chart.yaml` `appVersion` plus the artifacthub annotation
   must match it. Website-only or docs-only changes do not bump.

## Style

Async operations return `Future<T>`. Java 21 records for DTOs. SLF4J + Logback. Config
resolves classpath `application.properties` → external `KLAG_CONFIG_FILE` → `KAFKA_*` env
vars, and every `Env`-backed variable also resolves from `-DNAME` and `-Dname.dotted`.
Match the surrounding code's comment density and naming rather than importing a new style.

## Architecture

Vert.x reactive `Future<T>` API.

```
src/main/java/io/github/themoah/klag/
├── MainVerticle.java          # Entry point, HTTP router, lifecycle
├── config/AppConfig.java      # HTTP_PORT, KAFKA_HEALTH_CHECK_INTERVAL_MS
├── health/                    # KafkaHealthMonitor, HealthCheckHandler, HealthStatus, VersionHandler
├── kafka/                     # KafkaClientService[Impl], KafkaClientConfig, KafkaClusters, KafkaClusterSpec
├── metrics/                   # MetricsCollector, MicrometerReporter, PrometheusHandler
│   ├── velocity/              # LagVelocityTracker, TopicLagHistory
│   ├── hotpartition/          # HotPartitionDetector, HotPartitionConfig, StatisticalUtils
│   ├── dataskew/              # DataSkewDetector, DataSkewConfig
│   └── timelag/               # TimeLagEstimator, TimeLagConfig, OffsetTimestampTracker, PartitionOffsetHistory
├── mcp/                       # read-only MCP snapshot tools
└── model/                     # Records: ConsumerGroupLag, ConsumerGroupState, PartitionOffsets, LagVelocity, etc.
```

**Collection cycle (keep this shape).** Each cycle runs in two phases: committed offsets for
every group (in waves of `KAFKA_MAX_CONCURRENT_GROUPS`), then **one** batched
`getLogEndOffsets(Set<String>)` for the union of their topics — one `describeTopics` plus
three `listOffsets` for the whole set, not per topic (with `KAFKA_CHUNK_COUNT > 1` the union
is split into that many batches). Lag assembly is then pure computation.

Deleted topics are intersected with one cached `listTopics()` per cycle before
`describeTopics`. A permanently failing group freezes stale-gauge cleanup (deliberate);
the MCP snapshot is still published so agents do not read hours-old data. An empty
snapshot is never published.

## HTTP endpoints

| Endpoint | Purpose |
|----------|---------|
| `/healthz` | Liveness probe (always 200) |
| `/readyz` | Readiness (200 if any configured Kafka cluster is UP, 503 if all are DOWN) |
| `/metrics` | Prometheus scrape endpoint (if enabled) |
| `/version` | Build information |
| `/mcp` | MCP endpoint for a *running instance* (JSON-RPC over POST; if `MCP_ENABLED=true`) |

Do not confuse instance `/mcp` with the documentation MCP at `https://klag.dev/mcp`.

## Configuration and metrics

Env-backed settings resolve in order (first non-blank wins): env var `NAME` → JVM
property `-DNAME` → dotted `-Dname.dotted`. Config file precedence: classpath
`application.properties` < `KLAG_CONFIG_FILE` < `KAFKA_*` env vars.

The complete variable list and every metric name/tag live on the public docs, not in
this file:

- https://klag.dev (human)
- https://klag.dev/llms.txt (machine-readable)
- `website/src/content/docs/configuration/reference.md`
- `website/src/content/docs/metrics/`

When you add, rename, or retag a metric, update collector/reporter + tests, README,
those website metric pages, and `dashboard/demo-dashboard.json`.

## Agent onboarding plugin

The repo doubles as a Claude Code marketplace: `.claude-plugin/marketplace.json` points
at `./plugin`. Users: `/plugin marketplace add themoah/klag` then
`/plugin install klag@klag`. Keep `source: "./plugin"` — `"./"` would install the whole
tree. The skill fetches `klag.dev/llms.txt` rather than duplicating the config reference.
`scripts/check-plugin.sh` pins manifest shape.

The site Worker serves a read-only documentation MCP at `klag.dev/mcp`
(`search_klag_docs`, `get_klag_doc`, `get_klag_config`, `get_klag_metric`). Its corpus is
generated (`website/scripts/gen-llms.mjs`, `gen-skills.mjs`); do not hand-edit
`dist/llms.txt` or `src/generated/docs.json`.

## The website (`website/`)

Astro + Starlight on Cloudflare Workers, Node >= 22.12. Content lives in
`website/src/content/docs/`. Several agent-facing artifacts are **generated at build time and
must not be hand-edited**: `dist/llms.txt`, `dist/llms-full.txt`, the per-page `.md` twins,
`dist/skills/**`, `dist/.well-known/agent-skills/index.json`, and `src/generated/docs.json`.
Change `website/scripts/gen-llms.mjs` or `gen-skills.mjs` instead. The plugin under
`plugin/` is the single source of truth for the skills the site serves.

Cloudflare Workers Builds publishes the site on git push; `.github/workflows/website.yml`
only builds, tests, and type-checks. Do not run `wrangler deploy` from a working tree.
