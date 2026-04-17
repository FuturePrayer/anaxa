# 路线图与差距分析

## 1. 当前实现与架构愿景的距离

当前版本已经把独立式进程、堆外向量存储、虚拟线程、结构化并发、SIMD 计算、ANN sidecar、tenant 隔离、备份恢复和基准模块这些关键骨架搭起来了。对“单机部署的 Markdown / RAG 知识库后端”来说，核心能力已经成型；后续演进重点已经不再是“能不能用”，而是继续把 **单机生产化** 做得更稳、更省、更可观测。

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
- 自动 snapshot 调度 + 按 collection 的备份保留策略
- tenant 运维视图（tenant stats / backup inventory / tenant snapshot）
- 二进制 bulk ingest 协议
- 独立预取队列 + resident hot/cold cache 驱逐

## 3. 仍值得继续补齐的单机能力

### 3.1 更进一步的性能与背压控制

当前已经有 JSON / NDJSON / binary 三条导入路径，也有 resident warmup 与 source index cache 预算驱逐，但还有空间继续做：

- 更细的 source / stage 级 backpressure
- cache / warmup / prefetch 预算按负载自适应调参
- 更激进的批量写入协议压缩与零拷贝路径
- 更系统化的 benchmark profile 与容量规划建议

### 3.2 更成熟的数据生命周期管理

自动 snapshot、保留策略和 tenant snapshot 已经补上，但还可以继续增强：

- 真正的冷热分层到不同磁盘 / 目录 / 远端冷存储
- snapshot 完整性校验、定期 restore drill
- 更成熟的 compaction policy 调优
- 更丰富的后台任务状态与告警视图

### 3.3 更强的生产运维闭环

对单机生产可用来说，后续重点会是运维细节，而不是基础功能本身：

- 更完整的 tenant 运维动作（限额变更、只读/维护模式等）
- 更细的 SLO / alert 指标
- 更系统的故障注入与恢复演练
- 更明确的升级 / 回滚 / 数据迁移手册

## 4. 建议的下一步演进顺序

如果继续把当前实现向“更成熟的单机生产形态”推进，建议按下面顺序走：

1. 继续补 **更细的 source/stage backpressure、cache 自适应调参、批量写入压缩**
2. 再补 **更深的冷热分层、snapshot 完整性校验与 restore drill**
3. 最后完善 **tenant 运维动作、SLO/告警、升级/恢复手册与故障演练**

## 5. 面向 Markdown 知识库的判断

如果目标是把 AnaxaDB 用作 Markdown 知识库后端，当前版本已经能够承接：

- 初始批量导入（JSON / NDJSON / binary）
- 增量新增
- metadata-only 编辑
- 删除与重建索引
- 自动 snapshot 与备份保留
- tenant 级运维与备份盘点
- 单机部署下的 API 服务

就“单机部署模式生产环境可用”这个目标来说，当前版本已经基本达标。接下来更需要投入的，不再是补一个大而新的架构层，而是继续打磨：

- 容量与参数调优
- 生命周期自动化深度
- 运维与告警闭环
