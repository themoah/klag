---
title: Contributing
description: How to contribute to Klag. Fork, branch, run the test suites, and open a pull request.
---

Contributions are welcome.

## Workflow

1. Fork the repository.
2. Create a feature branch.
3. Run the tests before submitting:
   ```bash
   ./gradlew test                # Java tests
   ./scripts/test-helm-chart.sh  # Helm chart template tests
   ./scripts/e2e-test.sh         # End-to-end (k3d + real Kafka)
   ```
4. Open a pull request.

See [Build from Source](/development/build/) for the full set of build and test commands.

## Conventions

- Async operations return `Future<T>` (Vert.x); keep APIs non-blocking.
- Java 21 records for DTO-style model types.
- SLF4J + Logback for logging, no `stdout` from application code.
- Config resolution order: classpath → external file → environment variables.

## When adding a metric

Metric names and tags are part of the public API. When you add, rename, or retag a
metric, also update the reporter/collector and its tests, the `README.md` and `CLAUDE.md`
metric docs, the [Grafana dashboard](/integrations/grafana-dashboard/)
(`dashboard/demo-dashboard.json`), and, if Helm/ServiceMonitor behavior changes, the
chart values, templates, README, and Helm tests.

The repo's [Agent Skill](/ai/agent-skill/) encodes these checklists for AI coding agents.
