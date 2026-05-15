# Benchmark 与压测说明

## 1. Python 压测脚本

仓库提供了一个基于 Python 标准库的脚本：`scripts\anaxa_bench.py`

示例：

```bash
python scripts\anaxa_bench.py ^
  --base-url http://127.0.0.1:30720 ^
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
3. 可选执行 `flush -> compact -> wait-for-quiescent`
4. 执行 warmup 检索
5. 并发执行 measured-search
6. 输出 ingest 吞吐、prepare 用时、状态码分布、successful QPS 和延迟分位数

对于 Markdown 知识库场景，推荐至少带上 `--flush-after-ingest`。

## 2. Benchmark baseline

Benchmark 结果应当视为“当前机器与当前参数的环境指纹”，不要直接当成跨机器通用分数。记录 baseline 时至少保留：

- JDK 版本、JVM、OS、CPU 数、堆大小
- profile、scenario、向量数、维度、worker 数、是否启用 filter
- ingest 吞吐、prepare 用时、warmup p99、measured QPS、measured p99
- Segment 数量、storage bytes
- 每个 scenario 结束后抓取的 `metrics_snapshot`

推荐先跑这些 profile：

| profile | 用途 |
| --- | --- |
| `quick` | 快速确认环境和报告格式是否正常 |
| `standard` | 建立通用单机 baseline |
| `markdown-kb` | 比较 `none / flush / flush+compact` 对知识库场景的影响 |
| `scorer-eval` | 评估不同维度、过滤选择性和 scorer 压力 |

Java benchmark 会在每个 scenario 后尝试抓取 `/metrics`，并把当前 collection 相关的关键行写入 `metrics_snapshot`。如果远端没有权限访问 `/metrics`，报告会保留 `metrics_available: false`，但不会让 benchmark 失败。

## 3. Java benchmark 模块

模块：`anaxa-benchmark`

构建：

```bash
mvn -pl anaxa-benchmark -am -DskipTests package
```

### 3.1 嵌入式 benchmark

```bash
java --enable-preview ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.1.jar ^
  --profile=markdown-kb ^
  --data-dir=D:\anaxa-data\bench-suite
```

### 3.2 Remote benchmark

```bash
java --enable-preview ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.1.jar ^
  --base-url=http://127.0.0.1:30720 ^
  --tenant-id=team-a ^
  --profile=standard
