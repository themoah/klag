#!/usr/bin/env bash
#
# Strimzi compatibility e2e test for the Klag Helm chart.
#
# Verifies that Klag works against a Kafka cluster provisioned by the Strimzi
# operator (the most common production way to run Kafka on Kubernetes). Spins up
# a disposable k3d cluster, installs the Strimzi cluster operator, creates a
# KRaft Kafka cluster via Strimzi CRDs, builds the Klag image, installs the Helm
# chart pointed at the Strimzi bootstrap service, generates real consumer-group
# lag, and asserts Klag scrapes and exposes it.
#
# Nothing is mocked: Klag's readiness probe only passes once it connects to the
# Strimzi-managed Kafka, and lag is asserted from /metrics.
#
# Usage:
#   scripts/e2e-strimzi-test.sh [options]
#
# Options:
#   -a, --auto-install   Install missing deps (k3d, kubectl, helm) via Homebrew
#   -s, --skip-cleanup   Keep the cluster running after the test
#   -c, --cleanup        Only delete the test cluster, then exit
#   -h, --help           Show this help
#
# Environment:
#   KLAG_IMAGE       Use this image instead of building locally
#   STRIMZI_VERSION  Strimzi operator version (default: latest)
#   KAFKA_VERSION    Kafka version for the Strimzi Kafka CR (default: 3.9.0)
#
set -euo pipefail

CLUSTER_NAME="klag-strimzi"
NAMESPACE="klag-strimzi"
CHART_DIR="charts/klag"
RELEASE="klag"
KAFKA_CLUSTER="my-cluster"
BOOTSTRAP="my-cluster-kafka-bootstrap:9092"
CLIENT_IMAGE="apache/kafka:4.2.0"     # used only as a Kafka CLI client
LOCAL_IMAGE="klag:e2e"
TOPIC="strimzi-topic"
GROUP="strimzi-group"
MESSAGES=1000
CONSUME=100
LOCAL_PORT=18889
KAFKA_VERSION="${KAFKA_VERSION:-4.2.0}"
STRIMZI_VERSION="${STRIMZI_VERSION:-latest}"
PF_PID=""

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

AUTO_INSTALL=false; SKIP_CLEANUP=false; CLEANUP_ONLY=false

log()  { echo -e "${BLUE}==>${NC} $*"; }
ok()   { echo -e "${GREEN}  OK${NC} $*"; }
warn() { echo -e "${YELLOW}  !!${NC} $*"; }
die()  { echo -e "${RED}  XX${NC} $*" >&2; exit 1; }
usage(){ sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

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
  [[ -n "$PF_PID" ]] && kill "$PF_PID" 2>/dev/null || true
  if [[ "$SKIP_CLEANUP" == true ]]; then
    warn "Skipping cleanup. Cluster '${CLUSTER_NAME}' left running (delete: $0 --cleanup)"; return
  fi
  log "Cleaning up"
  k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 && ok "cluster deleted" || true
  exit $code
}
delete_cluster_only(){ log "Deleting cluster '${CLUSTER_NAME}'"; k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 && ok deleted || warn "not found"; exit 0; }

need() {
  local cmd="$1" pkg="${2:-$1}"
  command -v "$cmd" >/dev/null 2>&1 && return 0
  if [[ "$AUTO_INSTALL" == true ]] && command -v brew >/dev/null 2>&1; then
    log "Installing $pkg via Homebrew"; brew install "$pkg" >/dev/null 2>&1 && return 0
  fi
  die "Missing dependency: $cmd (install: brew install $pkg, or rerun with --auto-install)"
}

kc()   { kubectl --context "k3d-${CLUSTER_NAME}" -n "$NAMESPACE" "$@"; }
kcli() { kc exec deploy/kafka-client -- /opt/kafka/bin/"$@"; }

# kubectl caches API discovery on disk (10-min TTL); after installing the
# Strimzi CRDs a fresh `apply` may not yet resolve the new kinds. Drop the cache
# so the next call rebuilds discovery from the live API server.
refresh_discovery(){ rm -rf "${HOME}/.kube/cache/discovery" "${HOME}/.kube/cache/http" 2>/dev/null || true; }

# Apply a manifest, retrying while the API still reports "no matches for kind"
# (new CRD not yet visible to the client).
apply_retry(){
  local manifest="$1" i out
  for i in $(seq 1 18); do
    if out="$(printf '%s' "$manifest" | kc apply -f - 2>&1)"; then return 0; fi
    grep -q "no matches for kind\|ensure CRDs" <<<"$out" || { echo "$out" >&2; return 1; }
    refresh_discovery; sleep 5
  done
  echo "$out" >&2; return 1
}

