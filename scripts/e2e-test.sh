#!/usr/bin/env bash
#
# End-to-end test for the Klag Helm chart.
#
# Spins up a disposable k3d cluster, deploys a real single-node Kafka (KRaft),
# builds the Klag image from the local Dockerfile, installs the Helm chart,
# generates real consumer-group lag, and asserts Klag actually scrapes it and
# exposes Prometheus metrics. This is a true e2e test: nothing is mocked and
# Klag's readiness probe only passes once it can talk to Kafka.
#
# Usage:
#   scripts/e2e-test.sh [options]
#
# Options:
#   -a, --auto-install   Install missing deps (k3d, kubectl, helm) via Homebrew
#   -s, --skip-cleanup   Keep the cluster running after the test
#   -c, --cleanup        Only delete the test cluster, then exit
#   -h, --help           Show this help
#
# Environment:
#   KLAG_IMAGE   Use this image instead of building locally (e.g. themoah/klag:0.1.12)
#
set -euo pipefail

CLUSTER_NAME="klag-e2e"
NAMESPACE="klag-e2e"
CHART_DIR="charts/klag"
RELEASE="klag"
KAFKA_IMAGE="apache/kafka:3.9.0"
LOCAL_IMAGE="klag:e2e"
TOPIC="e2e-topic"
GROUP="e2e-group"
MESSAGES=1000
CONSUME=100               # consume only part -> leaves committed offset behind -> lag
LOCAL_PORT=18888
PF_PID=""

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

AUTO_INSTALL=false
SKIP_CLEANUP=false
CLEANUP_ONLY=false

log()  { echo -e "${BLUE}==>${NC} $*"; }
ok()   { echo -e "${GREEN}  OK${NC} $*"; }
warn() { echo -e "${YELLOW}  !!${NC} $*"; }
die()  { echo -e "${RED}  XX${NC} $*" >&2; exit 1; }

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -a|--auto-install) AUTO_INSTALL=true; shift;;
    -s|--skip-cleanup) SKIP_CLEANUP=true; shift;;
    -c|--cleanup)      CLEANUP_ONLY=true; shift;;
    -h|--help)         usage;;
    *) die "Unknown option: $1";;
  esac
done

cleanup() {
  local code=$?
  if [[ -n "$PF_PID" ]] && kill -0 "$PF_PID" 2>/dev/null; then
    kill "$PF_PID" 2>/dev/null || true
  fi
  if [[ "$SKIP_CLEANUP" == true ]]; then
    warn "Skipping cleanup. Cluster '${CLUSTER_NAME}' left running (delete: $0 --cleanup)"
    return
  fi
  log "Cleaning up"
  k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 && ok "cluster deleted" || true
  exit $code
}

delete_cluster_only() {
  log "Deleting cluster '${CLUSTER_NAME}'"
  k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 && ok "deleted" || warn "not found"
  exit 0
}

need() {
  local cmd="$1" pkg="${2:-$1}"
  if command -v "$cmd" >/dev/null 2>&1; then return 0; fi
  if [[ "$AUTO_INSTALL" == true ]] && command -v brew >/dev/null 2>&1; then
    log "Installing $pkg via Homebrew"
    brew install "$pkg" >/dev/null 2>&1 && return 0
  fi
  die "Missing dependency: $cmd (install: brew install $pkg, or rerun with --auto-install)"
}

kc()  { kubectl --context "k3d-${CLUSTER_NAME}" -n "$NAMESPACE" "$@"; }
kkfk() { kc exec deploy/kafka -- /opt/kafka/bin/"$@"; }

# ----------------------------------------------------------------------------

if [[ "$CLEANUP_ONLY" == true ]]; then delete_cluster_only; fi

log "Checking prerequisites"
need docker docker
docker info >/dev/null 2>&1 || die "Docker daemon not running (start Docker Desktop)"
need k3d k3d
need kubectl kubectl
need helm helm
ok "docker, k3d, kubectl, helm present"

trap cleanup EXIT INT TERM

