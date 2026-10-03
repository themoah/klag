---
title: Contributing
description: How to contribute to Klag. First-timers welcome — pick a labeled issue, run the tests, open a pull request.
---

Contributions are welcome. Prefer a **linked GitHub issue** over an unsolicited PR.

## First timers

1. Browse open [`good first issue`](https://github.com/themoah/klag/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) or [`hacktoberfest`](https://github.com/themoah/klag/issues?q=is%3Aissue+is%3Aopen+label%3Ahacktoberfest) tickets. Comment to claim one.
2. Fork the repository and create a feature branch from `main`.
3. Requires **Java 21**. Run the unit tests:
   ```bash
   ./gradlew test
   ```
4. Open a pull request that references the issue.

See [Build from Source](/development/build/) for Helm, e2e, native-image, and website commands.

For Hacktoberfest, use the [`hacktoberfest`](https://github.com/themoah/klag/issues?q=is%3Aissue+is%3Aopen+label%3Ahacktoberfest) issue list and the pinned [contributor board](https://github.com/themoah/klag/issues/97). Issues are curated.

## Workflow

1. Fork the repository.
2. Create a feature branch.
3. Run the tests relevant to your change before submitting:
   ```bash
   ./gradlew test                # Java tests
   ./scripts/test-helm-chart.sh  # Helm chart template tests
   ./scripts/e2e-test.sh         # End-to-end (k3d + real Kafka; slow, needs Docker)
   ```
   Website or docs changes:
   ```bash
   cd website && npm ci && npm test && npm run check
   ```
4. Open a pull request.

## Quality bar

- Link the issue you are fixing or implementing.
- Add or update tests for non-trivial behavior changes.
- **Drive-by typo / whitespace-only PRs will be closed** unless they fix something substantive or were agreed on an issue first.
- Keep diffs focused; match existing style (Vert.x `Future<T>`, Java 21 records, SLF4J).

## Conventions

- Async operations return `Future<T>` (Vert.x); keep APIs non-blocking.
- Java 21 records for DTO-style model types.
- SLF4J + Logback for logging, no `stdout` from application code.
- Config resolution order: classpath → external file → environment variables (and `Env`-backed `-D` properties).

## When adding a metric

Metric names and tags are part of the public API. When you add, rename, or retag a
metric, also update the reporter/collector and its tests, the `README.md` and `AGENTS.md`
metric docs, the [Grafana dashboard](/integrations/grafana-dashboard/)
(`dashboard/demo-dashboard.json`), and, if Helm/ServiceMonitor behavior changes, the
chart values, templates, README, and Helm tests.
