# AnaxaDB

AnaxaDB 是一个基于 **JDK 26** 的独立式向量数据库实现，当前版本已经具备可运行的单机服务形态，支持多租户 namespace、租户配额与限流、集合创建、向量写入、向量删除、流式 JSON 写入、低拷贝 WAL 追加、基于 **HNSW + PQ** 的近似检索、Segment prefetch、自适应 `efSearch` / rerank 调优、持久化 ANN sidecar、Payload 倒排 + 列式过滤、查询缓存、内部阶段指标、API Key 热重载 + RBAC、审计日志、备份恢复、Prometheus 风格指标导出、JFR 事件、WAL/Segment checksum 校验、Compaction 以及重启恢复。

当前代码以 Maven 多模块组织，核心目标是让网络接入、索引计算、存储持久化和查询编排彼此解耦，方便后续继续演进到更高性能和更完整的数据库能力。

## 1. 当前功能简述

当前版本已经实现：

- 独立进程 HTTP 服务
- 集合创建与集合列表/统计查询
- 向量批量写入（同一 `id` 重复写入时以后写为准）
- 向量删除（Delete / Tombstone）
- 基于输入流的请求体解析，避免写入接口先整体 `readAllBytes()`
- 低拷贝 WAL 记录编码，减少写入时的中间缓冲复制
- 基于 HNSW + PQ 的近似 Top-K 检索，并对小数据集自适应回退精确扫描
- Segment 级 prefetch（对预算内 mmap segment 预热页缓存）
- 持久化 Segment 级 HNSW / PQ / Payload 过滤索引 sidecar，降低冷启动重建成本
- 基于 Payload 倒排索引 + 列式存储的增强过滤：精确匹配、`$in`、`$contains`、范围比较、布尔组合、嵌套字段
- 多租户 namespace；不同 tenant 可复用相同 collection 名
- 租户级 API Key 绑定、`X-Tenant-Id` 解析、collection/live vectors/storage/QPS 配额
- `COSINE` 与 `L2` 两种距离度量
- WAL 逐条 checksum 校验
- Segment footer checksum 校验
- Tombstone 持久化与恢复
- 基于阈值的异步 Flush
- 手动 Compaction 与阈值触发的后台 Compaction
- Immutable Segment 持久化
- 截断/损坏 WAL 尾部恢复与隔离
- Segment 损坏检测与可恢复场景下的自动回放
- 基于虚拟线程的请求处理
- 基于 Structured Concurrency 的多 Segment 并发检索
- 基于 FFM `MemorySegment` 的堆外向量存储
- 基于 Vector API 的 SIMD 距离计算
- Source index cache + collection query cache
- 近似检索 rerank 裁剪与 early-stop 风格候选截断
- 基于 source size / filter candidates / topK 的自适应 `efSearch` 与 rerank 窗口
- Prometheus 风格 `/metrics` 指标导出
- HTTP/JFR 请求与检索事件、慢查询统计、直方图型延迟指标
- Flush / Compaction / Search candidate pruning / rerank / cache 命中指标
- 可选 API Key 鉴权
- 基于文件的 API Key 热重载
- Reader / Writer / Admin RBAC
- 基于令牌桶的请求限流
- JSON Lines 审计日志
- Collection 级备份与恢复

## 2. 模块划分

项目采用以下 Maven 模块：

| 模块 | 作用 |
| --- | --- |
| `anaxa-common` | 通用模型、错误定义、JSON 工具、请求上下文 |
| `anaxa-index` | 向量度量、HNSW+PQ 搜索器、ANN sidecar 持久化、Payload 倒排/列式过滤、Top-K 归并 |
| `anaxa-storage` | Collection 目录布局、带 checksum 的 WAL、堆外 MemTable、Immutable Segment |
| `anaxa-engine` | 集合生命周期、租户隔离、写入编排、异步 Flush、恢复、并发检索 |
| `anaxa-server` | HTTP Server、参数解析、指标导出、JFR 事件、鉴权、RBAC、租户解析、配额/限流、审计、备份恢复、错误映射、可执行入口 |

## 3. 当前系统架构

当前实现对应的运行链路如下：

