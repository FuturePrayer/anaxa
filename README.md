# AnaxaDB

AnaxaDB 是一个基于 **JDK 26** 的独立式向量数据库实现，当前版本已经具备可运行的单机服务形态，支持集合创建、向量写入、向量检索、Payload 精确过滤、WAL 持久化、段文件落盘以及重启恢复。

当前代码以 Maven 多模块组织，核心目标是让网络接入、索引计算、存储持久化和查询编排彼此解耦，方便后续继续演进到更高性能和更完整的数据库能力。

## 1. 当前功能简述

当前版本已经实现：

- 独立进程 HTTP 服务
- 集合创建与集合列表/统计查询
- 向量批量写入（同一 `id` 重复写入时以后写为准）
- Top-K 检索
- 基于 Payload 的精确等值过滤
- `COSINE` 与 `L2` 两种距离度量
- WAL 追加写入
- 基于阈值的异步 Flush
- Immutable Segment 持久化
- 重启后的 Segment/WAL 恢复
- 基于虚拟线程的请求处理
- 基于 Structured Concurrency 的多 Segment 并发检索
- 基于 FFM `MemorySegment` 的堆外向量存储
- 基于 Vector API 的 SIMD 距离计算

## 2. 模块划分

项目采用以下 Maven 模块：

| 模块 | 作用 |
| --- | --- |
| `anaxa-common` | 通用模型、错误定义、JSON 工具、请求上下文 |
| `anaxa-index` | 向量度量、Top-K 归并、SIMD Flat Searcher、索引抽象 |
| `anaxa-storage` | Collection 目录布局、WAL、堆外 MemTable、Immutable Segment |
| `anaxa-engine` | 集合生命周期、写入编排、异步 Flush、恢复、并发检索 |
| `anaxa-server` | HTTP Server、参数解析、错误映射、可执行入口 |

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
  - WAL append/replay      - TopKAccumulator
  - OffHeapMemTable        - VectorMetricScorer
  - ImmutableSegment       - FlatSegmentIndexSearcher
  - mapped segment files
```

### 3.1 写入路径

1. HTTP `POST /collections/{name}/vectors` 接收批量向量。
2. 数据先追加到 `active.wal`。
3. 向量写入当前活跃的堆外 `MemTable`。
4. 当 `MemTable` 估算大小超过阈值时：
   - 关闭当前 WAL
   - 将其改名为 `frozen-*.wal`
   - 切换到新的活跃 `MemTable + active.wal`
   - 后台虚拟线程把冻结 MemTable 写成 `segment-*.seg`
   - Flush 成功后删除对应冻结 WAL

### 3.2 检索路径

1. HTTP `POST /collections/{name}/search` 接收查询向量。
2. 引擎收集当前活跃 MemTable、待 Flush MemTable、已落盘 Segments。
3. 使用 `StructuredTaskScope` 并发检索每个数据源。
4. 每个数据源内部执行 SIMD 加速的向量评分。
5. 主线程归并所有局部 Top-K，返回最终结果。

### 3.3 持久化目录布局

每个 collection 在 `data-dir` 下有独立目录：

```text
{data-dir}/
  {collection-name}/
    collection.json
    wal/
      active.wal
      frozen-00000000000000000001.wal
    segments/
      segment-00000000000000000001.seg
```

- `collection.json`：集合元数据
- `active.wal`：当前正在追加的 WAL
- `frozen-*.wal`：等待后台 Flush 完成后删除的 WAL
- `segment-*.seg`：不可变段文件

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
  --default-flush-threshold-bytes=67108864
```

### 4.4 启动参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--host` | `0.0.0.0` | 监听地址 |
| `--port` | `8080` | 监听端口 |
| `--data-dir` | `data` | 数据目录 |
| `--default-flush-threshold-bytes` | `67108864` | collection 默认 Flush 阈值，单位字节 |

## 5. 使用说明

### 5.1 基本使用流程

1. 启动服务
2. 创建 collection
3. 批量写入向量
4. 发起搜索请求
5. 查看集合统计与健康状态

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

