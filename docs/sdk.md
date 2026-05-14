# Java SDK 使用说明

## 1. 定位

`anaxa-sdk` 是面向业务应用的 Java 客户端模块，目标是：

- **运行时基线为 JDK 25**
- **不依赖预览特性**
- **网络层直接使用 JDK 自带 `java.net.http.HttpClient`**
- **日志只依赖 `slf4j-api`，具体实现由接入方自行提供**

这意味着服务端依然可以继续运行在 JDK 26 + preview 模式下，而业务侧 SDK 可以保持更保守的 JDK 25 依赖面。

## 2. Maven 依赖

```xml
<dependencies>
  <dependency>
    <groupId>cn.suhoan</groupId>
    <artifactId>anaxa-sdk</artifactId>
    <version>1.4</version>
  </dependency>

  <!-- SDK 只依赖 slf4j-api，日志实现由接入方自行提供 -->
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.17</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

如果你的应用已经统一接入了 Logback、Log4j2 或其他 SLF4J 实现，就不需要再额外添加 `slf4j-simple`。

## 3. 构建方式

如果只需要在本机验证 SDK，可以直接在 **JDK 25** 下运行：

```powershell
mvn "-Dmaven.repo.local=D:\jarLibrary" -pl anaxa-sdk -am clean test
```

这里的 `-am` 会顺带构建 `anaxa-common`，但不会把 JDK 26 的服务端模块一起拉进来。

## 4. SDK 入口类

核心入口是：

- `cn.suhoan.anaxa.sdk.AnaxaClient`

典型初始化方式：

```java
try (AnaxaClient client = AnaxaClient.builder()
        .baseUri("http://127.0.0.1:30720")
        .apiKey("writer-secret")
        .defaultTenantId("team-a")
        .build()) {
    // ...
}
```

Builder 支持的常用参数：

| 参数 | 说明 |
| --- | --- |
| `baseUri(...)` | 服务端基础地址 |
| `apiKey(...)` | 默认 API Key；如果服务端运行在 open mode，可不传 |
| `defaultTenantId(...)` | 默认 `X-Tenant-Id` |
| `requestTimeout(...)` | 每个请求的超时时间 |
| `connectTimeout(...)` | SDK 自建 HttpClient 时的连接超时 |
| `httpClient(...)` | 允许业务侧传入自定义 HttpClient |
| `defaultHeader(...)` | 为所有请求附加自定义请求头 |

## 5. 基础 API

### 5.1 直接对应服务端的客户端

`AnaxaClient` 上提供的是偏“全局”的接口：

- `health()`
- `metrics()`
- `listCollections()`
- `createCollection(...)`
- `getCollection(...)`
- `listTenants()`
- `getTenant(...)`
- `listBackups()`
- `snapshotTenant(...)`

### 5.2 绑定单个 collection 的客户端

`client.collection("docs")` 会返回 `AnaxaCollectionClient`，常用方法包括：

- `stats()`
- `create(...)`
- `ensureExists(...)`
- `upsertJson(...)`
- `upsertNdjson(...)`
- `upsertBinary(...)`
- `partialUpdate(...)`
- `delete(...)`
- `search(...)`
- `flush()`
- `compact()`
- `backup(...)`
- `restoreFromBackup(...)`

## 6. 高阶 API

除了基础 REST 封装外，SDK 还提供了几类“按推荐流程编排”的高阶 API。

### 6.1 批量导入

`bulkUpsert(...)` 会自动：

1. 按 `batchSize` 分批
2. 按 `BulkIngestMode` 选择 JSON / NDJSON / binary
3. 在尾部按需补 `flush` / `compact`

相关类型：

- `BulkIngestMode`
- `BulkIngestOptions`
- `BulkIngestResult`

推荐默认模式是：

- `mode = BINARY`
- `batchSize = 512`

### 6.2 导入后切读

`loadForServing(...)` 面向“首批导入后立即开始提供检索服务”的场景，它会：

1. 先 `ensureExists(...)`
2. 再执行批量导入
3. 最后强制补一次 `flush`

这适合知识库首次建库、全量回灌、离线重建后的切流。

### 6.3 文档增删改同步

`synchronize(...)` 面向“日常增量同步”的场景，用一个 `CollectionSyncPlan` 同时描述：

- `upserts`
- `partialUpdates`
- `deletions`
- 各阶段 batch 大小
- upsert 使用的传输格式
- 是否在结尾自动 `flush`
- 是否在结尾自动 `compact`

这比业务方手动拼多次 HTTP 请求更不容易出错，也更方便统一记录日志。

## 7. 搜索过滤 DSL

为了避免业务代码手工拼 `$and` / `$or` / `$gte` 这类 JSON 结构，SDK 提供了：

- `PayloadFilterBuilder`

支持的便捷方法包括：

- `eq(...)`
- `in(...)`
- `contains(...)`
- `range(...)`
- `and(...)`
- `or(...)`
- `not(...)`
- `raw(...)`

示例：

```java
var filter = PayloadFilterBuilder.filter()
        .eq("tenant", "team-a")
        .in("category", List.of("guide", "faq"))
        .range("meta.priority", null, 5, 10, null);
```

## 8. 代码示例位置

仓库里已经附带了一组可编译的示例代码：

- `anaxa-sdk/src/main/java/cn/suhoan/anaxa/sdk/examples/AnaxaSdkExamples.java`

其中包含：

1. 基础 CRUD + 搜索示例
2. 首批导入并自动 flush 的示例
3. 面向知识库场景的增量同步示例

## 9. 与服务端 JDK 要求的关系

当前项目的 JDK 要求分成两层：

| 部分 | JDK 要求 |
| --- | --- |
| `anaxa-server` / `anaxa-engine` / `anaxa-index` / `anaxa-storage` | JDK 26，且服务端运行时需要 preview / vector 相关参数 |
| `anaxa-common` / `anaxa-sdk` | JDK 25，无 preview |

因此，业务侧如果只是接入 SDK，并不需要把自己的应用升级到 JDK 26 preview 运行模式。