```text
HTTP Client
    |
    v
anaxa-server
  - jdk.httpserver
  - Virtual Threads
  - ScopedValue(RequestContext)
  - API Key auth / RBAC / tenant policy / quotas / audit / backup-restore / metrics / JFR
    |
    v
anaxa-engine
  - VectorDatabaseEngine
  - EngineCollection
  - StructuredTaskScope scatter-gather search
    |
    +------------------------+
    |                        |
    v                        v
anaxa-storage            anaxa-index
  - checksum WAL            - HnswPqSegmentIndexSearcher
  - OffHeapMemTable        - VectorMetricScorer
  - ImmutableSegment       - PayloadFilterIndex
  - segment sidecar ANN    - PayloadColumnStore
  - mapped segment files   - PayloadFilterPlan
```

### 3.1 写入路径

1. HTTP `POST /collections/{name}/vectors` 接收批量向量。
2. 请求体直接从 `InputStream` 流式反序列化为写入模型，避免先整体读成 `byte[]`。
3. 数据追加到 `active.wal`，每条 WAL 记录携带 checksum，且写入编码过程避免额外的 body 二次缓冲复制。
4. 向量写入当前活跃的堆外 `MemTable`。
5. 当 `MemTable` 估算大小超过阈值时：
   - 关闭当前 WAL
   - 将其改名为 `frozen-*.wal`
   - 切换到新的活跃 `MemTable + active.wal`
   - 后台虚拟线程把冻结 MemTable 写成 `segment-*.seg`
   - Flush 成功后删除对应冻结 WAL

### 3.2 删除与 Compaction 路径

1. HTTP `POST /collections/{name}/deletions` 接收待删除的向量 ID 列表。
2. 引擎为存在的 live ID 生成 tombstone 记录，并先写入 WAL。
3. Tombstone 写入活跃 MemTable 后，旧版本向量会立即从检索结果中消失。
4. 当 tombstone 被 Flush 到 Segment 后，系统可通过 `POST /collections/{name}/compact` 手动压实，或者在 Segment 数达到阈值后由后台自动压实。
5. Compaction 会保留每个 ID 的最新 live 版本，并清理被 tombstone 覆盖的旧数据以及已经不再需要保留的 tombstone 记录。

### 3.3 检索路径

1. HTTP `POST /collections/{name}/search` 接收查询向量。
2. 引擎收集当前活跃 MemTable、待 Flush MemTable、已落盘 Segments。
3. 查询先通过 Payload 倒排索引和列式范围索引缩小候选范围；小候选集走精确扫描，大候选集走 HNSW+PQ 近似召回。
4. 搜索器会根据 source size、filter candidate 数和 `topK` 自适应选择精确/近似路径，并动态调优 `efSearch` 与 rerank 窗口。
5. 对预算内的 mmap Segment，会在搜索前触发 prefetch 预热页缓存，降低首次打分时的 page fault。
6. 近似召回后的候选会做 bounded rerank，并使用 SIMD 精确打分重新排序。
7. collection 级 query cache 会缓存完全相同的查询请求；source index cache 会缓存 Segment/MemTable 的 ANN 与过滤 sidecar。
8. 过滤表达式支持 `$and` / `$or` / `$not`、范围和数组包含，索引可命中的子句优先走倒排/列式候选生成，剩余子句再做逐条校验。
9. 使用 `StructuredTaskScope` 并发检索每个数据源。
10. 主线程归并所有局部 Top-K，返回最终结果，并把慢查询、query cache、source cache、candidate pruning、flush/compaction 指标写入观测面。

### 3.4 持久化目录布局

默认 tenant 继续沿用兼容目录布局；非默认 tenant 写入 `tenants/{tenant}/collections/{collection}`：

```text
{data-dir}/
  audit/
    audit.log
  backups/
    {backup-id}/
      {collection-name}/
        ...
      tenants/
        {tenant-id}/
          collections/
            {collection-name}/
              ...
  {collection-name}/
    collection.json
    wal/
      active.wal
      frozen-00000000000000000001.wal
    segments/
      segment-00000000000000000001.seg
      segment-00000000000000000001.seg.ann
    quarantine/
      active.wal.wal-recovered
  tenants/
    {tenant-id}/
      collections/
        {collection-name}/
          collection.json
          wal/
            active.wal
          segments/
            segment-00000000000000000001.seg
            segment-00000000000000000001.seg.ann
```

- `collection.json`：集合元数据
- `active.wal`：当前正在追加的 WAL
- `frozen-*.wal`：等待后台 Flush 完成后删除的 WAL
- `segment-*.seg`：带 footer checksum 的不可变段文件
- `segment-*.seg.ann`：Segment 对应的 HNSW / PQ / Payload 过滤 sidecar
- `quarantine/`：恢复过程中被隔离的损坏/被替换文件
- `audit/audit.log`：JSON Lines 审计日志
- `backups/{backup-id}/...`：逻辑备份目录