# ----------------------------------------------------------------------------
[[ "$CLEANUP_ONLY" == true ]] && delete_cluster_only

log "Checking prerequisites"
need docker docker
docker info >/dev/null 2>&1 || die "Docker daemon not running"
need k3d k3d; need kubectl kubectl; need helm helm
ok "docker, k3d, kubectl, helm present"

trap cleanup EXIT INT TERM

log "Creating k3d cluster '${CLUSTER_NAME}'"
k3d cluster delete "$CLUSTER_NAME" >/dev/null 2>&1 || true
k3d cluster create "$CLUSTER_NAME" --wait --timeout 120s >/dev/null
ok "cluster up"
kubectl --context "k3d-${CLUSTER_NAME}" create namespace "$NAMESPACE" >/dev/null
ok "namespace ${NAMESPACE} created"

# ----------------------------------------------------------------------------
if [[ -n "${KLAG_IMAGE:-}" ]]; then
  IMAGE_REPO="${KLAG_IMAGE%:*}"; IMAGE_TAG="${KLAG_IMAGE##*:}"; PULL_POLICY="IfNotPresent"
  log "Using prebuilt image ${KLAG_IMAGE}"
else
  log "Building Klag image from local Dockerfile (can take a few minutes)"
  docker build -t "$LOCAL_IMAGE" . >/dev/null; ok "built ${LOCAL_IMAGE}"
  log "Importing image into k3d"; k3d image import "$LOCAL_IMAGE" -c "$CLUSTER_NAME" >/dev/null; ok imported
  IMAGE_REPO="klag"; IMAGE_TAG="e2e"; PULL_POLICY="Never"
fi

# ----------------------------------------------------------------------------
log "Installing Strimzi cluster operator (${STRIMZI_VERSION})"
kubectl --context "k3d-${CLUSTER_NAME}" create -f \
  "https://strimzi.io/install/${STRIMZI_VERSION}?namespace=${NAMESPACE}" -n "$NAMESPACE" >/dev/null
kc rollout status deploy/strimzi-cluster-operator --timeout=300s >/dev/null \
  || die "Strimzi operator did not become ready"
ok "Strimzi operator ready"

# CRDs are created by the install bundle but API discovery may lag; wait until
# the Kafka/KafkaNodePool CRDs are Established before applying the custom resources.
kubectl --context "k3d-${CLUSTER_NAME}" wait --for=condition=Established --timeout=120s \
  crd/kafkas.kafka.strimzi.io crd/kafkanodepools.kafka.strimzi.io >/dev/null \
  || die "Strimzi CRDs not established"
refresh_discovery
ok "Strimzi CRDs established"

# ----------------------------------------------------------------------------
log "Creating Strimzi Kafka cluster '${KAFKA_CLUSTER}' (KRaft, Kafka ${KAFKA_VERSION})"
KAFKA_CR="$(cat <<EOF
apiVersion: kafka.strimzi.io/v1
kind: KafkaNodePool
metadata:
  name: dual-role
  labels:
    strimzi.io/cluster: ${KAFKA_CLUSTER}
spec:
  replicas: 1
  roles: [controller, broker]
  storage:
    type: ephemeral
---
apiVersion: kafka.strimzi.io/v1
kind: Kafka
metadata:
  name: ${KAFKA_CLUSTER}
  annotations:
    strimzi.io/node-pools: enabled
    strimzi.io/kraft: enabled
spec:
  kafka:
    version: ${KAFKA_VERSION}
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
  entityOperator:
    topicOperator: {}
    userOperator: {}
EOF
)"
apply_retry "$KAFKA_CR" >/dev/null || die "Failed to apply Strimzi Kafka CRs"
ok "Kafka CRs applied"

log "Waiting for Strimzi Kafka to become Ready (operator provisions pods)"
if ! kc wait "kafka/${KAFKA_CLUSTER}" --for=condition=Ready --timeout=600s >/dev/null 2>&1; then
  kc get kafka "${KAFKA_CLUSTER}" -o yaml | grep -A30 "status:" >&2 || true
  die "Strimzi Kafka cluster did not become Ready"
fi
ok "Strimzi Kafka Ready (bootstrap: ${BOOTSTRAP})"

# ----------------------------------------------------------------------------
log "Deploying Kafka CLI client (to drive topic/producer/consumer)"
cat <<EOF | kubectl --context "k3d-${CLUSTER_NAME}" -n "$NAMESPACE" apply -f - >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka-client
  labels: { app: kafka-client }
