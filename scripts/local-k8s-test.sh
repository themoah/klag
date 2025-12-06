#!/bin/bash

# Local Kubernetes test script for macOS
# Sets up a kind cluster and installs the Klag Helm chart

CLUSTER_NAME="klag-test"
CHART_DIR="charts/klag"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== Klag Local Kubernetes Test ==="
echo ""

check_command() {
    local cmd="$1"
    local install_cmd="$2"

    echo -n "Checking for ${cmd}... "
    if command -v "$cmd" &> /dev/null; then
        echo -e "${GREEN}found${NC}"
        return 0
    else
        echo -e "${RED}not found${NC}"
        if [ -n "$install_cmd" ]; then
            echo "  Install with: ${install_cmd}"
        fi
        return 1
    fi
}

install_if_missing() {
    local cmd="$1"
    local brew_package="${2:-$1}"

    if ! command -v "$cmd" &> /dev/null; then
        echo -n "Installing ${brew_package} via Homebrew... "
        if brew install "$brew_package" &> /dev/null; then
            echo -e "${GREEN}done${NC}"
            return 0
        else
            echo -e "${RED}failed${NC}"
            return 1
        fi
    fi
    return 0
}

cleanup() {
    echo ""
    echo "--- Cleanup ---"
    echo -n "Deleting kind cluster '${CLUSTER_NAME}'... "
    if kind delete cluster --name "$CLUSTER_NAME" &> /dev/null; then
        echo -e "${GREEN}done${NC}"
    else
        echo -e "${YELLOW}cluster not found or already deleted${NC}"
    fi
}

# Parse arguments
AUTO_INSTALL=false
CLEANUP_ONLY=false
SKIP_CLEANUP=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --auto-install|-a)
            AUTO_INSTALL=true
            shift
            ;;
        --cleanup|-c)
            CLEANUP_ONLY=true
            shift
            ;;
        --skip-cleanup|-s)
            SKIP_CLEANUP=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  -a, --auto-install  Automatically install missing dependencies via Homebrew"
            echo "  -c, --cleanup       Only cleanup (delete the kind cluster)"
            echo "  -s, --skip-cleanup  Skip cleanup after test (keep cluster running)"
            echo "  -h, --help          Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Handle cleanup-only mode
if [ "$CLEANUP_ONLY" = true ]; then
    cleanup
    exit 0
fi

echo "--- Checking Prerequisites ---"

# Check for Homebrew (macOS)
if [[ "$OSTYPE" == "darwin"* ]]; then
    check_command "brew" '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"' || {
        echo -e "${RED}Homebrew is required on macOS${NC}"
        exit 1
    }
fi

# Check/install dependencies
MISSING_DEPS=false

if ! check_command "docker" "brew install --cask docker"; then
    echo -e "${YELLOW}Docker Desktop must be installed and running${NC}"
    MISSING_DEPS=true
fi

if ! check_command "kind" "brew install kind"; then
    if [ "$AUTO_INSTALL" = true ] && [[ "$OSTYPE" == "darwin"* ]]; then
        install_if_missing "kind" || MISSING_DEPS=true
    else
        MISSING_DEPS=true
    fi
fi

if ! check_command "helm" "brew install helm"; then
    if [ "$AUTO_INSTALL" = true ] && [[ "$OSTYPE" == "darwin"* ]]; then
        install_if_missing "helm" || MISSING_DEPS=true
    else
        MISSING_DEPS=true
    fi
fi

if ! check_command "kubectl" "brew install kubectl"; then
    if [ "$AUTO_INSTALL" = true ] && [[ "$OSTYPE" == "darwin"* ]]; then
        install_if_missing "kubectl" || MISSING_DEPS=true
    else
        MISSING_DEPS=true
    fi
fi

if [ "$MISSING_DEPS" = true ]; then
    echo ""
    echo -e "${RED}Missing dependencies. Install them or run with --auto-install${NC}"
    exit 1
fi

# Check Docker is running
echo -n "Checking Docker daemon... "
if docker info &> /dev/null; then
    echo -e "${GREEN}running${NC}"
else
    echo -e "${RED}not running${NC}"
    echo "  Please start Docker Desktop"
    exit 1
fi

echo ""
echo "--- Setting up Kind Cluster ---"

# Check if cluster already exists
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    echo -n "Cluster '${CLUSTER_NAME}' already exists. Deleting... "
    kind delete cluster --name "$CLUSTER_NAME" &> /dev/null
    echo -e "${GREEN}done${NC}"
fi

# Create kind cluster
echo -n "Creating kind cluster '${CLUSTER_NAME}'... "
if kind create cluster --name "$CLUSTER_NAME" --wait 60s &> /dev/null; then
    echo -e "${GREEN}done${NC}"
else
    echo -e "${RED}failed${NC}"
    exit 1
fi

# Set kubectl context
echo -n "Setting kubectl context... "
kubectl cluster-info --context "kind-${CLUSTER_NAME}" &> /dev/null
echo -e "${GREEN}done${NC}"

echo ""
echo "--- Installing Klag Helm Chart ---"

# Install the chart
echo -n "Installing Helm chart... "
if helm install klag "$CHART_DIR" \
    --set kafka.bootstrapServers="kafka-broker:9092" \
    --set metrics.reporter="prometheus" \
    --wait --timeout 60s &> /dev/null; then
    echo -e "${GREEN}done${NC}"
else
    echo -e "${RED}failed${NC}"
    echo "Helm install output:"
    helm install klag "$CHART_DIR" \
        --set kafka.bootstrapServers="kafka-broker:9092" \
        --set metrics.reporter="prometheus" 2>&1
    cleanup
    exit 1
fi

echo ""
echo "--- Verifying Deployment ---"

# Check deployment status
echo -n "Checking deployment... "
if kubectl get deployment klag -o jsonpath='{.status.availableReplicas}' 2>/dev/null | grep -q "1"; then
    echo -e "${GREEN}ready${NC}"
else
    echo -e "${YELLOW}pending (expected - no Kafka available)${NC}"
fi

# Show pod status
echo ""
echo "Pod status:"
kubectl get pods -l app.kubernetes.io/name=klag

# Show service
echo ""
echo "Service:"
kubectl get svc klag

# Test helm template validation with kubectl
echo ""
echo "--- Kubernetes Manifest Validation ---"
echo -n "Validating manifests against cluster... "
if helm template klag "$CHART_DIR" | kubectl apply --dry-run=server -f - &> /dev/null; then
    echo -e "${GREEN}passed${NC}"
else
    echo -e "${RED}failed${NC}"
fi

echo ""
echo "--- Port Forward Instructions ---"
echo "To access Klag locally, run:"
echo "  kubectl port-forward svc/klag 8888:8888"
echo ""
echo "Then access:"
echo "  http://localhost:8888/healthz  - Liveness probe"
echo "  http://localhost:8888/readyz   - Readiness probe"
echo "  http://localhost:8888/metrics  - Prometheus metrics"

# Cleanup unless skipped
if [ "$SKIP_CLEANUP" = true ]; then
    echo ""
    echo -e "${YELLOW}Skipping cleanup. Cluster '${CLUSTER_NAME}' is still running.${NC}"
    echo "To cleanup later, run: $0 --cleanup"
else
    cleanup
fi

echo ""
echo -e "${GREEN}=== Local test completed ===${NC}"