### 6.1 健康检查

**GET** `/health`

响应示例：

```json
{
  "status": "UP"
}
```

### 6.2 创建集合

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
  "segmentCount": 0,
  "flushInProgress": false
}
```

### 6.3 查询所有集合

**GET** `/collections`

响应示例：

```json
[
  {
    "name": "docs",
    "dimension": 3,
    "metric": "COSINE",
    "liveVectorCount": 2,
    "segmentCount": 1,
    "flushInProgress": false
  }
]
```

### 6.4 查询单个集合状态

**GET** `/collections/{name}`

响应示例：

```json
{
  "name": "docs",
  "dimension": 3,
  "metric": "COSINE",
  "liveVectorCount": 2,
  "segmentCount": 1,
  "flushInProgress": false
}
```

### 6.5 批量写入向量

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
  "segmentCount": 0,
  "flushInProgress": false
}
```

### 6.6 向量检索

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
| `filter` | 否 | Payload 精确等值过滤；当前仅支持扁平键值的完全匹配 |

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
| `400` | 参数错误、请求体为空、维度不匹配、`topK <= 0`、非法集合名 |
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
  -d '{
    "name": "docs",
    "dimension": 3,
    "metric": "COSINE",
    "flushThresholdBytes": 96
  }'
```

### 8.2 写入向量

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/vectors" \
  -H "Content-Type: application/json" \
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

### 8.4 查询健康状态

```bash
curl "http://127.0.0.1:8080/health"
```

### 8.5 查询集合统计

```bash
curl "http://127.0.0.1:8080/collections/docs"
```

## 9. 当前实现与架构愿景的差距

当前版本已经把独立式进程、堆外向量存储、虚拟线程、结构化并发、SIMD 计算这些关键骨架搭起来了，但离完整的高性能向量数据库还有差距。

### 9.1 已实现的设计目标

- 独立式 HTTP 服务
- 基于 FFM 的堆外向量内存
- 基于 mmap 的 Segment 读取
- 基于 Vector API 的 SIMD 打分
- 基于 Structured Concurrency 的并发检索
- 基于 Virtual Threads 的高并发请求模型

### 9.2 还未实现但值得优先补齐

1. **HNSW / PQ 真正索引化**  
   当前检索器仍是 Flat Scan，结构已经可插拔，但还没有把 HNSW、量化压缩和图导航真正接入。

2. **Compaction 与多层段合并**  
   现在只有 Flush，没有后台 Compaction；随着 Segment 增多，查询成本会逐步升高。

3. **删除与墓碑机制**  
   当前支持 upsert 覆盖，但没有 delete API 和 tombstone。

4. **更强过滤能力**  
   当前仅支持扁平 Payload 的精确等值匹配，不支持范围、布尔表达式、数组和倒排索引。

5. **更完整的 WAL/段格式版本化**  
   目前文件格式简单直接，后续需要 checksum、版本升级策略、损坏检测和更严格的恢复策略。

6. **可观测性**  
   当前还缺少指标、审计日志、JFR 事件、慢查询采样和 Prometheus 导出。

7. **运维能力**  
   当前没有鉴权、租户隔离、限流、配置热更新、备份恢复工具和管理接口。

8. **性能优化**  
   还可以继续做：
   - 批量写入零拷贝解析
   - Payload 列式存储
   - 搜索 early-stop
   - Segment 级缓存与预取
   - 更细粒度的并发调度

9. **分布式能力**  
   当前是单机架构，没有副本、分片、Raft、一致性协议和跨节点 scatter-gather。

10. **Valhalla 值类接入**  
    架构设计里提到的值类候选节点结构还没有落地；后续可在索引层替换当前候选对象模型。

## 10. 建议的下一步演进顺序

如果要继续把当前实现推进到更接近生产可用，建议按下面顺序演进：

1. 先补 **Compaction + Delete/Tombstone**
2. 再接入 **HNSW**，替换 Flat Search
3. 增加 **可观测性、限流、鉴权**
4. 优化 **Payload 过滤索引**
5. 最后再推进 **分布式与副本能力**
