# Local integration stack

`docker-compose.yaml` in the repo root brings up a complete local loop:

| Service | What it does | URL |
|---------|--------------|-----|
| `kafka` | Single-broker KRaft Kafka (no Zookeeper) | `localhost:9092` |
| `klag` | Built from the local `Dockerfile` | http://localhost:8888/metrics |
| `prometheus` | Scrapes Klag every 10s (`docker/prometheus/prometheus.yml`) | http://localhost:9090 |
| `grafana` | Prometheus datasource + Klag dashboard pre-provisioned | http://localhost:3000 (admin / admin) |
| `producer` | Writes to `test-topic` at ~5 msg/s | |
| `slow-consumer` | Commits one message every 10s, so lag builds | |
| `no-commit-consumer` | Never commits, exercising null-offset handling | |
| `delayed-commit-consumer` | Commits nothing for 150s, then recovers | |

## Quick start

From the repository root:

```bash
docker compose up -d --build
```

Then open:

1. **Grafana** at http://localhost:3000 (login `admin` / `admin`). Open the **Klag** folder and the
   Klag dashboard. Lag appears within a minute or two.
2. **Prometheus** at http://localhost:9090. Try `klag_consumer_lag_sum`.
3. **Klag** directly at http://localhost:8888/metrics, or http://localhost:8888/readyz for readiness.

Stop and remove everything:

```bash
docker compose down -v
```

## Notes

- The dashboard is `dashboard/klag-grafana-com.json` (the grafana.com export, Grafana 11.x schema).
  It is mounted read-only into Grafana. Edits made in the UI are kept in the container only.
- Grafana is pinned to 11.3.0 to match that dashboard's schema. Prometheus is pinned to v3.5.0.
- Prometheus and Grafana are published only on the host loopback interface; they are reachable
  from this machine at `localhost`, but not directly from other machines on the network.
- Klag reads from `kafka:29092` inside the compose network. Host clients use `localhost:9092`.
- To run only the broker and Klag, start `kafka` and `klag`:
  `docker compose up -d --build kafka klag`.
- Ports 8888, 9090, 3000 and 9092 must be free on the host.
