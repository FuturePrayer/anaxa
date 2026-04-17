/**
 * AnaxaDB 官方 Java SDK。
 *
 * <p>这个模块刻意保持在 <strong>JDK 25 + 无预览特性</strong> 的基线上，便于在普通业务应用中直接引入。
 * 底层网络调用完全基于 JDK 自带的 {@link java.net.http.HttpClient}，不再额外引入第三方 HTTP 客户端。
 *
 * <p>SDK 分为两层：
 *
 * <ul>
 *   <li><strong>基础 API</strong>：一一映射服务端 HTTP 接口，适合需要精确控制每一步操作的调用方。</li>
 *   <li><strong>高阶 API</strong>：针对“批量导入后切读”“文档增删改同步”等常见向量数据库场景，
 *   直接把推荐流程封装成可复用的方法。</li>
 * </ul>
 */
package cn.suhoan.anaxa.sdk;
