# HTTP 接口文档

## 1. 通用约定

- 除 `/metrics` 外，接口默认返回 JSON
- 所有响应都会携带：

  ```text
  X-Trace-Id: <trace-id>
  ```

- 如果请求头中显式传入 `X-Trace-Id`，服务会透传；否则服务端自动生成

鉴权头：

```text
X-API-Key: <your-key>
```

或：

```text
Authorization: Bearer <your-key>
```

tenant 头：

```text
X-Tenant-Id: <tenant-id>
```

## 2. 角色与路由

| 角色 | 可访问能力 |
| --- | --- |
| `READER` | 读集合、搜索、查看统计 |
| `WRITER` | 创建集合、写入、partial update、删除；同时具备 `READER` 能力 |
| `ADMIN` | `WRITER` 全部能力，以及 `/metrics`、flush、compaction、备份与恢复 |

## 3. 路由总览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 健康检查 |
| `GET` | `/metrics` | Prometheus 指标 |
| `POST` | `/collections` | 创建集合 |
| `GET` | `/collections` | 查询集合列表 |
| `GET` | `/collections/{name}` | 查询集合统计 |
| `POST` | `/collections/{name}/vectors` | JSON 包装批量写入 |
| `POST` | `/collections/{name}/vectors` | NDJSON 流式写入（`application/x-ndjson`） |
| `PATCH` | `/collections/{name}/vectors` | payload-only partial update |
| `POST` | `/collections/{name}/deletions` | 删除向量 |
| `POST` | `/collections/{name}/flush` | 手动 flush |
| `POST` | `/collections/{name}/compact` | 手动 compaction |
| `POST` | `/collections/{name}/search` | 向量检索 |
| `POST` | `/collections/{name}/backup` | 备份集合 |
| `POST` | `/backups/{backupId}/restore` | 恢复集合 |

## 4. 健康检查

### `GET /health`

响应：

```json
{
  "status": "UP"
}
```

## 5. 指标导出

### `GET /metrics`

返回 Prometheus 文本格式指标，包含 HTTP、search、flush、compaction、cache、tenant/collection 统计等。

代表性指标：

- `anaxa_http_requests_total`
- `anaxa_search_queries_total`
- `anaxa_search_query_cache_hits_total`
- `anaxa_search_source_index_cache_hits_total`
- `anaxa_engine_flush_total`
- `anaxa_engine_compaction_total`
- `anaxa_engine_live_vectors`
- `anaxa_engine_collection_storage_bytes`

## 6. 创建集合

### `POST /collections`

请求体：

```json
{
  "name": "docs",
  "dimension": 768,
  "metric": "COSINE",
  "flushThresholdBytes": 67108864
}
```

字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 集合名，匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,127}` |
| `dimension` | 是 | 向量维度，必须大于 0 |
| `metric` | 否 | `COSINE` 或 `L2`，默认 `COSINE` |
| `flushThresholdBytes` | 否 | flush 阈值，未传则使用服务默认值 |

## 7. 查询集合

### `GET /collections`

返回当前 tenant 视图下的集合列表；全局 admin key 在不带 `X-Tenant-Id` 时可查看所有 tenant。

### `GET /collections/{name}`

返回集合统计：

```json
{
  "name": "docs",
  "dimension": 768,
  "metric": "COSINE",
  "liveVectorCount": 30000,
  "tombstoneCount": 0,
  "segmentCount": 1,
  "flushInProgress": false,
  "compactionInProgress": false,
  "tenantId": "default",
  "storageBytes": 178223867
}
```

## 8. 写入向量

### 8.1 JSON 包装批量写入

### `POST /collections/{name}/vectors`

`Content-Type: application/json`

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
    }
  ]
}
```

字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `vectors` | 是 | 待写入向量列表 |
| `vectors[].id` | 是 | 向量唯一标识 |
| `vectors[].vector` | 是 | 浮点向量，长度必须与集合维度一致 |
| `vectors[].payload` | 否 | 任意 JSON 对象 |

