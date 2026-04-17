# AnaxaDB 概览

## 1. 项目定位

AnaxaDB 的目标是提供一个基于现代 Java 生态、可独立运行的向量数据库。当前版本已经覆盖了单机知识库后端所需的大部分基础能力：

- 独立 HTTP 服务
- 多租户 namespace、租户配额与 tenant 级限流
- JSON / NDJSON 批量写入
- delete/tombstone
- payload-only partial update
- HNSW + PQ 近似检索
- Payload 倒排与列式范围过滤
- WAL / Segment checksum 与重启恢复
- Prometheus 指标、JFR 事件、审计日志、备份恢复

## 2. 当前功能简述

当前版本已经实现：

- 独立进程 HTTP 服务
- 集合创建、集合列表与集合统计
- `COSINE` / `L2` 两种距离度量
- 批量向量写入
- `application/x-ndjson` 流式批量写入
- 向量删除（Delete / Tombstone）
- payload-only partial update（客户端不需要重传向量）
- 面向 delete/update 负载的自适应 flush / compaction
- 基于 HNSW + PQ 的近似 Top-K 检索，并在小候选集上自动回退精确扫描
- Payload 精确匹配、`$in`、`$contains`、范围过滤、布尔组合、嵌套字段过滤
- Segment prefetch、resident warmup、source index cache、collection query cache
- API Key 热重载、RBAC、审计日志、备份恢复
- Prometheus 风格指标、慢查询统计、内部阶段指标

## 3. Maven 模块划分

| 模块 | 作用 |
| --- | --- |
| `anaxa-common` | 通用模型、错误定义、JSON 工具、上下文与共享工具类 |
| `anaxa-index` | 向量度量、SIMD 打分、HNSW+PQ 搜索器、Payload 过滤索引、Top-K 归并 |
| `anaxa-storage` | Collection 目录布局、带 checksum 的 WAL、堆外 MemTable、Immutable Segment |
| `anaxa-engine` | 集合生命周期、写入编排、恢复、structured concurrency 检索、adaptive flush/compaction |
| `anaxa-server` | `jdk.httpserver` 接入、鉴权/RBAC、租户解析、配额、限流、审计、备份恢复 |
| `anaxa-benchmark` | 环境 benchmark、嵌入式临时服务、自定义 profile 场景与 stdout 报告 |

## 4. 当前系统架构

```text
HTTP Client
    |
    v
anaxa-server
  - jdk.httpserver
  - Virtual Threads
  - ScopedValue(RequestContext)
  - API Key auth / RBAC / tenant policy / quotas / audit / metrics
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
  - OffHeapMemTable         - VectorMetricScorer
  - ImmutableSegment        - PayloadFilterIndex / PayloadColumnStore
  - mmap segment files      - TopKAccumulator / ANN sidecar
```

## 5. 写入与删改生命周期

### 5.1 普通写入

1. 客户端调用 `POST /collections/{name}/vectors`
2. 服务把请求写入 `active.wal`
3. 活跃 `MemTable` 更新为最新 live 状态
4. 达到阈值后后台 flush 成 `segment-*.seg`

### 5.2 NDJSON 批量写入

当 `Content-Type` 为 `application/x-ndjson` 时：

- 每一行就是一个 `UpsertVector`
- 服务按批次流式解析并逐批落 WAL / MemTable
- 不需要先把整个请求体反序列化成一个大 JSON 数组

这条路径更适合大规模知识库导入。

### 5.3 删除

1. 客户端调用 `POST /collections/{name}/deletions`
2. 引擎为当前仍然 live 的 ID 写 tombstone
3. 搜索结果会立即屏蔽旧版本
4. 后台 flush / compaction 清理历史版本和 tombstone

### 5.4 Partial update

当只需要修改 metadata / payload 时，客户端可以调用：

- `PATCH /collections/{name}/vectors`

它的行为是：

1. WAL 记录 payload patch，而不是要求客户端重传向量
2. 引擎解析当前 live 文档，合并 patch
3. 新 payload 立即生效
4. 搜索路径继续使用原始向量内容

当前合并规则：

- 顶层和嵌套对象都按 **递归 merge**
- patch 中某个字段值为 `null` 时，会删除对应字段
- 只允许更新当前仍然是 live 的文档

## 6. 自适应 flush / compaction

当前版本已经不再只按 “MemTable 字节数” 触发后台整理，而是额外考虑：

- mutation 数量
- stale version 累积
- tombstone 占比
- delete-heavy / update-heavy 压力

这让以下场景更稳定：

- 大批量新增之后再切读
- 首批导入之后又不断补写新文档
- 知识库元数据频繁修改
- 文档删除后长期运行

实践上可以理解为：

- **新增为主**：仍主要由字节阈值驱动 flush
- **删除/修改为主**：会更早触发 flush
- **tombstone/stale data 累积明显**：会更早触发 compaction

## 7. 检索路径

1. HTTP `POST /collections/{name}/search`
2. 引擎收集 active MemTable、pending Flush MemTable 和持久化 Segment
3. 先做 Payload 倒排/列式过滤缩小候选
4. 小候选集走精确扫描，大候选集走 HNSW+PQ
5. 近似召回后做 bounded rerank
6. 通过 structured concurrency 并发搜索各数据源
7. 汇总局部 Top-K，返回最终命中结果

## 8. 持久化目录布局

默认 tenant 继续使用兼容目录；非默认 tenant 使用 `tenants/{tenant}/collections/{collection}`：

```text
{data-dir}/
  audit/
    audit.log
  backups/
    {backup-id}/...
  {collection-name}/
    collection.json
    wal/
      active.wal
      frozen-*.wal
    segments/
      segment-*.seg
      segment-*.seg.ann
    quarantine/
      ...
  tenants/
    {tenant-id}/
      collections/
        {collection-name}/
          collection.json
          wal/
          segments/
```

关键文件：

- `collection.json`：集合元数据
- `active.wal`：当前活跃 WAL
- `frozen-*.wal`：等待 flush 完成后删除
- `segment-*.seg`：不可变段文件
- `segment-*.seg.ann`：Segment 对应 ANN / Payload sidecar
- `quarantine/`：恢复过程隔离的损坏文件

## 9. 当前适合的使用姿势

对于 Markdown 知识库后端，当前最推荐的流程是：

1. 批量导入初始数据
2. 调用 `flush`
3. 开始承接查询流量
4. 新增文档继续走 upsert
5. 只改 metadata 走 partial update
6. 删除和高频修改由自适应 flush/compaction 接管，离峰再手动 compact