## 4. 部署说明

### 4.1 环境要求

- JDK 26
- Maven 3.9+
- 需要启用 preview 特性和 incubator Vector API

本地环境示例：

- JDK：`D:\devProgram\jdk\jdk-26`
- Maven 本地仓库：`D:\jarLibrary`

### 4.2 编译打包

在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\devProgram\jdk\jdk-26'
$env:MAVEN_OPTS='-Dmaven.repo.local=D:\jarLibrary'
mvn clean package
```

打包成功后，可执行产物位于：

```text
anaxa-server\target\anaxa-server-1.0-SNAPSHOT.jar
```

### 4.3 启动方式

最小启动命令：

```powershell
java --enable-preview --add-modules jdk.incubator.vector `
  -jar anaxa-server\target\anaxa-server-1.0-SNAPSHOT.jar `
  --data-dir=D:\anaxa-data
```

推荐启动参数：

```powershell
java --enable-preview --add-modules jdk.incubator.vector `
  -XX:+UseZGC -XX:+ZGenerational `
  -Xms256m -Xmx1g `
  -jar anaxa-server\target\anaxa-server-1.0-SNAPSHOT.jar `
  --host=0.0.0.0 `
  --port=8080 `
  --data-dir=D:\anaxa-data `
  --default-flush-threshold-bytes=67108864 `
  --api-keys=prod-secret-1,prod-secret-2 `
  --api-key-file=D:\anaxa-config\api-keys.json `
  --rate-limit-per-minute=6000 `
  --rate-limit-burst=256 `
  --slow-query-threshold-ms=250 `
  --audit-log=D:\anaxa-data\audit\audit.log `
  --backup-dir=D:\anaxa-data\backups
```

### 4.4 启动参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--host` | `0.0.0.0` | 监听地址 |
| `--port` | `8080` | 监听端口 |
| `--data-dir` | `data` | 数据目录 |
| `--default-flush-threshold-bytes` | `67108864` | collection 默认 Flush 阈值，单位字节 |
| `--api-keys` | 空 | 静态 API Key 列表，逗号分隔；配置后除 `/health` 外都需要鉴权，且这些 key 默认拥有全部权限 |
| `--api-key-file` | 空 | 可选 JSON 文件，支持 API Key + 角色（`READER`/`WRITER`/`ADMIN`）热重载 |
| `--rate-limit-per-minute` | `6000` | 每个 principal / remote address 的每分钟请求额度，`0` 表示关闭 |
| `--rate-limit-burst` | `256` | 令牌桶突发容量 |
| `--slow-query-threshold-ms` | `250` | 慢查询阈值；`0` 表示关闭慢查询标记 |
| `--audit-log` | `{data-dir}\audit\audit.log` | 审计日志输出位置 |
| `--backup-dir` | `{data-dir}\backups` | 逻辑备份目录 |

## 5. 使用说明

### 5.1 基本使用流程

1. 启动服务
2. 创建 collection
3. 批量写入向量
4. 按需删除向量
5. 发起搜索请求
6. 在需要时触发 compaction
7. 按需执行备份与恢复
8. 查看集合统计、指标、审计与健康状态

### 5.2 支持的度量类型

| 类型 | 含义 |
| --- | --- |
| `COSINE` | 余弦相似度，分数越大越相似 |
| `L2` | 欧氏距离平方的相反数，分数越大表示距离越近 |

## 6. HTTP 接口文档

所有接口均返回 JSON，并在响应头中附带：

```text
X-Trace-Id: <trace-id>
```

如果请求头里传入了 `X-Trace-Id`，服务会透传；否则服务端自动生成。

如果启动时配置了 `--api-keys`，则除 `GET /health` 外其余接口都要求：

```text
X-API-Key: <your-key>
```

或：

```text
Authorization: Bearer <your-key>
```

如果使用 `--api-key-file`，文件格式示例如下：

```json
{
  "keys": [
    {
      "id": "reader-bot",
      "secret": "reader-secret",
      "roles": ["READER"],
      "tenant": "team-a"
    },
    {
      "id": "ops-admin",
      "secret": "admin-secret",
      "roles": ["ADMIN"],
      "globalTenantAccess": true
    }
  ],
  "tenants": [
    {
      "id": "team-a",
      "maxCollections": 32,
      "maxLiveVectors": 5000000,
      "maxStorageBytes": 21474836480,
      "rateLimitPerMinute": 12000,
      "rateLimitBurst": 512
    }
  ]
}
```

