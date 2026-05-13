# Benchmark 与压测说明

## 1. Python 压测脚本

仓库提供了一个基于 Python 标准库的脚本：`scripts\anaxa_bench.py`

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
3. 可选执行 `flush -> compact -> wait-for-quiescent`
4. 执行 warmup 检索
5. 并发执行 measured-search
6. 输出 ingest 吞吐、prepare 用时、状态码分布、successful QPS 和延迟分位数

对于 Markdown 知识库场景，推荐至少带上 `--flush-after-ingest`。

## 2. Java benchmark 模块

模块：`anaxa-benchmark`

构建：

```bash
mvn -pl anaxa-benchmark -am -DskipTests package
```

### 2.1 嵌入式 benchmark

```bash
java --enable-preview ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.0-SNAPSHOT.jar ^
  --profile=markdown-kb ^
  --data-dir=D:\anaxa-data\bench-suite
```

### 2.2 Remote benchmark

```bash
java --enable-preview ^
  -jar anaxa-benchmark\target\anaxa-benchmark-1.0-SNAPSHOT.jar ^
  --base-url=http://127.0.0.1:8080 ^
  --tenant-id=team-a ^
  --profile=standard
```

## 3. 支持的 profile

| profile | 用途 |
| --- | --- |
| `quick` | 快速得到环境指纹 |
| `standard` | 默认环境评估，覆盖通用场景和中/大规模知识库场景 |
| `markdown-kb` | 专门比较知识库场景下 `none / flush / flush+compact` |
| `scorer-eval` | 覆盖不同维度、topK、过滤选择性与 L2/COSINE 的 scorer 评估场景 |
| `full` | 更完整的机器摸底 |

## 4. benchmark 场景组织方式

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

## 5. `[environment]` 段含义

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

## 6. `[scenario:xxx]` 段含义

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

## 7. `[summary]` 段含义

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

## 8. `[observations]` 段含义

`[observations]` 是 benchmark 自动生成的摘要，当前主要输出：

- 最佳稳态吞吐场景
- `none` / `flush` / `flush+compact` 的横向差异
- `flush+compact` 是否值得额外 prepare 时间

判断知识库读路径时，优先看：

1. `warmup_p99`
2. `measured_qps`
3. `measured_p99`
4. `prepare_seconds`