spec:
  replicas: 1
  selector: { matchLabels: { app: kafka-client } }
  template:
    metadata: { labels: { app: kafka-client } }
    spec:
      containers:
        - name: client
          image: ${CLIENT_IMAGE}
          command: ["sleep", "infinity"]
EOF
kc rollout status deploy/kafka-client --timeout=120s >/dev/null || die "client pod failed"
ok "client ready"

# ----------------------------------------------------------------------------
log "Generating consumer-group lag (topic=${TOPIC}, group=${GROUP})"
kcli kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
  --create --topic "$TOPIC" --partitions 3 --replication-factor 1 >/dev/null
ok "topic created"
seq "$MESSAGES" | kc exec -i deploy/kafka-client -- \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$BOOTSTRAP" --topic "$TOPIC" >/dev/null
ok "produced ${MESSAGES} messages"
kcli kafka-console-consumer.sh --bootstrap-server "$BOOTSTRAP" \
  --topic "$TOPIC" --group "$GROUP" --from-beginning \
  --max-messages "$CONSUME" --timeout-ms 20000 >/dev/null 2>&1 || true
EXPECTED_LAG=$((MESSAGES - CONSUME))
ok "consumed ${CONSUME} -> expected lag ~${EXPECTED_LAG}"

# ----------------------------------------------------------------------------
log "Installing Klag Helm chart pointed at Strimzi bootstrap"
helm --kube-context "k3d-${CLUSTER_NAME}" install "$RELEASE" "$CHART_DIR" \
  -n "$NAMESPACE" \
  --set image.repository="$IMAGE_REPO" \
  --set image.tag="$IMAGE_TAG" \
  --set image.pullPolicy="$PULL_POLICY" \
  --set kafka.bootstrapServers="$BOOTSTRAP" \
  --set metrics.reporter="prometheus" \
  --set metrics.intervalMs=5000 \
  --set app.healthCheckIntervalMs=5000 \
  --wait --timeout 180s >/dev/null \
  || { kc describe pods -l app.kubernetes.io/name=klag; kc logs -l app.kubernetes.io/name=klag --tail=50; die "Klag did not become ready -> could not reach Strimzi Kafka"; }
ok "Klag deployed and readiness probe passed (connected to Strimzi Kafka)"

# ----------------------------------------------------------------------------
log "Scraping Klag /metrics and asserting lag from the Strimzi cluster"
kc port-forward "svc/${RELEASE}" "${LOCAL_PORT}:8888" >/dev/null 2>&1 &
PF_PID=$!; sleep 3
METRICS=""
for _ in $(seq 1 12); do
  METRICS="$(curl -fsS "http://localhost:${LOCAL_PORT}/metrics" 2>/dev/null || true)"
  if grep -q "$GROUP" <<<"$METRICS" && grep -q "klag_consumer_lag" <<<"$METRICS"; then break; fi
  sleep 5
done

assert(){ local d="$1" p="$2"; if grep -q "$p" <<<"$METRICS"; then ok "$d"; else echo "$METRICS" | grep -i klag | head -30 >&2; die "ASSERTION FAILED: $d (pattern: $p)"; fi; }

curl -fsS "http://localhost:${LOCAL_PORT}/readyz" >/dev/null && ok "/readyz 200 (Strimzi Kafka UP)" || die "/readyz failed"
assert "klag_consumer_lag metric exposed"     "klag_consumer_lag"
assert "lag tracked for group ${GROUP}"       "consumer_group=\"${GROUP}\""
assert "lag tracked for topic ${TOPIC}"       "topic=\"${TOPIC}\""
assert "log-end-offset metric exposed"        "klag_partition_log_end_offset"
assert "consumer group state metric exposed"  "klag_consumer_group_state"

LAG_LINE="$(grep "klag_consumer_lag_sum" <<<"$METRICS" | grep "$GROUP" | head -1 || true)"
if [[ -n "$LAG_LINE" ]]; then
  LAG_VAL="$(awk '{printf "%d", $NF}' <<<"$LAG_LINE")"
  [[ "${LAG_VAL:-0}" -gt 0 ]] && ok "observed lag sum = ${LAG_VAL} (>0, expected ~${EXPECTED_LAG})" || die "lag sum=${LAG_VAL}; expected >0"
else
  warn "klag_consumer_lag_sum line not found; relying on presence assertions"
fi

kill "$PF_PID" 2>/dev/null || true; PF_PID=""
echo ""
echo -e "${GREEN}=== STRIMZI E2E PASSED: Klag chart is compatible with Strimzi-managed Kafka ===${NC}"
