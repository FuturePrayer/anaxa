# AnaxaDB

AnaxaDB 是一个基于 **JDK 26** 的独立式向量数据库实现，当前已经具备可运行的单机服务形态，支持多租户、WAL/Segment 恢复、HNSW+PQ 检索、Prometheus 指标、审计日志、备份恢复，以及面向知识库场景的 **NDJSON 批量写入**、**payload-only partial update** 和 **delete/update 压力下的自适应 flush/compaction**。同时，仓库也提供了一个 **基于 JDK 25、无预览特性、直接使用 JDK HttpClient 的 Java SDK**，方便业务侧直接接入。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [docs/overview.md](docs/overview.md) | 当前系统架构、模块划分、写入/检索/删改生命周期、功能简述 |
| [docs/deployment.md](docs/deployment.md) | 环境要求、构建打包、启动参数、部署与运维建议 |
| [docs/http-api.md](docs/http-api.md) | HTTP 接口文档、鉴权/租户头、请求与响应格式、错误码 |
| [docs/usage.md](docs/usage.md) | 常用操作示例，包括 JSON/NDJSON 写入、PATCH partial update、搜索、删除、备份恢复 |
| [docs/sdk.md](docs/sdk.md) | Java SDK 依赖方式、基础 API、高阶流程 API 与代码示例 |
| [docs/benchmark.md](docs/benchmark.md) | Python 压测脚本、Java benchmark 模块、benchmark 报告字段说明 |
| [docs/roadmap.md](docs/roadmap.md) | 当前实现与架构愿景的差距、已完成项、后续演进建议 |

## 快速开始

1. 构建

   ```powershell
   $env:JAVA_HOME='D:\devProgram\jdk\jdk-26'
   mvn "-Dmaven.repo.local=D:\jarLibrary" clean package
   ```

2. 启动服务

   ```powershell
    java --enable-preview `
      -jar anaxa-server\target\anaxa-server-1.0-SNAPSHOT.jar `
      --data-dir=D:\anaxa-data `
      --allow-open-access=true
    ```

   `--allow-open-access=true` 仅用于本地快速体验；生产环境必须配置 `--api-keys` 或 `--api-key-file`。

3. 创建集合

   ```bash
   curl -X POST "http://127.0.0.1:8080/collections" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "docs",
       "dimension": 768,
       "metric": "COSINE"
     }'
   ```

4. 批量导入后切读流量时，建议执行一次 flush

   ```bash
   curl -X POST "http://127.0.0.1:8080/collections/docs/flush"
   ```

## 当前重点能力

- JSON 包装批量写入 + `application/x-ndjson` 流式批量写入
- `PATCH /collections/{name}/vectors` 做 metadata/payload 局部更新，不要求客户端重传向量
- delete/tombstone + update-heavy / delete-heavy 自适应 flush/compaction
- HNSW+PQ 近似检索，支持 payload 倒排和列式范围过滤
- collection query cache + source index cache + Segment prefetch / resident warmup
- API Key / RBAC / tenant 配额 / rate limiting / audit / backup-restore
- 内置 WebUI 测试控制台：`/ui`，可通过 `--web-ui-enabled=false` 关闭

## Maven 模块

| 模块 | 作用 |
| --- | --- |
| `anaxa-common` | 通用模型、错误定义、JSON 工具、上下文与工具类 |
| `anaxa-sdk` | 基于 JDK 25 的 Java SDK，封装 HttpClient、基础 REST API 与高阶写入/同步流程 |
| `anaxa-index` | HNSW+PQ 搜索器、SIMD 打分、Payload 过滤索引、Top-K 归并 |
| `anaxa-storage` | WAL、堆外 MemTable、Immutable Segment、恢复与校验 |
| `anaxa-engine` | collection 生命周期、检索编排、flush/compaction、partial update |
| `anaxa-server` | HTTP 服务、鉴权、租户、配额、指标、审计、备份恢复 |
| `anaxa-benchmark` | 环境 benchmark 与 stdout 报告 |

## 知识库场景建议

- **首批导入**：优先用 NDJSON 或大批次 JSON 写入。
- **导入完成后**：执行一次 `flush`，把 MemTable 落盘并准备读路径。
- **正文变化**：走完整 upsert，提交新向量。
- **只改标题/标签/path/权限元数据**：走 `PATCH /collections/{name}/vectors`。
- **删除/高频修改**：让系统自动 flush/compact；手动 compact 更适合离峰维护窗口。
