#!/bin/bash

set -e

export ENV=${1:-usgw1staging}
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"

# Check if gradle wrapper jar exists, if not regenerate it
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "Gradle wrapper jar not found, regenerating..."
  gradle wrapper
fi
./gradlew clean assemble

# Clean up existing containers and images
docker stop klag-app 2>/dev/null || true
docker rm klag-app 2>/dev/null || true
docker rmi klag:latest 2>/dev/null || true
docker image prune -f 2>/dev/null || true

docker build -t klag:latest .

docker run -d \
  -p 8888:8888 \
  --name klag-app \
  -e KAFKA_BOOTSTRAP_SERVERS="kafka2-000-${ENV}.mist.pvt:6667,kafka2-001-${ENV}.mist.pvt:6667,kafka2-002-${ENV}.mist.pvt:6667" \
  -e KAFKA_REQUEST_TIMEOUT_MS=120000 \
  -e KAFKA_CHUNK_COUNT=10 \
  -e KAFKA_CHUNK_DELAY_MS=1000 \
  -e METRICS_INTERVAL_MS=120000 \
  klag:latest
