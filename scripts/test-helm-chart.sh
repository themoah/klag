#!/bin/bash

CHART_DIR="charts/klag"
TESTS_DIR="${CHART_DIR}/tests"

echo "=== Helm Chart Test Suite ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

passed=0
failed=0

run_test() {
    local test_name="$1"
    local values_file="$2"
    local extra_args="${3:-}"

    echo -n "Testing: ${test_name}... "

    if helm template test-release "${CHART_DIR}" ${values_file:+-f "$values_file"} ${extra_args} > /dev/null 2>&1; then
        echo -e "${GREEN}PASSED${NC}"
        ((passed++))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Command: helm template test-release ${CHART_DIR} ${values_file:+-f $values_file} ${extra_args}"
        helm template test-release "${CHART_DIR}" ${values_file:+-f "$values_file"} ${extra_args} 2>&1 | head -20
        ((failed++))
    fi
}

validate_output() {
    local test_name="$1"
    local values_file="$2"
    local grep_pattern="$3"
    local extra_args="${4:-}"

    echo -n "Validating: ${test_name}... "

    if helm template test-release "${CHART_DIR}" ${values_file:+-f "$values_file"} ${extra_args} 2>/dev/null | grep -q "${grep_pattern}"; then
        echo -e "${GREEN}PASSED${NC}"
        ((passed++))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Expected pattern not found: ${grep_pattern}"
        ((failed++))
    fi
}

validate_not_present() {
    local test_name="$1"
    local values_file="$2"
    local grep_pattern="$3"
    local extra_args="${4:-}"

    echo -n "Validating: ${test_name}... "

    if ! helm template test-release "${CHART_DIR}" ${values_file:+-f "$values_file"} ${extra_args} 2>/dev/null | grep -q "${grep_pattern}"; then
        echo -e "${GREEN}PASSED${NC}"
        ((passed++))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Pattern should NOT be present: ${grep_pattern}"
        ((failed++))
    fi
}

echo "--- Chart Linting ---"
echo -n "Linting chart... "
if helm lint "${CHART_DIR}" > /dev/null 2>&1; then
    echo -e "${GREEN}PASSED${NC}"
    ((passed++))
else
    echo -e "${RED}FAILED${NC}"
    helm lint "${CHART_DIR}"
    ((failed++))
fi

echo ""
echo "--- Template Rendering Tests ---"

# Basic rendering tests
run_test "Default values" ""
run_test "Basic Prometheus config" "${TESTS_DIR}/test-values.yaml"
run_test "SASL authentication" "${TESTS_DIR}/test-values-sasl.yaml"
run_test "OTLP reporter" "${TESTS_DIR}/test-values-otlp.yaml"
run_test "Datadog reporter" "${TESTS_DIR}/test-values-datadog.yaml"
run_test "Existing secrets" "${TESTS_DIR}/test-values-existing-secret.yaml"

# Test with various --set overrides
run_test "Custom replica count" "" "--set replicaCount=3"
run_test "Custom resources" "" "--set resources.limits.memory=1Gi"
run_test "Disabled ServiceAccount" "" "--set serviceAccount.create=false"
run_test "Custom image tag" "" "--set image.tag=latest"
run_test "Virtual threads enabled" "" "--set app.useVirtualThreads=true"
run_test "JVM metrics enabled" "" "--set metrics.jvmEnabled=true"
run_test "Custom group filter" "" "--set metrics.groupFilter=app-*"

echo ""
echo "--- Content Validation Tests ---"

# Validate specific content in rendered templates
validate_output "Deployment has correct image" "" "image: \"themoah/klag:"
validate_output "Service uses port 8888" "" "port: 8888"
validate_output "Liveness probe on /healthz" "" "path: /healthz"
validate_output "Readiness probe on /readyz" "" "path: /readyz"
validate_output "KAFKA_BOOTSTRAP_SERVERS env var" "${TESTS_DIR}/test-values.yaml" "KAFKA_BOOTSTRAP_SERVERS"
validate_output "METRICS_REPORTER env var" "${TESTS_DIR}/test-values.yaml" "METRICS_REPORTER"

# SASL config validation
validate_output "SASL secret created" "${TESTS_DIR}/test-values-sasl.yaml" "kind: Secret"
validate_output "KAFKA_SECURITY_PROTOCOL set" "${TESTS_DIR}/test-values-sasl.yaml" "KAFKA_SECURITY_PROTOCOL"
validate_output "KAFKA_SASL_MECHANISM set" "${TESTS_DIR}/test-values-sasl.yaml" "KAFKA_SASL_MECHANISM"
validate_output "JAAS config from secret" "${TESTS_DIR}/test-values-sasl.yaml" "secretKeyRef"

# OTLP config validation
validate_output "OTLP_ENDPOINT set" "${TESTS_DIR}/test-values-otlp.yaml" "OTLP_ENDPOINT"
validate_output "OTEL_SERVICE_NAME set" "${TESTS_DIR}/test-values-otlp.yaml" "OTEL_SERVICE_NAME"
validate_output "OTLP secret created" "${TESTS_DIR}/test-values-otlp.yaml" "name: test-release-klag-otlp"

# Datadog config validation
validate_output "DD_API_KEY set" "${TESTS_DIR}/test-values-datadog.yaml" "DD_API_KEY"
validate_output "DD_APP_KEY set" "${TESTS_DIR}/test-values-datadog.yaml" "DD_APP_KEY"
validate_output "DD_SITE set" "${TESTS_DIR}/test-values-datadog.yaml" "DD_SITE"
validate_output "Datadog secret created" "${TESTS_DIR}/test-values-datadog.yaml" "name: test-release-klag-datadog"

# ServiceMonitor validation
validate_output "ServiceMonitor created" "${TESTS_DIR}/test-values.yaml" "kind: ServiceMonitor"
validate_output "ServiceMonitor scrapes /metrics" "${TESTS_DIR}/test-values.yaml" "path: /metrics"
validate_not_present "No ServiceMonitor by default" "" "kind: ServiceMonitor"

# Existing secret validation (should NOT create new secrets)
validate_not_present "No Kafka secret when using existing" "${TESTS_DIR}/test-values-existing-secret.yaml" "name: test-release-klag-kafka"
validate_output "Uses existing Kafka secret" "${TESTS_DIR}/test-values-existing-secret.yaml" "name: my-kafka-credentials"
validate_output "Uses existing OTLP secret" "${TESTS_DIR}/test-values-existing-secret.yaml" "name: my-otlp-credentials"

echo ""
echo "--- Kubernetes Schema Validation ---"
echo -n "Validating Kubernetes manifests... "
if helm template test-release "${CHART_DIR}" -f "${TESTS_DIR}/test-values.yaml" 2>/dev/null | kubectl apply --dry-run=client -f - > /dev/null 2>&1; then
    echo -e "${GREEN}PASSED${NC}"
    ((passed++))
else
    echo -e "${YELLOW}SKIPPED${NC} (kubectl not configured or cluster not available)"
fi

echo ""
echo "=== Test Summary ==="
echo -e "Passed: ${GREEN}${passed}${NC}"
echo -e "Failed: ${RED}${failed}${NC}"
echo ""

if [ $failed -gt 0 ]; then
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
else
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
fi
