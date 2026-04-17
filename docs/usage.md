# 使用示例

假设服务运行在 `http://127.0.0.1:8080`。

## 1. 创建集合

```bash
curl -X POST "http://127.0.0.1:8080/collections" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "name": "docs",
    "dimension": 3,
    "metric": "COSINE",
    "flushThresholdBytes": 1048576
  }'
```

如果要落到指定 tenant：

```bash
-H "X-Tenant-Id: team-a"
```

## 2. JSON 批量写入

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
      }
    ]
  }'
```

## 3. NDJSON 批量导入

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/vectors" \
  -H "Content-Type: application/x-ndjson" \
  -H "X-API-Key: prod-secret-1" \
  --data-binary @- <<'EOF'
{"id":"alpha","vector":[1.0,0.0,0.0],"payload":{"tenant":"blue","path":"guide/intro.md"}}
{"id":"beta","vector":[0.0,1.0,0.0],"payload":{"tenant":"red","path":"faq/common.md"}}
EOF
```

## 4. Partial update（只改 payload）

```bash
curl -X PATCH "http://127.0.0.1:8080/collections/docs/vectors" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
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
  }'
```

适合：

- 改标题
- 改标签
- 改 path / source / ACL
- 其它 metadata-only 变更

## 5. 相似检索

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

## 6. 复杂过滤检索

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

## 7. 删除文档

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/deletions" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: prod-secret-1" \
  -d '{
    "ids": ["beta"]
  }'
```

## 8. 导入完成后手动 flush

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/flush" \
  -H "X-API-Key: admin-secret"
```

对于“批量导入后快速切读”的知识库场景，这一步通常值得做。

## 9. 离峰手动 compaction

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/compact" \
  -H "X-API-Key: admin-secret"
```

## 10. 查看健康状态与集合统计

```bash
curl "http://127.0.0.1:8080/health"
```

```bash
curl "http://127.0.0.1:8080/collections/docs" \
  -H "X-API-Key: prod-secret-1"
```

## 11. 查询 Prometheus 指标

```bash
curl "http://127.0.0.1:8080/metrics" \
  -H "X-API-Key: admin-secret"
```

## 12. 执行备份

```bash
curl -X POST "http://127.0.0.1:8080/collections/docs/backup" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: admin-secret" \
  -d '{
    "backupId": "nightly-001"
  }'
```

## 13. 从备份恢复到新集合

```bash
curl -X POST "http://127.0.0.1:8080/backups/nightly-001/restore" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: admin-secret" \
  -d '{
    "sourceCollection": "docs",
    "collectionName": "docs-restored"
  }'
```

## 14. 知识库场景推荐流程

### 14.1 初始导入

1. 创建 collection
2. 使用 NDJSON 或大批量 JSON 导入
3. 执行 `flush`
4. 开始提供搜索服务

### 14.2 日常编辑

- 新增文档：`POST /vectors`
- 正文变化：重新 upsert 新向量
- 只改 metadata：`PATCH /vectors`
- 删除文档：`POST /deletions`

### 14.3 维护窗口

- 用 `compact` 清理 stale version 和 tombstone
- 用 backup/restore 做逻辑备份与恢复
