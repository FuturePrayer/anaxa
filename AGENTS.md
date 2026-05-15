# AGENTS.md

This file is for AI coding agents working on AnaxaDB. Keep changes small, verify with Maven, and avoid reverting unrelated user work.

## Project Overview

AnaxaDB is a standalone Java vector database for single-node deployments. It includes an HTTP server, Java SDK, HNSW+PQ search, payload filtering, WAL/segment persistence, tenant controls, metrics, backup/restore, benchmark tooling, Docker packaging, and a local Web UI.

## Repository Structure

| Path | Purpose |
| --- | --- |
| `pom.xml` | Maven parent POM and shared plugin/dependency versions. |
| `anaxa-common` | Shared DTOs, errors, JSON support, request context, binary batch helpers. Targets JDK 25. |
| `anaxa-sdk` | Java SDK built on JDK `HttpClient`. Targets JDK 25 and avoids preview APIs. |
| `anaxa-index` | Vector scoring, HNSW+PQ search, payload indexes, top-k merging. |
| `anaxa-storage` | WAL, off-heap memtable, immutable segment format, recovery. |
| `anaxa-engine` | Collection lifecycle, write/read orchestration, flush/compaction, search fan-out. |
| `anaxa-server` | JDK `HttpServer`, auth/RBAC, tenants, quotas, metrics, audit, backup, Web UI. |
| `anaxa-benchmark` | Embedded/remote benchmark runner and scenario profiles. |
| `docs` | Architecture, deployment, API, SDK, benchmark, roadmap, and release docs. |
| `scripts` | Python benchmark helper. |
| `.github/workflows` | Release automation. |

## Development Environment

Preferred local tools on the main development machine:

```powershell
$env:JAVA_HOME='D:\develop\jdk-26'
$env:PATH="$env:JAVA_HOME\bin;D:\develop\apache-maven-3.9.6\bin;$env:PATH"
```

Notes:

- Root build defaults to JDK 26 and passes `--enable-preview` for modules that still need preview APIs.
- `anaxa-common` and `anaxa-sdk` override `maven.compiler.release` to `25`.
- `ScopedValue` is final in JDK 25. `StructuredTaskScope` is still preview and requires `--enable-preview`.
- Default server port is `30720`.

## Common Commands

Full build and tests:

```powershell
mvn --batch-mode --no-transfer-progress clean test
```

Package all modules:

```powershell
mvn --batch-mode --no-transfer-progress clean package
```

Run a focused module test:

```powershell
mvn --batch-mode --no-transfer-progress -pl anaxa-engine -am test
```

Verify Maven Central SDK/common release artifacts without signing:

```powershell
mvn --batch-mode --no-transfer-progress -Pcentral -pl anaxa-common,anaxa-sdk -am "-Dgpg.skip=true" verify
```

Build benchmark runner:

```powershell
mvn --batch-mode --no-transfer-progress -pl anaxa-benchmark -am package
```

Run a quick embedded benchmark:

```powershell
java --enable-preview -jar anaxa-benchmark\target\anaxa-benchmark-1.4.jar --profile=quick --data-dir=D:\anaxa-data\bench-quick
```

Run the server locally:

```powershell
java --enable-preview -jar anaxa-server\target\anaxa-server-1.4.jar --data-dir=D:\anaxa-data --allow-open-access=true
```

## Testing Guidance

- Prefer focused Maven module tests while iterating.
- Run full `mvn clean test` before finalizing broad engine/storage/server changes.
- For release-related changes, run the `central` profile verification for `anaxa-common` and `anaxa-sdk`.
- For benchmark changes, run at least `--profile=quick`; use `standard` or `markdown-kb` when checking performance-sensitive behavior.
- Server tests require preview support through the parent Surefire configuration.

## Benchmark Baseline Guidance

Benchmark output should be treated as an environment fingerprint, not a universal score. Capture at least:

- JDK version, OS, CPU count, heap values.
- Profile, scenario, vector count, dimension, workers, filter usage.
- Ingest throughput, prepare time, warmup p99, measured QPS, measured p99.
- Segment count and storage bytes.
- Metrics snapshot from `/metrics` when running remote benchmarks.

Use these baseline profiles first:

- `quick`: sanity and environment fingerprint.
- `standard`: general single-node baseline.
- `markdown-kb`: compare `none`, `flush`, and `flush+compact` prepare modes.
- `scorer-eval`: scorer and dimension sensitivity.

## Coding Conventions

- Make the smallest correct change.
- Do not add compatibility layers unless persisted data, shipped behavior, or external consumers require it.
- Keep public SDK/common APIs documented with Javadoc because they are published to Maven Central.
- Preserve existing module boundaries. Do not make `anaxa-common` or `anaxa-sdk` depend on preview APIs.
- Avoid adding third-party dependencies unless the benefit is clear and documented.
- For server/runtime code, prefer explicit metrics around new background work or queues.
- For storage format changes, include recovery and backward compatibility notes.

## Release Notes

Maven Central publishing currently releases only:

- `cn.suhoan:anaxa`
- `cn.suhoan:anaxa-common`
- `cn.suhoan:anaxa-sdk`

Stable tags publish to Maven Central. Pre-release versions containing `snapshot`, `alpha`, `beta`, `rc`, `milestone`, `preview`, `pre`, `dev`, or `nightly` skip Maven Central.

See `docs/maven-central-release.md` for GPG, Central Portal, and GitHub Secrets setup.

## Operational Cautions

- Do not delete or rewrite user data directories while testing.
- Use temporary benchmark data directories outside important local data.
- Do not commit secrets, API keys, GPG private keys, Central tokens, or local Maven settings.
- If the working tree has unrelated changes, leave them alone.