服务会按文件修改时间自动重载该文件。当前路由权限模型如下：

| 角色 | 可访问能力 |
| --- | --- |
| `READER` | 读集合、搜索、查看统计 |
| `WRITER` | 创建集合、写入、删除；同时具备 `READER` 能力 |
| `ADMIN` | `WRITER` 全部能力，以及 `/metrics`、compaction、备份与恢复 |

`X-Tenant-Id` 为可选请求头：

```text
X-Tenant-Id: <tenant-id>
```

租户解析规则：

1. 未开启鉴权时：普通 collection 路由默认落到 `default` tenant；`GET /collections` 和 `GET /metrics` 不带该头时返回所有 tenant 视图。
2. 使用 tenant 绑定 API Key 时：不带 `X-Tenant-Id` 时自动落到该 key 绑定 tenant；如果显式指定，则必须与绑定 tenant 一致。
3. 使用 `globalTenantAccess=true` 的全局 key 时：collection 级操作不带 `X-Tenant-Id` 时默认落到 `default` tenant；`GET /collections` 和 `GET /metrics` 不带该头时返回所有 tenant 视图。

### 6.1 健康检查

**GET** `/health`

响应示例：

```json
{
  "status": "UP"
}
```

### 6.2 指标导出

**GET** `/metrics`

如果携带 `X-Tenant-Id`，则 collection / search / flush / compaction 类指标会按该 tenant 视图输出；tenant 绑定 admin key 在不带该头时默认返回自身 tenant 视图，全局 admin key 不带该头时返回所有 tenant 聚合视图。

返回 Prometheus 文本格式指标，包含：

- `anaxa_http_requests_total`
- `anaxa_http_request_duration_seconds_bucket`
- `anaxa_http_request_duration_seconds_sum`
- `anaxa_http_request_duration_seconds_count`
- `anaxa_http_auth_failures_total`
- `anaxa_http_authorization_denied_total`
- `anaxa_http_rate_limited_total`
- `anaxa_search_queries_total`
- `anaxa_search_slow_queries_total`
- `anaxa_search_hits_total`
- `anaxa_search_duration_seconds_sum`
- `anaxa_search_duration_seconds_count`
- `anaxa_engine_flush_total`
- `anaxa_engine_flush_duration_seconds_sum`
- `anaxa_engine_flush_duration_seconds_count`
- `anaxa_engine_compaction_total`
- `anaxa_engine_compaction_duration_seconds_sum`
- `anaxa_engine_compaction_duration_seconds_count`
- `anaxa_search_sources_total`
- `anaxa_search_source_queries_total{mode="exact|approximate"}`
- `anaxa_search_filter_candidates_total`
- `anaxa_search_approximate_candidates_total`
- `anaxa_search_reranked_candidates_total`
- `anaxa_search_scored_candidates_total`
- `anaxa_search_graph_visited_total`
- `anaxa_search_source_index_cache_hits_total`
- `anaxa_search_source_index_cache_misses_total`
- `anaxa_search_query_cache_hits_total`
- `anaxa_search_query_cache_misses_total`
- `anaxa_engine_collections`
- `anaxa_engine_live_vectors`
- `anaxa_engine_tombstones`
- `anaxa_engine_segments`
- `anaxa_engine_storage_bytes`
- `anaxa_engine_collection_live_vectors`
- `anaxa_engine_collection_storage_bytes`

collection / search / flush / compaction 指标都带 `tenant` 和 `collection` label。

响应示例：

```text
# TYPE anaxa_http_requests_total counter
anaxa_http_requests_total{method="POST",route="/collections",status="201"} 1
# TYPE anaxa_search_queries_total counter
anaxa_search_queries_total{collection="docs",tenant="team-a"} 3
# TYPE anaxa_engine_flush_total counter
anaxa_engine_flush_total{collection="docs",tenant="team-a"} 1
# TYPE anaxa_engine_collections gauge
anaxa_engine_collections 1
```

如果启用了 JFR，可额外观察：

- `cn.suhoan.anaxa.HttpRequest`
- `cn.suhoan.anaxa.Search`

### 6.3 创建集合

**POST** `/collections`

请求体：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "flushThresholdBytes": 1048576
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 集合名，匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}` |
| `dimension` | 是 | 向量维度，必须大于 0 |
| `metric` | 否 | `COSINE` 或 `L2`，默认 `COSINE` |
| `flushThresholdBytes` | 否 | Flush 阈值，未传则使用服务默认值 |

成功响应：`201 Created`

响应示例：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 0,
  "tombstoneCount": 0,
  "segmentCount": 0,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 0
}
```