# ----------------------------------------------------------------------------
log "Creating k3d cluster '${CLUSTER_NAME}'"
k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 || true
k3d cluster create "$CLUSTER_NAME" --wait --timeout 120s >/dev/null
ok "cluster up"
kubectl --context "k3d-${CLUSTER_NAME}" create namespace "$NAMESPACE" >/dev/null
ok "namespace ${NAMESPACE} created"

# ----------------------------------------------------------------------------
if [[ -n "${KLAG_IMAGE:-}" ]]; then
  IMAGE_REPO="${KLAG_IMAGE%:*}"; IMAGE_TAG="${KLAG_IMAGE##*:}"
  PULL_POLICY="IfNotPresent"
  log "Using prebuilt image ${KLAG_IMAGE}"
else
  log "Building Klag image from local Dockerfile (this can take a few minutes)"
  docker build -t "$LOCAL_IMAGE" . >/dev/null
  ok "built ${LOCAL_IMAGE}"
  log "Importing image into k3d"
  k3d image import "$LOCAL_IMAGE" -c "$CLUSTER_NAME" >/dev/null
  ok "imported"
  IMAGE_REPO="klag"; IMAGE_TAG="e2e"; PULL_POLICY="Never"
fi

# ----------------------------------------------------------------------------
log "Deploying single-node Kafka (KRaft)"
cat <<EOF | kubectl --context "k3d-${CLUSTER_NAME}" -n "$NAMESPACE" apply -f - >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka
  labels: { app: kafka }
