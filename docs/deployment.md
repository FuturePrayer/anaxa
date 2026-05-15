# 部署说明

## 1. 环境要求

- JDK 26
- Maven 3.9+
- 运行时需要 `--enable-preview`
- 运行时仍需要 `--enable-preview`，当前用于 `ScopedValue` / `StructuredTaskScope`

本地环境示例：

- JDK：`D:\devProgram\jdk\jdk-26`
- Maven 本地仓库：`D:\jarLibrary`

## 2. 构建打包

```powershell
$env:JAVA_HOME='D:\devProgram\jdk\jdk-26'
mvn "-Dmaven.repo.local=D:\jarLibrary" clean package
```

可执行产物：

```text
anaxa-server\target\anaxa-server-1.1.jar
```

## 3. 启动方式

### 3.1 最小启动命令

```powershell
java --enable-preview `
  -jar anaxa-server\target\anaxa-server-1.1.jar `
  --data-dir=D:\anaxa-data `
  --allow-open-access=true
```

> `--allow-open-access=true` 仅用于本地开发或内网临时验证。生产环境默认拒绝无鉴权启动，必须配置 `--api-keys` 或 `--api-key-file`。

### 3.2 推荐启动命令

```powershell
java --enable-preview `
  -XX:+UseZGC `
  -Xms256m -Xmx1g `
  -jar anaxa-server\target\anaxa-server-1.1.jar `
  --host=0.0.0.0 `
  --port=30720 `
  --data-dir=D:\anaxa-data `
  --default-flush-threshold-bytes=67108864 `
  --api-keys=prod-secret-1,prod-secret-2 `
  --api-key-file=D:\anaxa-config\api-keys.json `
  --rate-limit-per-minute=6000 `
  --rate-limit-burst=256 `
  --max-request-body-bytes=268435456 `
  --max-concurrent-requests=1024 `
  --slow-query-threshold-ms=250 `
  --audit-log=D:\anaxa-data\audit\audit.log `
  --backup-dir=D:\anaxa-data\backups `
  --snapshot-interval-seconds=900 `
  --snapshot-retention-per-collection=7 `
  --web-ui-enabled=true
```

> 某些 JDK 26 build 可能不接受 `-XX:+ZGenerational`；如果启动时报错，移除该参数即可。

### 3.3 使用配置文件启动

当参数较多时，建议先生成 JSON 配置模板：

```powershell
java --enable-preview `
  -jar anaxa-server\target\anaxa-server-1.4.jar `
  --init-config=D:\anaxa-config\anaxa-config.json
```

之后用配置文件启动：

```powershell
java --enable-preview `
  -jar anaxa-server\target\anaxa-server-1.4.jar `
  --config=D:\anaxa-config\anaxa-config.json
```

命令行参数仍可覆盖配置文件中的同名设置，例如：

```powershell
java --enable-preview `
  -jar anaxa-server\target\anaxa-server-1.4.jar `
  --config=D:\anaxa-config\anaxa-config.json `
  --port=30721 `
  --max-concurrent-source-searches=6
```

配置文件示例：

```json
{
  "host": "0.0.0.0",
  "port": 30720,
  "dataDirectory": "D:\\anaxa-data",
  "defaultFlushThresholdBytes": 67108864,
  "apiKeys": ["prod-secret-1", "prod-secret-2"],
  "apiKeyFile": "D:\\anaxa-config\\api-keys.json",
  "rateLimitPerMinute": 6000,
  "rateLimitBurst": 256,
  "slowQueryThresholdMillis": 250,
  "auditLogPath": "D:\\anaxa-data\\audit\\audit.log",
  "backupDirectory": "D:\\anaxa-data\\backups",
  "snapshotIntervalSeconds": 900,
  "autoSnapshotRetentionPerCollection": 7,
  "maxRequestBodyBytes": 268435456,
  "maxConcurrentRequests": 1024,
  "allowOpenAccess": false,
  "webUiEnabled": true,
  "engine": {
    "maxConcurrentSourceSearches": 4,
    "warmupYieldPollMillis": 2,
    "foregroundSearchesPerSourceSearch": 16,
    "minAdaptiveSourceSearches": 1,
    "adaptiveRecoverySearches": 64
  }
}
```

