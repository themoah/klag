---
title: Native Image
description: Run Klag as a GraalVM native binary for ~70-100 ms startup and ~44 MB RSS — ideal for fast scaling and low-footprint deployments.
---

Klag publishes a GraalVM **native image** alongside the JVM image. It starts in
**~70–100 ms using ~44 MB RSS**, versus ~500 ms / ~119 MB for the JVM image — with the
same config, endpoints, and metrics.

## Run the published image

The native image is tagged `:native` and `:<version>-native`:

```bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
           -e METRICS_REPORTER=prometheus \
           -p 8888:8888 \
           themoah/klag:native
```

## Build it yourself

Requires a GraalVM JDK 21 (LTS) with `native-image` (e.g.
`sdk install java 21.0.2-graalce`). Run Gradle with that JDK as `JAVA_HOME`/`GRAALVM_HOME`.

```bash
gradle nativeCompile          # -> build/native/nativeCompile/klag (standalone binary)
docker build -f Dockerfile.native -t klag:native .   # distroless runtime image
```

Benchmark startup and memory:

```bash
scripts/benchmark-startup.sh native - build/native/nativeCompile/klag
```

## How it's configured

Native config lives in `build.gradle.kts` (the `graalvmNative` block) plus reachability
hints in `src/main/resources/META-INF/native-image/`. Reflection metadata for Netty,
kafka-clients, logback, and Micrometer comes from the GraalVM Reachability Metadata
Repository (auto-enabled). The entry point is `KlagLauncher` (direct
`new MainVerticle()`, no reflective Vert.x launcher).

:::note
The runtime stays on **JDK 21**. JVM 25 (LTS) showed no startup/memory gain over 21 for
this workload (slightly higher RSS).
:::