spec:
  replicas: 1
  selector: { matchLabels: { app: kafka } }
  template:
    metadata: { labels: { app: kafka } }
    spec:
      containers:
        - name: kafka
          image: ${KAFKA_IMAGE}
          ports:
            - { containerPort: 9092 }
          env:
            - { name: KAFKA_NODE_ID, value: "1" }
            - { name: KAFKA_PROCESS_ROLES, value: "broker,controller" }
            - { name: KAFKA_CONTROLLER_QUORUM_VOTERS, value: "1@localhost:9093" }
            - { name: KAFKA_LISTENERS, value: "PLAINTEXT://:9092,CONTROLLER://:9093" }
            - { name: KAFKA_ADVERTISED_LISTENERS, value: "PLAINTEXT://kafka:9092" }
            - { name: KAFKA_CONTROLLER_LISTENER_NAMES, value: "CONTROLLER" }
            - { name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP, value: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" }
            - { name: KAFKA_INTER_BROKER_LISTENER_NAME, value: "PLAINTEXT" }
            - { name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR, value: "1" }
            - { name: KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR, value: "1" }
            - { name: KAFKA_TRANSACTION_STATE_LOG_MIN_ISR, value: "1" }
            - { name: KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS, value: "0" }
          readinessProbe:
            tcpSocket: { port: 9092 }
            initialDelaySeconds: 5
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: kafka
spec:
  selector: { app: kafka }
  ports:
    - { name: kafka, port: 9092, targetPort: 9092 }
EOF
kc rollout status deploy/kafka --timeout=180s >/dev/null || die "Kafka failed to start"
ok "Kafka ready"

# ----------------------------------------------------------------------------
log "Generating consumer-group lag (topic=${TOPIC}, group=${GROUP})"
kkfk kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic "$TOPIC" --partitions 3 --replication-factor 1 >/dev/null
ok "topic created"

seq "$MESSAGES" | kc exec -i deploy/kafka -- \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null
ok "produced ${MESSAGES} messages"

# Consume only CONSUME messages with the group, then stop -> committed offset
# lags behind log-end-offset -> real lag for Klag to observe.
kkfk kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic "$TOPIC" --group "$GROUP" --from-beginning \
  --max-messages "$CONSUME" --timeout-ms 20000 >/dev/null 2>&1 || true
EXPECTED_LAG=$((MESSAGES - CONSUME))
ok "consumed ${CONSUME} -> expected lag ~${EXPECTED_LAG}"

# ----------------------------------------------------------------------------
log "Linting and validating chart against the live cluster"
helm lint "$CHART_DIR" >/dev/null || die "helm lint failed"
ok "helm lint"
helm template "$RELEASE" "$CHART_DIR" -n "$NAMESPACE" \
  | kubectl --context "k3d-${CLUSTER_NAME}" apply --dry-run=server -f - >/dev/null \
  || die "server-side manifest validation failed"
ok "server-side dry-run validation (no longer SKIPPED)"

# ----------------------------------------------------------------------------
log "Installing Klag Helm chart"
helm --kube-context "k3d-${CLUSTER_NAME}" install "$RELEASE" "$CHART_DIR" \
  -n "$NAMESPACE" \
  --set image.repository="$IMAGE_REPO" \
  --set image.tag="$IMAGE_TAG" \
  --set image.pullPolicy="$PULL_POLICY" \
  --set kafka.bootstrapServers="kafka:9092" \
  --set metrics.reporter="prometheus" \
  --set metrics.intervalMs=5000 \
  --set app.healthCheckIntervalMs=5000 \
  --wait --timeout 180s >/dev/null \
  || { kc describe pods -l app.kubernetes.io/name=klag; kc logs -l app.kubernetes.io/name=klag --tail=50; die "helm install failed (Klag did not become ready -> could not reach Kafka)"; }
ok "Klag deployed and readiness probe passed (Kafka reachable)"

# ----------------------------------------------------------------------------
log "Scraping Klag /metrics and asserting real lag is reported"
kc port-forward "svc/${RELEASE}" "${LOCAL_PORT}:8888" >/dev/null 2>&1 &
PF_PID=$!
sleep 3

# Give Klag a couple of collection intervals (5s each) to scrape the group.
METRICS=""
for _ in $(seq 1 12); do
  METRICS="$(curl -fsS "http://localhost:${LOCAL_PORT}/metrics" 2>/dev/null || true)"
  if grep -q "$GROUP" <<<"$METRICS" && grep -q "klag_consumer_lag" <<<"$METRICS"; then
    break
  fi
  sleep 5
done

assert() {
  local desc="$1" pat="$2"
  if grep -q "$pat" <<<"$METRICS"; then ok "$desc"; else
    echo "$METRICS" | grep -i klag | head -30 >&2
    die "ASSERTION FAILED: $desc (pattern: $pat)"
  fi
}

curl -fsS "http://localhost:${LOCAL_PORT}/healthz" >/dev/null && ok "/healthz 200" || die "/healthz failed"
curl -fsS "http://localhost:${LOCAL_PORT}/readyz"  >/dev/null && ok "/readyz 200 (Kafka UP)" || die "/readyz failed"

assert "klag_consumer_lag metric exposed"          "klag_consumer_lag"
assert "lag tracked for group ${GROUP}"            "consumer_group=\"${GROUP}\""
assert "lag tracked for topic ${TOPIC}"            "topic=\"${TOPIC}\""
assert "log-end-offset metric exposed"             "klag_partition_log_end_offset"
assert "consumer group state metric exposed"       "klag_consumer_group_state"

# Verify the summed lag for the group is non-trivial (real lag, not zero).
LAG_SUM_LINE="$(grep "klag_consumer_lag_sum" <<<"$METRICS" | grep "$GROUP" | head -1 || true)"
if [[ -n "$LAG_SUM_LINE" ]]; then
  LAG_VAL="$(awk '{printf "%d", $NF}' <<<"$LAG_SUM_LINE")"
  if [[ "${LAG_VAL:-0}" -gt 0 ]]; then
    ok "observed lag sum = ${LAG_VAL} (>0, expected ~${EXPECTED_LAG})"
  else
    die "lag sum reported as ${LAG_VAL}; expected > 0"
  fi
else
  warn "klag_consumer_lag_sum line not found; relying on presence assertions above"
fi

kill "$PF_PID" 2>/dev/null || true; PF_PID=""

echo ""
echo -e "${GREEN}=== E2E PASSED: Klag deployed on k3d, connected to real Kafka, and reported real consumer lag ===${NC}"
