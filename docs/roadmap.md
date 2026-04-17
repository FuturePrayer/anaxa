# 路线图与差距分析

## 1. 当前实现与架构愿景的差距

当前版本已经把独立式进程、堆外向量存储、虚拟线程、结构化并发、SIMD 计算这些关键骨架搭起来了，但离完整的高性能向量数据库仍有差距。

## 2. 已完成的关键目标

- 独立式 HTTP 服务
- 基于 FFM 的堆外向量内存
- 基于 mmap 的 Segment 读取
- 基于 Vector API 的 SIMD 打分
- 基于 Virtual Threads 的并发请求模型
- 基于 Structured Concurrency 的多 source 并发检索
- HNSW + PQ 近似搜索
- 持久化 HNSW / PQ / Payload sidecar
- 增强型 Payload 过滤表达式 + 列式范围候选裁剪
- 多租户 namespace、tenant 配额、tenant 级限流
- API Key 鉴权、热重载、RBAC、限流与审计
- Prometheus 指标、JFR 事件、慢查询统计与内部阶段指标
- 逻辑备份与恢复
- WAL / Segment checksum 校验与恢复策略
- NDJSON bulk ingest
- payload-only partial update
- delete/update-aware adaptive flush / compaction
- resident segment warmup + 更细粒度的 source 调度

## 3. 仍值得优先补齐的能力

### 3.1 更进一步的性能优化

当前已经完成了 NDJSON bulk ingest 和第一版 delete/update-aware adaptive flush / compaction，但还有空间继续做：

- 二进制 bulk ingest 协议
- 更强的常驻 segment cache / 热冷分层 cache
- 更独立的异步预取队列和更精细的 warmup 策略
- 更细的 source / stage 调度与 backpressure

### 3.2 分布式能力

当前仍是单机架构，没有：

- 副本
- 分片
- Raft / 一致性协议
- 跨节点 scatter-gather

### 3.3 Valhalla 值类接入

架构设计里提到的值类候选节点结构还没有落地；后续可在索引层替换当前候选对象模型。

### 3.4 运维与数据生命周期

还缺少：

- snapshot 调度
- 冷热分层
- 备份保留策略
- 更成熟的后台 compaction policy 调优
- 更完整的 tenant 运维接口

## 4. 建议的下一步演进顺序

如果要继续把当前实现推进到更接近生产可用，建议按下面顺序演进：

1. 先补 **snapshot 调度、冷热分层和更完整的后台数据生命周期管理**
2. 再补 **二进制 bulk ingest、更成熟的 segment cache / 异步预取队列**
3. 然后演进 **分布式、副本与一致性能力**
4. 最后评估 **Valhalla 值类** 对候选节点与 Top-K 结构的替换收益

## 5. 面向 Markdown 知识库的判断

如果目标是把 AnaxaDB 用作 Markdown 知识库后端，当前版本已经适合承接：

- 初始批量导入
- 增量新增
- metadata-only 编辑
- 删除与重建索引
- 单机部署下的 API 服务

距离更强的“长期生产级知识库基础设施”还差的，主要是：

- 更自动化的数据生命周期管理
- 更成熟的高负载缓存/预取体系
- 分布式扩展能力