### 6.4 查询所有集合

**GET** `/collections`

行为说明：

- tenant 绑定 key 默认只返回其所属 tenant 的集合
- 全局 key 在不带 `X-Tenant-Id` 时返回所有 tenant 的集合，带上该头时只返回指定 tenant
- 未开启鉴权时，不带 `X-Tenant-Id` 也返回所有 tenant 的集合

响应示例：

```json
[
  {
    "name": "docs",
    "dimension": 3,
    "metric": "COSINE",
    "liveVectorCount": 2,
    "tombstoneCount": 0,
    "segmentCount": 1,
    "flushInProgress": false,
    "compactionInProgress": false,
    "tenantId": "default",
    "storageBytes": 16384
  }
]
```

### 6.5 查询单个集合状态

**GET** `/collections/{name}`

响应示例：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 2,
  "tombstoneCount": 0,
  "segmentCount": 1,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 16384
}
```

### 6.6 批量写入向量

**POST** `/collections/{name}/vectors`

请求体：

```json
{
  "vectors": [
    {
      "id": "alpha",
      "vector": [1.0, 0.0, 0.0],
      "payload": {
        "tenant": "blue",
        "category": "guide"
      }
    },
    {
      "id": "beta",
      "vector": [0.0, 1.0, 0.0],
      "payload": {
        "tenant": "red",
        "category": "faq"
      }
    }
  ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `vectors` | 是 | 待写入向量列表，不能为空 |
| `vectors[].id` | 是 | 向量唯一标识 |
| `vectors[].vector` | 是 | 浮点向量，长度必须与 collection `dimension` 相同 |
| `vectors[].payload` | 否 | 任意 JSON 对象，用于检索过滤和结果回传 |

成功响应：`200 OK`

响应内容为该集合最新统计：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 2,
  "tombstoneCount": 0,
  "segmentCount": 0,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 256
}
```

### 6.7 删除向量

**POST** `/collections/{name}/deletions`

请求体：

```json
{
  "ids": ["alpha", "beta"]
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `ids` | 是 | 待删除向量 ID 列表，不能为空 |

行为说明：

- 仅对当前仍然是 live 状态的 ID 生成 tombstone
- 已删除或不存在的 ID 会被忽略
- 删除后旧向量会立刻从检索结果中消失

成功响应：`200 OK`

响应内容为该集合最新统计：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 1,
  "tombstoneCount": 1,
  "segmentCount": 0,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 320
}
```

### 6.8 手动触发 Flush

**POST** `/collections/{name}/flush`

该接口不需要请求体，用于把当前活动 MemTable 立即冻结、落盘，并等待 Flush 完成；如果当前没有待刷新的 live/tombstone 数据，则该操作为 no-op。

行为说明：

- 适合批量导入结束后的“准备读”阶段
- Flush 完成后会同步预热新 Segment 的 source index cache，避免首次检索再支付一次冷启动建索引代价
- 如果当前已经有后台 Flush 在执行，请求会等待其结束后再返回

成功响应：`200 OK`

响应内容为 Flush 完成后的集合统计：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 1,
  "tombstoneCount": 0,
  "segmentCount": 1,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 12288
}
```

### 6.9 手动触发 Compaction

**POST** `/collections/{name}/compact`

该接口不需要请求体，用于把当前所有持久化 Segment 做一次手动压实。

行为说明：

- 保留每个 ID 的最新 live 版本
- 清理已被覆盖的旧版本
- 清理已经不再需要保留的 tombstone
- 如果 collection 的持久化 Segment 少于 2 个，则该操作为 no-op

成功响应：`200 OK`

响应内容为压实后的集合统计：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 1,
  "tombstoneCount": 0,
  "segmentCount": 1,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 12288
}
```

### 6.9 向量检索

**POST** `/collections/{name}/search`

请求体：

```json
{
  "vector": [1.0, 0.0, 0.0],
  "topK": 2,
  "filter": {
    "tenant": "blue"
  }
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `vector` | 是 | 查询向量 |
| `topK` | 是 | 返回结果数量，必须大于 0 |
| `filter` | 否 | Payload 过滤表达式；支持精确匹配、嵌套字段、`$in`、`$contains`、`$gt`/`$gte`/`$lt`/`$lte`、`$and`/`$or`/`$not` |

过滤表达式示例：

```json
{
  "filter": {
    "$and": [
      { "tenant": { "$in": ["blue", "green"] } },
      { "meta.priority": { "$gte": 5, "$lt": 10 } },
      { "tags": { "$contains": "featured" } },
      {
        "$or": [
          { "meta.region": "eu" },
          { "meta.region": "apac" }
        ]
      }
    ]
  }
}
```

行为说明：

- 顶层直接写 `{ "tenant": "blue" }` 仍然表示等值匹配，兼容旧请求
- 嵌套对象可用点路径，例如 `meta.region`
- `$contains` 适用于数组字段，也支持字符串包含
- `$in` / 等值 / 部分数组包含子句会优先命中倒排索引
- 标量范围子句会优先命中列式候选裁剪
- 大候选集会走 HNSW+PQ 近似召回，小候选集会自动回退精确扫描；近似路径会做有界 rerank

成功响应：`200 OK`

响应示例：

```json
{
  "hits": [
    {
      "id": "alpha",
      "score": 1.0,
      "payload": {
        "tenant": "blue",
        "category": "guide"
      },
      "sequence": 1
    },
    {
      "id": "gamma",
      "score": 0.9938837,
      "payload": {
        "tenant": "blue",
        "category": "tutorial"
      },
      "sequence": 3
    }
  ]
}
```

返回字段说明：

| 字段 | 说明 |
| --- | --- |
| `id` | 向量 ID |
| `score` | 相似度分数；`COSINE` 越大越相似，`L2` 为负距离，越大越近 |
| `payload` | 写入时附带的元数据 |
| `sequence` | 系统内部序列号，越大表示写入越新 |

### 6.10 备份集合

**POST** `/collections/{name}/backup`

请求体：

```json
{
  "backupId": "nightly-001"
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `backupId` | 是 | 备份标识；最终输出到 `{backup-dir}\{backupId}` 下当前 tenant 作用域对应的 collection 目录 |

行为说明：

- 只支持对当前没有待处理 Flush / Compaction 的 collection 执行备份
- 备份会复制 `collection.json`、Segment、ANN sidecar 和当前 MemTable 的 WAL 快照

成功响应：`200 OK`

响应内容为原 collection 当前统计。

### 6.11 从备份恢复集合

**POST** `/backups/{backupId}/restore`

请求体：

```json
{
  "sourceCollection": "docs",
  "collectionName": "docs-restored"
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `sourceCollection` | 是 | 备份目录中的源集合名 |
| `collectionName` | 是 | 恢复后的新集合名 |

行为说明：

- 恢复会把当前 tenant 作用域下的备份目录复制回 `data-dir`
- 恢复后的 collection 会重新加载 WAL / Segment / sidecar，并立即可搜索
- 目标集合名必须不存在

成功响应：`201 Created`

响应内容为恢复后集合统计。

集合统计字段补充说明：

| 字段 | 说明 |
| --- | --- |
| `liveVectorCount` | 当前仍然可检索的 live 向量数量 |
| `tombstoneCount` | 当前仍被保留、尚未被压实清理的 tombstone 数量 |
| `segmentCount` | 持久化 Segment 数量 |
| `flushInProgress` | 是否存在正在执行的 Flush |
| `compactionInProgress` | 是否存在正在执行的 Compaction |
| `tenantId` | collection 所属 tenant |
| `storageBytes` | 当前 collection 估算占用字节数（MemTable + WAL + Segment + sidecar） |

文件恢复行为补充说明：

| 场景 | 当前行为 |
| --- | --- |
| `active.wal` 尾部截断/损坏 | 回放有效前缀，隔离原文件，并重写干净的 `active.wal` |
| `frozen-*.wal` 尾部截断/损坏 | 回放有效前缀，隔离原文件，并将恢复结果并入新的 `active.wal` |
| `segment-*.seg` checksum 不匹配且有同代 WAL | 隔离损坏 segment，使用 frozen WAL 恢复 |
| `segment-*.seg` checksum 不匹配且无可恢复 WAL | 启动失败，避免静默丢数 |

## 7. 错误响应

错误时统一返回：

```json
{
  "message": "Collection not found: docs",
  "traceId": "3f59d8cb-52b0-4708-9bbc-09a5f3d4f651"
}
```

### 7.1 常见状态码

| 状态码 | 场景 |
| --- | --- |
| `400` | 参数错误、请求体为空、维度不匹配、`topK <= 0`、非法集合名、Compaction 已在执行、备份时 collection 仍有待处理 Flush/Compaction |
| `401` | API Key 缺失或无效 |
| `403` | API Key 有效但角色权限不足，或 tenant 访问越权 |
| `429` | 请求超过限流阈值 |
| `409` | 租户配额超限（如 maxCollections / maxLiveVectors / maxStorageBytes）或目标恢复集合已存在 |
| `404` | collection 不存在或接口路径不存在 |
| `405` | HTTP 方法不允许 |
| `500` | 内部异常，例如后台 Flush 失败等 |

## 8. 使用示例

下面给出一组完整示例，假设服务运行在 `http://127.0.0.1:8080`。

### 8.1 创建集合

```bash
curl -X POST "http://127.0.0.1:8080/collections" \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: demo-create-001" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "name": "docs",
    "dimension": 3,
    "metric": "COSINE",
    "flushThresholdBytes": 96
  }'
```

如果需要显式落到某个 tenant，可额外带上：

```bash
-H "X-Tenant-Id: team-a"
```

### 8.2 写入向量

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/vectors" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "vectors": [
      {
        "id": "alpha",
        "vector": [1.0, 0.0, 0.0],
        "payload": {
          "tenant": "blue",
          "category": "guide"
        }
      },
      {
        "id": "beta",
        "vector": [0.0, 1.0, 0.0],
        "payload": {
          "tenant": "red",
          "category": "faq"
        }
      },
      {
        "id": "gamma",
        "vector": [0.9, 0.1, 0.0],
        "payload": {
          "tenant": "blue",
          "category": "tutorial"
        }
      }
    ]
  }'
