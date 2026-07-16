# AGENTS.md

Klag is a Kafka consumer lag exporter (Vert.x 4.5.22, Java 21). The authoritative
reference for env vars, metrics, architecture, and build commands lives in `CLAUDE.md`
and `.cursor/rules/project.mdc` — read those first.

## Cursor Cloud specific instructions

Environment is pre-provisioned by the update script: **JDK 21** (system `java`) and
**Gradle 8.14.3** (system `gradle` at `/usr/local/bin/gradle`) are already installed.

- **Use `gradle` directly, NOT `./gradlew`.** The Gradle wrapper JAR is not committed
  (no `gradle/wrapper/` dir), so `./gradlew` fails with "Unable to access jarfile". This
  contradicts `CLAUDE.md` which mentions `./gradlew` for local dev — on Cloud, always run
  the bare `gradle` (same as CI). Standard tasks (`gradle compileJava|test|assemble|run`)
  are documented in `CLAUDE.md`.
- **The app boots without Kafka.** `/healthz` returns 200 and `/version` works even with no
  broker; `/readyz` returns 503 until Kafka is reachable, then 200. Kafka is the only hard
  runtime dependency, and it's only needed to produce real lag metrics.
- **Running against a broker (dev):** start Kafka, then
  `METRICS_REPORTER=prometheus KAFKA_BOOTSTRAP_SERVERS=localhost:9092 gradle run`.
  Metrics are served at `http://localhost:8888/metrics` (`HTTP_PORT` default 8888). Lag
  series only appear once a consumer group with committed offsets exists and a metrics
  cycle has run (`METRICS_INTERVAL_MS`, default 60000 — lower it for faster feedback).
  `METRICS_REPORTER` must be set (not `none`) for `/metrics` and `/mcp` snapshots to populate.
- **No broker is bundled.** `docker` is not installed, so `docker-compose.yaml` and the
  `scripts/e2e*.sh` / Helm / k3d flows need Docker/helm/kubectl installed first (optional,
  not required for JVM dev). A quick local broker: download Apache Kafka and run it in
  KRaft mode (`kafka-storage.sh format` then `kafka-server-start.sh config/kraft/server.properties`).
- The `website/` dir (Astro docs site, Node ≥22.12) is independent of the exporter and not
  needed to build/run/test Klag.
