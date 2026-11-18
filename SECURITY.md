# Security Policy

## Supported versions

Klag follows a rolling security support policy on the latest minor release.

- Application dependencies (Vert.x, Netty, Logback, Micrometer) are kept on the
  latest patch within their supported LTS lines.
- Container images are rebuilt on a current base with security fixes.

## Reporting a vulnerability

Please open a private security advisory on GitHub or email the maintainer listed
in `charts/klag/Chart.yaml`. We will acknowledge within 72 hours.

## Remediation and cadence

- Dependency bumps for disclosed CVEs are prioritized and released promptly.
- Base image refresh: at least monthly, and as-needed for high/critical CVEs.
- CI runs Trivy scans on PRs and `main`. Release pipelines fail on image drift
  between chart and application versions to avoid stale, unscanned tags.

## Advisories

### Java4Shell (CVE-2026-114938)

HotSpot's AVX-512 Base64 decode intrinsic can write out of bounds on x86-64 CPUs
with AVX-512 VBMI (ARM64 is not affected). Klag's HTTP server accepted h2c
upgrades, whose handler Base64-decodes the client-supplied `HTTP2-Settings`
header before routing or MCP auth, so the JVM image up to 0.2.20 was reachable
without authentication on affected hosts.

- **Fixed in 0.2.21:** h2c is disabled, the JVM image runs on the patched
  SapMachine 21.0.13.1 runtime, and it also starts with `-XX:-UseBASE64Intrinsics`.
- **Native image:** not affected (no HotSpot JIT).
- **Running the jar on your own JDK:** upgrade to the fixed release of your JDK
  line (17.0.21.1, 21.0.13.1, 25.0.5.1 or 27.0.1.1 and later in that line; check
  your vendor's advisory), or add `-XX:-UseBASE64Intrinsics` to the `java`
  command after any other options.

## Image hardening

- JVM image runtime is built on a minimal, non-root base with read-only FS.
- Native image uses `distroless:nonroot` (glibc) with minimal attack surface.

Consumers with stricter policies can also rebuild from source with their own
base image by running `docker build .` or the Helm chart with `image.repository`
and `image.tag` overrides.