```

### 8.3 检索相似向量

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/search" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "vector": [1.0, 0.0, 0.0],
    "topK": 2,
    "filter": {
      "tenant": "blue"
    }
  }'
```

预期结果类似：

```json
{
  "hits": [
    {
      "id": "alpha",
      "score": 1.0,
      "payload": {
        "tenant": "blue",
        "category": "guide"
      },
      "sequence": 1
    },
    {
      "id": "gamma",
      "score": 0.9938837,
      "payload": {
        "tenant": "blue",
        "category": "tutorial"
      },
      "sequence": 3
    }
  ]
}
```

### 8.4 复杂过滤检索

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/search" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: reader-secret" \
  -d '{
    "vector": [1.0, 0.0, 0.0],
    "topK": 5,
    "filter": {
      "$and": [
        { "tenant": { "$in": ["blue", "green"] } },
        { "meta.priority": { "$gte": 5, "$lt": 10 } },
        { "tags": { "$contains": "featured" } }
      ]
    }
  }'
```

### 8.5 删除向量

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/deletions" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "ids": ["beta"]
  }'
```

### 8.6 手动触发 Flush

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/flush" \
  -H "X-API-Key: admin-secret"
```

### 8.7 手动触发 Compaction

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/compact" \
  -H "X-API-Key: admin-secret"
```

