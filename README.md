# AnaxaDB

[English](README.en.md) | [简体中文](README.md)

AnaxaDB 是一个基于现代 Java 构建的独立式向量数据库。它提供单机 HTTP 服务、Java SDK、HNSW+PQ 向量检索、多租户操作、WAL/Segment 恢复、备份恢复、Prometheus 指标、审计日志，以及用于本地测试的内置 Web UI。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE.txt)
[![Java](https://img.shields.io/badge/java-26-orange.svg)](pom.xml)

## 特性

- 基于 HNSW+PQ 的近似向量检索，支持 cosine、dot-product 和 L2 打分。
- 支持 JSON 和 `application/x-ndjson` 批量写入，适合大规模文档导入。
- 支持 payload 过滤，包括倒排索引和列式范围索引。
- 支持通过 `PATCH /collections/{name}/vectors` 做 payload-only 局部更新。
- 基于 tombstone 的删除机制，并针对高频更新/删除场景提供自适应 flush 和 compaction。
- 基于 WAL 和 immutable segment 的本地持久化恢复。
- 支持多租户 API、API Key、RBAC、配额、限流、审计日志和指标。
- 支持备份恢复，并校验 backup identifier。
- 内置 Web UI，访问路径为 `/ui`，可通过 `--web-ui-enabled=false` 关闭。
- Java SDK 不依赖预览特性，适合业务侧集成。

## 项目状态

AnaxaDB 当前是单机数据库，适合本地开发、实验、benchmark，以及需要简单运维模型的嵌入式或单节点部署场景。

服务端当前需要 JDK 26 和 `--enable-preview`，因为仍使用部分 Java 预览 API。SDK 面向 JDK 25，不需要启用预览特性。

## 快速开始

### 构建

```bash
mvn clean package
```

### 启动服务

```bash
java --enable-preview \
  -jar anaxa-server/target/anaxa-server-1.0.jar \
  --data-dir=./anaxa-data \
  --allow-open-access=true
```

`--allow-open-access=true` 仅适合本地测试。共享环境或生产环境请使用 `--api-keys` 或 `--api-key-file` 配置鉴权。

### 打开 Web UI

访问：

```text
http://127.0.0.1:8080/ui
```

### 创建集合

```bash
curl -X POST "http://127.0.0.1:8080/collections" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "docs",
    "dimension": 768,
    "metric": "COSINE"
  }'
```

### 写入向量

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/vectors" \
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

向量维度必须与集合维度一致。上面的短向量仅用于展示请求结构。

### 检索

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/search" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.1, 0.2, 0.3],
    "topK": 10
  }'
```

## 安装

可以从 GitHub Releases 下载发布产物：

- `anaxa-server-<version>.jar`：可运行的 HTTP 服务。
- `anaxa-benchmark-<version>.jar`：benchmark 运行器。
- `anaxa-sdk-<version>.jar`：Java SDK 类库。
- `anaxa-<version>-checksums.sha256`：发布产物的 SHA-256 校验文件。

## Java SDK

SDK 模块基于 JDK `HttpClient` 封装 HTTP API，提供底层 API 调用和更高层的写入辅助流程。

```xml
<dependency>
  <groupId>cn.suhoan</groupId>
  <artifactId>anaxa-sdk</artifactId>
  <version>1.0</version>
</dependency>
```

更多客户端配置和示例见 [docs/sdk.md](docs/sdk.md)。

## 模块

| 模块 | 说明 |
| --- | --- |
| `anaxa-common` | 通用模型、错误定义、JSON 支持、上下文和工具类。 |
| `anaxa-sdk` | 基于 JDK `HttpClient` 的 Java SDK。 |
| `anaxa-index` | HNSW+PQ 搜索、向量打分、payload 索引和 top-k 归并。 |
| `anaxa-storage` | WAL、堆外 MemTable、immutable segment、恢复和校验。 |
| `anaxa-engine` | Collection 生命周期、读写编排、flush、compaction 和更新。 |
| `anaxa-server` | HTTP 服务、鉴权、租户、配额、指标、审计、备份和 Web UI。 |
| `anaxa-benchmark` | Java benchmark 运行器和报告生成。 |

## 文档

| 文档 | 说明 |
| --- | --- |
| [docs/overview.md](docs/overview.md) | 架构、模块和数据生命周期。 |
| [docs/deployment.md](docs/deployment.md) | 运行时要求、打包、启动参数和运维建议。 |
| [docs/http-api.md](docs/http-api.md) | HTTP API、请求头、payload 和错误说明。 |
| [docs/usage.md](docs/usage.md) | 写入、检索、更新、删除、备份和恢复等常见流程。 |
| [docs/sdk.md](docs/sdk.md) | Java SDK 配置和示例。 |
| [docs/benchmark.md](docs/benchmark.md) | Benchmark profile、脚本和报告字段。 |
| [docs/roadmap.md](docs/roadmap.md) | 当前限制和后续规划。 |

## Benchmark

```bash
java --enable-preview \
  -jar anaxa-benchmark/target/anaxa-benchmark-1.0.jar \
  --profile=quick
```

可用 profile 和推荐测试环境见 [docs/benchmark.md](docs/benchmark.md)。

## 安全说明

- 默认不允许无鉴权开放访问。
- 认证部署请配置 `--api-keys` 或 `--api-key-file`。
- `--allow-open-access=true` 只应在本地开发环境使用。
- 安全敏感环境可以通过 `--web-ui-enabled=false` 关闭 Web UI。

## License

AnaxaDB 使用 [Apache License 2.0](LICENSE.txt) 许可证。