```

## 4. 支持的 profile

| profile | 用途 |
| --- | --- |
| `quick` | 快速得到环境指纹 |
| `standard` | 默认环境评估，覆盖通用场景和中/大规模知识库场景 |
| `markdown-kb` | 专门比较知识库场景下 `none / flush / flush+compact` |
| `scorer-eval` | 覆盖不同维度、topK、过滤选择性与 L2/COSINE 的 scorer 评估场景 |
| `full` | 更完整的机器摸底 |

## 5. benchmark 场景组织方式

Java benchmark 按 `profile -> scenario -> family` 组织：

| 层级 | 含义 |
| --- | --- |
| `profile` | 一组预置评测组合 |
| `scenario` | 一条具体压测用例，例如 `kb-medium-flush` |
| `family` | 一组可横向对比的同类场景 |

常见后缀：

| 后缀 | 含义 |
| --- | --- |
| `unprepared` | 导入后不做准备 |
| `flush` | 导入后先 flush |
| `flush-compact` | 导入后先 flush 再 compact |

## 6. `[environment]` 段含义

| 字段 | 含义 |
| --- | --- |
| `profile` | 当前使用的 benchmark profile |
| `server_mode` | `embedded` 或 `remote` |
| `base_url` | 实际访问地址 |
| `run_id` | 本次 benchmark 唯一标识 |
| `data_dir` | 本次 benchmark 使用的数据目录 |
| `java_runtime` | JDK 版本 |
| `vm_name` | JVM 名称 |
| `os` | 操作系统 |
| `available_cpu` | JVM 可见 CPU 数 |
| `max_heap_bytes` | 最大堆大小 |
| `total_heap_bytes` | 当前已申请堆大小 |
| `free_heap_bytes` | 当前可用堆大小 |

## 7. `[scenario:xxx]` 段含义

每个场景都代表一组完整的：

`建集合 -> 写入 -> prepare -> warmup -> measured-search`

| 字段 | 含义 |
| --- | --- |
| `collection` | 自动生成的集合名 |
| `family` | 横向对比分组 |
| `prepare_mode` | `none` / `flush` / `flush,compact` |
| `dimension` | 向量维度 |
| `vectors` | 总向量数 |
| `search_workers` | 并发搜索 worker 数 |
| `use_filter` | 是否启用 payload filter |
| `ingest_seconds` | 写入总耗时 |
| `ingest_vps` | 写入吞吐（vectors/sec） |
| `prepare_seconds` | prepare 阶段耗时 |
| `warmup_seconds` | warmup 阶段总耗时 |
| `warmup_success` | warmup 成功请求数 / 总请求数 |
| `warmup_qps` | warmup successful QPS |
| `warmup_p50_ms` / `warmup_p95_ms` / `warmup_p99_ms` / `warmup_max_ms` | warmup 延迟分位数 |
| `warmup_status` | warmup 状态码分布 |
| `measured-search_seconds` | 正式测量阶段总耗时 |
| `measured-search_success` | 正式测量成功请求数 / 总请求数 |
| `measured-search_qps` | 稳态 successful QPS |
| `measured-search_p50_ms` / `measured-search_p95_ms` / `measured-search_p99_ms` / `measured-search_max_ms` | 稳态延迟分位数 |
| `measured-search_status` | 正式测量状态码分布 |
| `segments` | 场景结束时持久化 Segment 数量 |
| `storage_bytes` | collection 估算占用字节数 |
| `metrics_available` | 是否成功抓取 `/metrics` |
| `metrics_snapshot` | 当前 collection 的关键 Prometheus 指标快照 |

## 8. `[metrics_snapshot]` 重点指标

`metrics_snapshot` 主要用于解释 QPS 和 p99 背后的原因。常用指标：

| 指标 | 含义 |
| --- | --- |
| `anaxa_search_sources_total` | 查询累计访问的 source 数，可用于判断 fan-out 压力 |
| `anaxa_search_source_vectors_total` | 查询累计覆盖的 source vector 数 |
| `anaxa_search_source_batches_total` | 查询被分成多少个 source 搜索批次 |
| `anaxa_search_source_throttled_total` | 因 source fan-out 限制被分批处理的查询次数 |
| `anaxa_search_source_queries_total{mode="exact|approximate"}` | 精确扫描和 ANN 查询的 source 次数 |
| `anaxa_search_stage_duration_seconds` | source selection、source search、merge 阶段累计耗时 |
| `anaxa_search_filter_candidates_total` | payload filter 后候选数量 |
| `anaxa_search_approximate_candidates_total` | ANN 近似候选数量 |
| `anaxa_search_reranked_candidates_total` | rerank 候选数量 |
| `anaxa_search_scored_candidates_total` | 实际打分候选数量 |
| `anaxa_search_source_index_cache_hits_total` / `misses_total` | source index cache 命中和未命中次数 |
| `anaxa_search_query_cache_hits_total` / `misses_total` | collection query cache 命中和未命中次数 |
| `anaxa_background_tasks_total` | flush / compaction / warmup 后台任务完成数 |
| `anaxa_background_task_duration_seconds` | 后台任务执行耗时 |
| `anaxa_background_task_queued_seconds` | 后台任务排队等待耗时 |
| `anaxa_background_task_yield_seconds` | 后台任务因给前台搜索让路而等待的耗时 |
| `anaxa_engine_active_searches` | 当前活跃搜索数 |
| `anaxa_engine_active_foreground_searches` | 当前前台查询活跃数，不含 warmup |
| `anaxa_engine_pending_flush_memtables` | 等待 flush 完成的 MemTable 数 |
| `anaxa_engine_queued_warm_tasks` | 等待 warmup 的任务数 |
| `anaxa_engine_resident_source_bytes` | resident source cache 占用字节数 |
| `anaxa_engine_flush_in_progress` / `compaction_in_progress` | 是否有 flush / compaction 正在运行 |

解读建议：

- `source_search` 阶段耗时高，通常说明检索、filter、ANN/rerank 或 cache miss 是主要成本。
- `merge` 阶段耗时高，通常说明 source fan-out 或 top-k 归并压力偏高。
- `background_task_queued_seconds` 持续升高，说明后台任务排队，后续需要 backpressure 或并发预算。
- `background_task_yield_seconds` 升高，说明 warmup 已经在主动给前台搜索让路。
- `pending_flush_memtables` 长时间大于 0，说明写入和 flush 能力不匹配。
- `source_index_cache_misses_total` 高且 warmup 任务多，说明冷启动或 cache 预算需要关注。
- `source_throttled_total` 上升，说明 source fan-out 限制已经开始生效，需要结合 p99 看这是保护还是瓶颈。

## 9. `[summary]` 段含义

| 列 | 含义 |
| --- | --- |
| `scenario` | 场景名 |
| `prepare` | prepare 模式 |
| `dim` | 向量维度 |
| `vectors` | 向量数 |
| `workers` | 并发 worker 数 |
| `filter` | 是否启用 filter |
| `ingest_vps` | 写入吞吐 |
| `warmup_p99` | 冷启动阶段 p99 |
| `measured_qps` | 稳态 QPS |
| `measured_p99` | 稳态 p99 |

## 10. `[observations]` 段含义

`[observations]` 是 benchmark 自动生成的摘要，当前主要输出：

- 最佳稳态吞吐场景
- `none` / `flush` / `flush+compact` 的横向差异
- `flush+compact` 是否值得额外 prepare 时间

判断知识库读路径时，优先看：

1. `warmup_p99`
2. `measured_qps`
3. `measured_p99`
4. `prepare_seconds`