## 4. 启动参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--host` | `0.0.0.0` | 监听地址 |
| `--port` | `30720` | 监听端口 |
| `--data-dir` | `data` | 数据目录 |
| `--default-flush-threshold-bytes` | `67108864` | collection 默认 flush 阈值 |
| `--api-keys` | 空 | 静态 API Key 列表，逗号分隔 |
| `--api-key-file` | 空 | 可选 JSON 文件，支持 key / role / tenant policy 热重载 |
| `--rate-limit-per-minute` | `6000` | 每分钟请求额度；`0` 表示关闭 |
| `--rate-limit-burst` | `256` | 令牌桶突发容量 |
| `--max-request-body-bytes` | `268435456` | 单个 HTTP request body 最大字节数；超限返回 413；`0` 表示关闭限制 |
| `--max-concurrent-requests` | `1024` | 最大并发处理请求数；超限返回 503；`/health` 不占用该额度 |
| `--slow-query-threshold-ms` | `250` | 慢查询阈值；`0` 表示关闭 |
| `--audit-log` | `{data-dir}\audit\audit.log` | 审计日志路径 |
| `--backup-dir` | `{data-dir}\backups` | 逻辑备份目录 |
| `--snapshot-interval-seconds` | `0` | 自动 snapshot 周期；`0` 表示关闭 |
| `--snapshot-retention-per-collection` | `7` | 每个 collection 自动 snapshot 保留份数 |
| `--allow-open-access` | `false` | 是否允许无 API key 启动；生产必须保持 `false` |
| `--web-ui-enabled` | `true` | 是否启用内置 WebUI；关闭后 `/ui` 返回 404 |
| `--config` | 空 | 从 JSON 文件加载服务配置；命令行参数可覆盖文件值 |
| `--init-config` | 空 | 生成 JSON 配置模板并退出 |
| `--max-concurrent-source-searches` | `4` | 单次查询并发搜索的 source 上限 |
| `--warmup-yield-poll-ms` | `2` | resident warmup 遇到前台查询时的让路轮询间隔 |
| `--foreground-searches-per-source-search` | `16` | 自适应 backpressure 中，每多少个前台查询减少 1 个 source 并发额度 |
| `--min-adaptive-source-searches` | `1` | 自适应 source 并发额度下限 |
| `--adaptive-recovery-searches` | `64` | 连续多少次未触发 source/backpressure 后恢复 1 个 source 并发额度 |

## 5. 部署建议

### 5.1 JVM / 内存

- 保持较小堆即可，例如 `-Xms256m -Xmx1g`
- 更多内存留给 mmap Page Cache
- 当前版本已经把主要数据放在堆外 `MemorySegment`

### 5.2 知识库导入建议

推荐流程：

1. 创建集合
2. 使用 JSON、NDJSON 或 binary batch 批量写入
3. 执行一次 `POST /collections/{name}/flush`
4. 再开始承接读流量

这样可以明显降低首批查询时的冷启动尖峰。

### 5.3 更新与删除建议

- **正文变化 / embedding 变化**：重新 upsert
- **只改 metadata**：走 `PATCH /collections/{name}/vectors`
- **删除**：走 `POST /collections/{name}/deletions`
- **离峰维护**：可手动调用 `POST /collections/{name}/compact`

### 5.4 备份与恢复建议

- 自动 snapshot 适合做日常保底，手工 backup/snapshot 更适合发布、迁移、批量导入后的显式留档
- `backupId` 只能使用 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}`，不要包含路径分隔符或相对路径
- 备份前尽量确保 collection 没有待处理的 flush / compaction；自动 snapshot 会先执行一次 `flush`
- 备份目录建议和数据目录分开
- 恢复时目标 collection 名必须不存在
- 如果开启自动 snapshot，建议把保留份数与磁盘容量一起做容量规划

### 5.5 请求大小与过载保护

- JSON、NDJSON 与 binary ingest 都受 `--max-request-body-bytes` 限制，超限返回 `413 Payload Too Large`
- NDJSON / binary 是流式分批写入，超限前已经提交的批次不会自动回滚
- `--max-concurrent-requests` 用于限制 HTTP 层并发处理数量，超限返回 `503` 并带 `Retry-After: 1`
- `--max-concurrent-source-searches` 限制单次查询 fan-out，active sources 多于该值时分批搜索
- `--foreground-searches-per-source-search` 和 `--min-adaptive-source-searches` 会在前台查询堆积时进一步降低单次查询 source 并发，保护 CPU 调度
- `--warmup-yield-poll-ms` 控制后台 resident warmup 对前台查询让路的等待粒度
- 生产环境建议在反向代理层同时设置 TLS、连接超时、读超时、请求头大小和请求体大小限制

### 5.6 WebUI

- 服务默认启用内置测试控制台：`http://127.0.0.1:30720/ui`
- WebUI 页面本身不要求 API key，但页面发出的数据库请求仍然走现有 API Key / RBAC / tenant 校验
- 如果生产环境不希望暴露测试页面，可设置 `--web-ui-enabled=false`

## 6. tenant / API key 文件示例

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

服务会按文件修改时间自动热重载该配置。
