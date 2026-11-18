# Build stage
FROM eclipse-temurin:21-jdk AS builder

ARG GRADLE_VERSION=8.14.3
ARG GRADLE_SHA256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531

WORKDIR /app

# Install Gradle (checksum-verified download, same pattern as Dockerfile.native)
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget unzip && \
    wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O /tmp/gradle.zip && \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - && \
    unzip -d /opt/gradle /tmp/gradle.zip && \
    ln -s "/opt/gradle/gradle-${GRADLE_VERSION}/bin/gradle" /usr/bin/gradle && \
    rm -f /tmp/gradle.zip

# Copy build files (lockfile required — STRICT locking on compile/runtime classpaths)
COPY build.gradle.kts settings.gradle.kts gradle.lockfile ./

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src src

# Build fat JAR
RUN gradle assemble --no-daemon

# Runtime stage (Ubuntu 24.04 Noble to avoid pebble + reduce CVEs).
# SapMachine (SAP's OpenJDK build, Docker Official Image) instead of Temurin: it shipped the
# Java4Shell fix (21.0.13.1) first. Same OpenJDK 21 code; switch back once Temurin has 21.0.13.1.
FROM sapmachine:21-jre-ubuntu-noble

WORKDIR /app

# Copy fat JAR from builder
COPY --from=builder /app/build/libs/*-fat.jar app.jar

# Default port
EXPOSE 8888

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run as a non-root numeric UID (matches distroless "nonroot" and the chart's
# runAsUser). Numeric so Kubernetes runAsNonRoot checks can verify it.
USER 65532:65532

# -XX:-UseBASE64Intrinsics: Java4Shell (CVE-2026-114938) workaround for x86-64 AVX-512 hosts.
# In the entrypoint after $JAVA_OPTS (last flag wins), so JAVA_OPTS can't drop or re-enable it.
# Arch-neutral (arm64 OK).
# Redundant on a fixed base (>= 21.0.13.1); kept as a guard if the base is switched back early.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -XX:-UseBASE64Intrinsics -jar app.jar"]