### 8.2 NDJSON 流式批量写入

### `POST /collections/{name}/vectors`

`Content-Type: application/x-ndjson`

请求体每行都是一个向量对象：

```json
{"id":"alpha","vector":[1.0,0.0,0.0],"payload":{"tenant":"blue"}}
{"id":"beta","vector":[0.0,1.0,0.0],"payload":{"tenant":"red"}}
```

行为：

- 服务按批次流式解析，不需要客户端先包装成大数组
- 适合知识库初始导入或批量补录

## 9. Partial update

### `PATCH /collections/{name}/vectors`

请求体：

```json
{
  "updates": [
    {
      "id": "alpha",
      "payload": {
        "title": "Intro v2",
        "obsolete": null,
        "meta": {
          "section": "basics",
          "published": true
        }
      }
    }
  ]
}
```

行为：

- 只允许更新当前仍然是 live 的文档
- 递归 merge payload
- patch 中值为 `null` 的字段会被删除
- 不要求客户端重传向量

适用场景：

- 改标题、标签、路径、权限、tenant 元数据
- 不需要重新计算 embedding 的 metadata-only 修改

## 10. 删除向量

### `POST /collections/{name}/deletions`

请求体：

```json
{
  "ids": ["alpha", "beta"]
}
```

行为：

- 只对当前仍然 live 的 ID 生成 tombstone
- 已删除或不存在的 ID 会被忽略
- 删除后旧向量会立即从检索结果中消失

## 11. Flush 与 Compaction

### `POST /collections/{name}/flush`

行为：

- 把当前 active MemTable 立即冻结并落盘
- 适合“批量导入后准备读”
- 如果当前已经有后台 flush，请求会等待其结束

### `POST /collections/{name}/compact`

行为：

- 保留每个 ID 的最新 live 版本
- 清理旧版本和不再需要的 tombstone
- 更适合作为离峰维护动作

## 12. 向量检索

### `POST /collections/{name}/search`

请求体：

```json
{
  "vector": [1.0, 0.0, 0.0],
  "topK": 5,
  "filter": {
    "$and": [
      { "tenant": { "$in": ["blue", "green"] } },
      { "meta.priority": { "$gte": 5, "$lt": 10 } },
      { "tags": { "$contains": "featured" } }
    ]
  }
}
```

字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `vector` | 是 | 查询向量 |
| `topK` | 是 | 返回数量，必须大于 0 |
| `filter` | 否 | Payload 过滤表达式 |

当前支持的过滤能力：

- 精确匹配
- 嵌套字段（如 `meta.region`）
- `$in`
- `$contains`
- `$gt` / `$gte` / `$lt` / `$lte`
- `$and` / `$or` / `$not`

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
    }
  ]
}
```

## 13. 备份与恢复

### `POST /collections/{name}/backup`

请求体：

```json
{
  "backupId": "nightly-001"
}
```

### `POST /backups/{backupId}/restore`

请求体：

```json
{
  "sourceCollection": "docs",
  "collectionName": "docs-restored"
}
```

恢复后的目标集合名必须不存在。

## 14. 错误响应

统一格式：

```json
{
  "message": "Collection not found: docs",
  "traceId": "3f59d8cb-52b0-4708-9bbc-09a5f3d4f651"
}
```

常见状态码：

| 状态码 | 场景 |
| --- | --- |
| `400` | 请求体为空、维度不匹配、`topK <= 0`、非法参数、partial update 目标不存在 |
| `401` | API Key 缺失或无效 |
| `403` | 角色权限不足或 tenant 越权 |
| `404` | collection 不存在或路径不存在 |
| `405` | 方法不允许 |
| `409` | 配额超限或恢复目标集合已存在 |
| `429` | 超过限流阈值 |
| `500` | 内部异常，例如后台 flush 失败 |
