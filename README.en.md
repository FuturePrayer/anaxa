<p align="center">
  <img src="logo.png" alt="Peony" width="128" height="128">
</p>

<h1 align="center">AnaxaDB</h1>

<p align="center">
  <strong>AnaxaDB is a standalone vector database built on modern Java. It provides a single-node HTTP server, a Java SDK, HNSW+PQ vector search, tenant-aware operations, WAL/segment recovery, backup and restore, Prometheus metrics, audit logs, and a built-in Web UI for local testing.</strong>
</p>

---

[简体中文](README.md) | English

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE.txt)
[![Java](https://img.shields.io/badge/java-26-orange.svg)](pom.xml)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/FuturePrayer/anaxa)

## Features

- Approximate vector search with HNSW+PQ and cosine, dot-product, and L2 scoring.
- JSON and `application/x-ndjson` batch ingestion for large document imports.
- Payload filtering with inverted and columnar range indexes.
- Payload-only partial updates through `PATCH /collections/{name}/vectors`.
- Tombstone-based deletes with adaptive flush and compaction for update-heavy workloads.
- WAL and immutable segment recovery for local durability.
- Multi-tenant APIs with API keys, RBAC, quotas, rate limiting, audit logs, and metrics.
- Backup and restore APIs with validated backup identifiers.
- Built-in Web UI at `/ui`, with `--web-ui-enabled=false` to disable it.
- Java SDK built without preview features for application-side integration.

## Status

AnaxaDB is currently a single-node database. It is suitable for local development, experiments, benchmarks, and embedded-style deployments where the operational model is intentionally simple.

The server currently requires JDK 26 and `--enable-preview` because it uses Java preview APIs. The SDK targets JDK 25 and does not require preview features.

## Quick Start

### Build

```bash
mvn clean package
```

### Start The Server

```bash
java --enable-preview \
  -jar anaxa-server/target/anaxa-server-1.0.jar \
  --data-dir=./anaxa-data \
  --allow-open-access=true
```

`--allow-open-access=true` is intended for local testing only. Use `--api-keys` or `--api-key-file` for shared or production environments.

### Open The Web UI

Visit:

```text
http://127.0.0.1:30720/ui
```

### Create A Collection

```bash
curl -X POST "http://127.0.0.1:30720/collections" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "docs",
    "dimension": 768,
    "metric": "COSINE"
  }'
```

### Upsert Vectors

```bash
curl -X POST "http://127.0.0.1:30720/collections/docs/vectors" \
  -H "Content-Type: application/json" \
  -d '{
    "vectors": [
      {
        "id": "doc-1",
        "values": [0.1, 0.2, 0.3],
        "payload": {"title": "Example"}
      }
    ]
  }'
```

The vector dimension must match the collection dimension. The shortened vector above is only a request-shape example.

### Search

```bash
curl -X POST "http://127.0.0.1:30720/collections/docs/search" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.1, 0.2, 0.3],
    "topK": 10
  }'
```

## Installation

Download release artifacts from GitHub Releases:

- `anaxa-server-<version>.jar`: runnable HTTP server.
- `anaxa-benchmark-<version>.jar`: benchmark runner.
- `anaxa-sdk-<version>.jar`: Java SDK library.
- `anaxa-<version>-checksums.sha256`: SHA-256 checksums for release artifacts.

## Docker

Stable releases are published as Docker images:

- `ghcr.io/<owner>/<repo>:<version>`
- `ghcr.io/<owner>/<repo>:latest`
- `swr.cn-east-3.myhuaweicloud.com/suhoan/anaxa:<version>`
- `swr.cn-east-3.myhuaweicloud.com/suhoan/anaxa:latest`

Run the server image:

```bash
docker run --rm \
  -p 30720:30720 \
  -v anaxa-data:/data/anaxa \
  swr.cn-east-3.myhuaweicloud.com/suhoan/anaxa:latest \
  --host=0.0.0.0 \
  --port=30720 \
  --data-dir=/data/anaxa \
  --allow-open-access=true
```

For mainland China environments, replace the image registry as needed.

To build from source and start locally with Docker Compose:

```bash
docker compose up --build
```

The included `docker-compose.yml` builds the image from the current source tree instead of pulling a prebuilt image.

## Java SDK

The SDK module wraps the HTTP API with JDK `HttpClient` and provides low-level API calls plus higher-level ingestion helpers.

```xml
<dependency>
  <groupId>cn.suhoan</groupId>
  <artifactId>anaxa-sdk</artifactId>
  <version>1.6</version>
</dependency>
```

See [docs/sdk.md](docs/sdk.md) for client setup and examples.

## Modules

| Module | Description |
| --- | --- |
| `anaxa-common` | Shared models, errors, JSON support, context, and utilities. |
| `anaxa-sdk` | Java SDK based on JDK `HttpClient`. |
| `anaxa-index` | HNSW+PQ search, vector scoring, payload indexes, and top-k merge logic. |
| `anaxa-storage` | WAL, off-heap memtables, immutable segments, recovery, and validation. |
| `anaxa-engine` | Collection lifecycle, write/read orchestration, flush, compaction, and updates. |
| `anaxa-server` | HTTP server, authentication, tenants, quotas, metrics, audit, backup, and Web UI. |
| `anaxa-benchmark` | Java benchmark runner and report generation. |

## Documentation

| Document | Description |
| --- | --- |
| [docs/overview.md](docs/overview.md) | Architecture, modules, and data lifecycle. |
| [docs/deployment.md](docs/deployment.md) | Runtime requirements, packaging, startup flags, and operations. |
| [docs/http-api.md](docs/http-api.md) | HTTP API reference, headers, payloads, and errors. |
| [docs/usage.md](docs/usage.md) | Common workflows for ingestion, search, update, delete, backup, and restore. |
| [docs/sdk.md](docs/sdk.md) | Java SDK setup and examples. |
| [docs/benchmark.md](docs/benchmark.md) | Benchmark profiles, scripts, and report fields. |
| [docs/roadmap.md](docs/roadmap.md) | Current limitations and planned improvements. |

## Benchmark

```bash
java --enable-preview \
  -jar anaxa-benchmark/target/anaxa-benchmark-1.0.jar \
  --profile=quick
```

See [docs/benchmark.md](docs/benchmark.md) for available profiles and recommended test environments.

## Security Notes

- Open access is disabled by default.
- Configure `--api-keys` or `--api-key-file` for authenticated deployments.
- Use `--allow-open-access=true` only for local development.
- Disable the Web UI in locked-down environments with `--web-ui-enabled=false`.

## License

AnaxaDB is licensed under the [Apache License 2.0](LICENSE.txt).