### 8.8 查询健康状态

```bash
curl "http://127.0.0.1:8080/health"
```

### 8.9 查询集合统计

```bash
curl "http://127.0.0.1:8080/collections/docs" \
  -H "X-API-Key: prod-secret-1"
```

### 8.10 查询 Prometheus 指标

```bash
curl "http://127.0.0.1:8080/metrics" \
  -H "X-API-Key: admin-secret"
```

### 8.11 执行备份

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/backup" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: admin-secret" \
  -d '{
    "backupId": "nightly-001"
  }'
```

### 8.12 从备份恢复到新集合

```bash
curl -X POST "http://127.0.0.1:8080/backups/nightly-001/restore" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: admin-secret" \
  -d '{
    "sourceCollection": "docs",
    "collectionName": "docs-restored"
  }'
```

### 8.13 使用仓库内压测脚本

仓库提供了一个基于 Python 标准库的压测脚本：`scripts\anaxa_bench.py`

示例：

```bash
python scripts\anaxa_bench.py ^
  --base-url http://127.0.0.1:8080 ^
  --api-key admin-secret ^
  --tenant-id team-a ^
  --collection bench-team-a ^
  --dimension 128 ^
  --vectors 50000 ^
  --ingest-batch-size 250 ^
  --warmup-requests 500 ^
  --search-requests 5000 ^
  --search-workers 16 ^
  --top-k 10 ^
  --group-count 32 ^
  --flush-after-ingest ^
  --compact-after-ingest ^
  --use-filter
```

脚本会自动：

1. 创建集合
2. 批量写入测试向量
3. 可选执行 `flush -> compact -> wait-for-quiescent` 准备阶段
4. 执行 warmup 检索
5. 并发执行测量检索
6. 输出 ingest 吞吐、prepare 用时、attempt QPS / successful QPS、状态码分布、p50/p95/p99/max 延迟，以及最终 collection 统计

对于 Markdown 知识库这类“批量导入后快速切到读流量”的场景，推荐至少使用 `--flush-after-ingest`。它会把活动 MemTable 落盘并预热新 Segment，通常就足以消掉首次检索冷启动尖峰；`--compact-after-ingest` 更适合作为离线维护步骤，而不是首批查询前的阻塞准备动作。

### 8.14 使用 Java benchmark 模块

仓库现在还提供了一个独立的 Java benchmark 模块：`anaxa-benchmark`。它默认会在本机当前运行环境内自举一个临时 AnaxaDB 实例，按预置 profile 跑多组参数场景，并把评测报告直接输出到 stdout。

构建：

```bash
mvn -pl anaxa-benchmark -am -DskipTests package
```

运行内嵌 benchmark（默认 profile 为 `standard`）：

```bash
java --enable-preview --add-modules jdk.incubator.vector ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.0-SNAPSHOT.jar ^
  --profile=markdown-kb ^
  --data-dir=D:\anaxa-data\bench-suite
```

如果要压测一个已经在运行的服务，也可以切到 remote 模式：

```bash
java --enable-preview --add-modules jdk.incubator.vector ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.0-SNAPSHOT.jar ^
  --base-url=http://127.0.0.1:8080 ^
  --tenant-id=team-a ^
  --profile=standard
```

当前支持的 profile：

- `quick`：快速给出一份轻量级环境指纹
- `standard`：默认环境评估，覆盖小规模通用场景和中/大规模知识库场景
- `markdown-kb`：专门对比 Markdown 知识库场景下 `none / flush / flush+compact`
- `full`：增加更重参数档位，适合做更完整的机器摸底

输出报告会包含：

1. 环境信息（JDK / VM / OS / CPU / 堆大小 / base URL）
2. 每个场景的 ingest、prepare、warmup、measured-search 指标
3. 汇总表格
4. 自动生成的 observations，用来帮助判断 flush / compaction / 维度变化对冷启动和稳态 QPS 的影响

## 9. 当前实现与架构愿景的差距

当前版本已经把独立式进程、堆外向量存储、虚拟线程、结构化并发、SIMD 计算这些关键骨架搭起来了，但离完整的高性能向量数据库还有差距。

### 9.1 已实现的设计目标

- 独立式 HTTP 服务
- 基于 FFM 的堆外向量内存
- 基于 mmap 的 Segment 读取
- 写入接口的流式 JSON 反序列化
- 更低拷贝的 WAL 记录编码
- 基于 Vector API 的 SIMD 打分
- 基于 Structured Concurrency 的并发检索
- 基于 Virtual Threads 的高并发请求模型
- HNSW + PQ 近似搜索
- Segment prefetch
- 自适应 `efSearch` / rerank 窗口调优
- 持久化 HNSW / PQ / Payload 索引 sidecar
- 增强型 Payload 过滤表达式 + 列式范围候选裁剪
- 多租户 namespace、tenant 配额、tenant 级限流
- API Key 鉴权、热重载、RBAC、限流与审计
- Prometheus 指标、JFR 事件、慢查询统计与内部阶段指标
- 逻辑备份与恢复
- WAL / Segment checksum 校验与恢复策略

### 9.2 还未实现但值得优先补齐

1. **更激进的性能优化**  
   还可以继续做：
   - Binary / NDJSON bulk ingest 协议
   - 常驻 segment cache 与异步预取队列
   - 更细粒度的并发调度
   - 自适应 compaction / flush policy

2. **分布式能力**  
   当前是单机架构，没有副本、分片、Raft、一致性协议和跨节点 scatter-gather。

3. **Valhalla 值类接入**  
   架构设计里提到的值类候选节点结构还没有落地；后续可在索引层替换当前候选对象模型。

4. **运维与数据生命周期能力**  
   还缺少 snapshot 调度、冷热分层、备份保留策略、后台 compaction policy 调优和更完整的租户运维接口。

## 10. 建议的下一步演进顺序

如果要继续把当前实现推进到更接近生产可用，建议按下面顺序演进：

1. 先补 **Snapshot 调度、冷热分层和更完整的后台数据生命周期管理**
2. 再做 **Binary / NDJSON bulk ingest、常驻 segment cache 与异步预取队列**
3. 然后演进 **分布式、副本与一致性能力**
4. 最后评估 **Valhalla 值类** 对候选节点与 Top-K 结构的替换收益
