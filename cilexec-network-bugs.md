# CilExec 已确认网络相关 Bug 汇总

仓库：`SuHeling1212/cilexec`  
目标分支：`master`  
范围：当前已确认的网络、下载、TLS、Socket、DNS 与代理兼容问题。

---

## 1. HTTPS 原始主机名证书校验错误

### 涉及文件

- `src/main/java/com/follarce/effect/PinnedHttpClient.java`

### 当前逻辑

`PinnedHttpClient` 会先通过 `NetworkTargetPolicy` 解析并验证目标地址，然后把 URL 主机替换成已经验证过的 IP，以避免 DNS rebinding。

HTTPS 情况下，代码大致为：

```java
var defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
https.setHostnameVerifier((ignoredPinnedAddress, session) ->
        defaultVerifier.verify(uri.getHost(), session));
```

同时通过自定义 `PinnedSslSocketFactory`：

- TCP 连接到已经验证的 IP
- TLS 的 `tlsHost` 使用原始域名
- SNI 尝试使用原始域名

### 问题

`HttpsURLConnection.getDefaultHostnameVerifier()` 并不是可以单独复用的“JDK 默认 HTTPS 主机名验证算法”。OpenJDK 的默认 `HostnameVerifier` 回调本身并不承担正常 HTTPS 主机名匹配；正常验证由 HTTPS/TLS 内部流程完成。

当前 URL 主机已经变成 pinned IP，因此 JDK 内部校验首先会按 IP 检查证书。当证书只对原始域名有效时：

1. JDK 按 pinned IP 检查证书；
2. 检查失败；
3. 调用自定义 verifier；
4. 自定义 verifier 再调用 `getDefaultHostnameVerifier()`；
5. 该 verifier 并不会替代真正的默认 HTTPS 主机名检查；
6. 最终 HTTPS 请求失败。

### 影响

正常的公网 HTTPS 站点可能无法通过证书验证，即使 DNS 解析、IP 安全检查、SNI 与证书本身都正确。

### 建议修复

保留安全模型：DNS 只解析一次、校验解析出的地址、TCP 只连接已校验地址、SNI 使用原始域名、证书主机名验证也必须使用原始域名。

优先考虑让 TLS socket 的 peer host 保持原始 hostname，并启用：

```java
SSLParameters parameters = ssl.getSSLParameters();
parameters.setEndpointIdentificationAlgorithm("HTTPS");
```

由标准 TLS hostname verification 对原始 hostname 做校验，而不是尝试调用 `getDefaultHostnameVerifier()`。

### 建议测试

增加本地 HTTPS 集成测试：URL hostname 为测试域名，实际 TCP 连接 pinned 到测试 IP，证书 SAN 只包含测试域名；验证匹配时成功、不匹配时失败。

---

## 2. HTTP/Download 异常状态路径没有确定性关闭连接

### 涉及文件

- `src/main/java/com/follarce/effect/PinnedHttpClient.java`
- `src/main/java/com/follarce/effect/BuiltinEffectHandlers.java`

### 当前逻辑

`PinnedHttpClient.Response` 的 body 包装了 `DisconnectingInputStream`，只有在 body 被关闭时才执行 `connection.disconnect()`。

但 `HttpHandler` 当前顺序类似：

```java
PinnedHttpClient.Response response = PinnedHttpClient.send(...);
requireNoRedirect(response.statusCode());

try (InputStream input = response.body()) {
    ...
}
```

`DownloadHandler` 当前顺序类似：

```java
PinnedHttpClient.Response response = PinnedHttpClient.send(...);
requireNoRedirect(response.statusCode());
requireDownloadStatus(response.statusCode());

try (InputStream input = response.body()) {
    ...
}
```

### 问题

如果 `requireNoRedirect(...)` 或 `requireDownloadStatus(...)` 抛异常，代码还没有进入 body 的 try-with-resources，因此 `response.body().close()` 不会被调用，`connection.disconnect()` 也不能保证执行。

### 影响

可能导致 HTTP 连接资源延迟释放、socket/连接资源占用，以及高频异常请求下资源堆积。普通 `HttpHandler` 主要在 3xx redirect 上命中；`DownloadHandler` 对 3xx、404、500 等不允许状态都可能命中。

### 建议修复

让 `Response` 实现 `AutoCloseable`：

```java
record Response(...) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        body.close();
    }
}
```

然后：

```java
try (PinnedHttpClient.Response response = PinnedHttpClient.send(...)) {
    requireNoRedirect(response.statusCode());
    ...
}
```

或者至少把状态检查移动到 body try-with-resources 内。

### 建议测试

覆盖 redirect、download 404、download 500，并验证 response/stream 一定被关闭，连接清理不依赖 GC。

---

## 3. 0 字节文件在合法 416 EOF 响应下下载失败

### 涉及文件

- `src/main/java/com/follarce/effect/BuiltinEffectHandlers.java`
- `src/main/java/com/follarce/application/FclRuntimeFunctions.java`

### 当前行为

下载从 `offset = 0` 开始并发送 Range。一个真正长度为 0 的文件，服务器可以合法返回：

```http
416 Range Not Satisfiable
Content-Range: bytes */0
```

`DownloadHandler` 已经能够把这种情况解析为：

- `status = 416`
- `total = 0`
- `complete = true`
- `offset = 0`

但 `FclRuntimeFunctions.download()` 中只有：

```java
if (status == 416 && complete && total == offset && currentHash.isPresent()) {
    ...
}
```

才把 416 当作成功 EOF。

第一次下载空文件时 `currentHash = empty`，因此不会进入成功分支，随后通用状态判断会把合法的 416 当作失败。

### 影响

合法 0 字节远程文件可能无法通过 `network.download()` 下载。如果服务端忽略 Range 并直接返回 `200 OK` + 空 body，当前逻辑可能成功，所以 bug 专门存在于合法 416 EOF 路径。

### 建议修复

当满足：

```text
status == 416
complete == true
offset == 0
total == 0
currentHash.empty
```

时，创建并持久化一个空对象，然后正常完成下载。

### 建议测试

服务端返回 `416` + `Content-Range: bytes */0`，验证下载成功、创建空对象、文件大小为 0、不会抛 416 错误。

---

## 4. 多块下载缺少稳定 validator 时可能拼接两个远端版本

### 涉及文件

- `src/main/java/com/follarce/effect/BuiltinEffectHandlers.java`
- `src/main/java/com/follarce/application/FclRuntimeFunctions.java`

### 当前逻辑

如果第一块响应有 strong ETag 或 Last-Modified，代码会保存 `validator`，后续请求发送：

```http
If-Range: <validator>
```

这是正确设计。

但是当前代码允许：第一块返回未完成的 `206 Partial Content`，同时没有 strong ETag、没有 Last-Modified，却仍继续下一块下载。

### 失败场景

1. 下载文件版本 A 的第 1 块；
2. 第 1 块没有 validator；
3. 远端文件更新成版本 B；
4. 下载第 2 块；
5. 由于没有 `If-Range`，服务端继续返回 B 的 range；
6. CilExec 把 B 的内容追加到 A 的前半部分；
7. 最终得到一个 A+B 混合文件。

如果普通 `network.download()` 调用方没有额外提供完整文件 SHA-256，混合文件可能被当作成功结果。

### 影响

这是数据正确性问题，可能静默产生远端从未存在过的文件内容。Market 下载路径如果之后有预期 SHA-256 校验，则可能被最终 hash 检查拦截；普通 `network.download()` 没有这种天然保证。

### 建议修复

如果：

```text
status == 206
complete == false
```

则必须要求存在稳定 validator：strong ETag 或 Last-Modified。如果不存在，直接失败，不继续下一块。

原则：宁可拒绝不安全的多块下载，也不要静默拼接多个版本。

### 建议测试

- 未完成 206 且无 ETag/Last-Modified：必须失败；
- strong ETag：后续 `If-Range` 正确发送并成功；
- Last-Modified：后续正常继续；
- weak ETag `W/"..."` 不应被当作稳定 validator。

---

## 5. Socket 的 30 秒 timeout 不是整体接收 deadline

### 涉及文件

- `src/main/java/com/follarce/effect/BuiltinEffectHandlers.java`
- `SocketHandler`

### 当前逻辑

receive/send/accept 中使用：

```java
socket.setSoTimeout(30_000);
```

然后 `read()` 循环不断调用 `input.read(...)`。

### 问题

`SO_TIMEOUT` 限制的是单次阻塞 `read()`。如果对端每 29 秒发送 1 byte，每次 read 都能在 timeout 前返回，下一轮 read 又重新拥有新的 30 秒，因此整个接收过程可以无限持续。

### 影响

攻击者或异常 peer 可以长时间占用 effect worker 和 socket，形成 slowloris/trickle 风格的资源耗尽。HTTP 已经有类似总 deadline，但 Socket 没有。

### 建议修复

给整个 socket 操作增加绝对 deadline。每次 read 前：

1. 计算剩余时间；
2. 若剩余时间 <= 0，失败；
3. 将 `SO_TIMEOUT` 设置为 `min(perReadTimeout, remaining)`；
4. 再执行 read。

这样同时具备单次 read timeout 和整体 deadline。

### 建议测试

使用本地 server 持续慢速发送字节，测试专用 deadline 设置为较短时间，确认整体 deadline 到达后结束，而不是无限续期。

---

## 6. HTTP 的 120 秒总 deadline 无法真正限制 DNS 阻塞

### 涉及文件

- `src/main/java/com/follarce/effect/PinnedHttpClient.java`
- `src/main/java/com/follarce/effect/NetworkTargetPolicy.java`

### 当前逻辑

`PinnedHttpClient.send()` 先记录开始时间，然后调用：

```java
NetworkTargetPolicy.ResolvedHttpTarget target =
        NetworkTargetPolicy.resolveHttpTarget(uri);
```

解析内部调用：

```java
InetAddress[] addresses = InetAddress.getAllByName(host);
```

返回后才会执行 `enforceDeadline(...)`。

### 问题

虽然 DNS 时间被算进总耗时，但如果 `InetAddress.getAllByName(host)` 本身阻塞超过 120 秒，代码无法在第 120 秒主动中断它，因为执行线程仍然卡在 DNS 调用里。

### 影响

所谓“120 秒 total HTTP deadline”并不是严格 wall-clock 上限。异常 DNS resolver / 系统 DNS 卡死时，effect worker 仍可能被长期占用。

### 建议修复方向

需要对 DNS 解析本身做独立、有界处理。可以考虑在受控 worker/virtual thread 中做解析，由调用方进行有时限等待，并限制并发 DNS 解析任务数量。

注意：底层 DNS 是否响应 thread interruption 取决于实现，因此简单 `Future.cancel(true)` 不一定真正终止解析。必须避免每次超时都留下无法回收的 resolver 任务，也不能为了超时而重新解析目标从而破坏 DNS pinning。

### 建议测试

把 resolver 抽象为可注入组件，测试正常 resolver、阻塞 resolver、超时 resolver，验证 effect 能在规定 deadline 内失败。

---

## 7. DNS 多地址只使用第一个安全 IP，没有 failover

### 涉及文件

- `src/main/java/com/follarce/effect/NetworkTargetPolicy.java`
- `src/main/java/com/follarce/effect/PinnedHttpClient.java`
- Socket 网络路径可能也有类似问题

### 当前逻辑

DNS：

```java
InetAddress[] addresses = InetAddress.getAllByName(host);
```

随后会检查所有地址，这一点安全上是正确的。但最后只返回：

```java
return addresses[0];
```

### 失败场景

例如 DNS 返回：

```text
IPv6 地址 A
IPv4 地址 B
```

两者都是允许的公网地址，但当前环境 IPv6 不可达、IPv4 可达。CilExec 只尝试 A，A 失败后直接返回，不会尝试 B。

### 影响

在 IPv6 配置不完整、多 A/AAAA 记录、CDN 多地址、单节点临时不可达等环境中，可能产生无意义失败。

### 安全修复原则

不能为了 failover 在每次失败后重新 DNS resolve。正确方式：

1. DNS 解析一次；
2. 得到完整地址列表；
3. 一次性验证所有地址；
4. 保存已验证地址列表；
5. 只在这份列表中尝试连接；
6. 连接过程中不重新解析 hostname。

这样既保留 DNS rebinding 防护，又能安全做 multi-IP fallback。

### 建议修改

把单一 `InetAddress address` 改为类似 `List<InetAddress> addresses`，连接层按顺序尝试地址。

### 建议测试

- 第一个安全 IP 不可达、第二个安全 IP 可达：最终连接第二个成功；
- 地址列表中只要存在 private/special-use 地址：整组拒绝，而不是跳过后继续连公网地址。

---

## 8. fake-IP DNS / 系统代理环境与当前直连安全模型不兼容

### 涉及文件

- `src/main/java/com/follarce/effect/PinnedHttpClient.java`
- `src/main/java/com/follarce/effect/NetworkTargetPolicy.java`

### 当前行为

连接明确使用：

```java
.openConnection(Proxy.NO_PROXY)
```

同时 SSRF 策略会阻止 `198.18.0.0/15` 等特殊用途地址。

### 问题

部分代理软件的 fake-IP 模式会让 DNS 返回 `198.18.x.x` 一类 fake IP，再由本地代理映射回真实域名并转发。CilExec 会在代理接管前把这个地址识别成 special-use 并拒绝，同时 `Proxy.NO_PROXY` 又明确绕过 Java 代理配置。

### 影响

在依赖 fake-IP DNS、Java 系统代理、显式 HTTP CONNECT 代理或某些本地代理组合时，公网请求可能失败。

### 重要安全要求

不能简单允许 `198.18.0.0/15`，因为这会削弱 SSRF / special-use 地址隔离；也不应默认自动信任系统代理。

### 建议设计

增加管理员显式开启的 trusted proxy 模式，例如：

```text
CILEXEC_NETWORK_PROXY=...
CILEXEC_NETWORK_TRUST_PROXY=true
```

默认关闭。启用后只连接管理员配置的固定 proxy；SSRF 检查仍应用于逻辑目标；明确 DNS 是 CilExec 侧还是 proxy 侧解析；不允许 FCL 用户自行指定任意代理地址。

这是兼容性增强，不应以牺牲默认安全边界为代价。

---

# 建议修复优先级

## P0 / 正确性与数据完整性

1. HTTPS hostname verification
2. HTTP response 异常路径确定性关闭
3. 0 字节 416 下载
4. 无 validator 的多块下载

## P1 / 可靠性与资源边界

5. Socket 总 deadline
6. DNS timeout
7. 多 IP failover

## P2 / 环境兼容

8. trusted proxy / fake-IP 支持

---

# 必须补充的回归测试

至少应覆盖：

- pinned IP + 原始 hostname HTTPS 证书验证成功；
- hostname 不匹配时 HTTPS 必须失败；
- redirect/error status 后连接始终被关闭；
- `416 Content-Range: bytes */0` 下载空文件成功；
- 未完成 206 且无 stable validator 时失败；
- strong ETag 多块下载成功；
- Last-Modified 多块下载成功；
- weak ETag 不视为稳定 validator；
- Socket slow trickle 被整体 deadline 中止；
- DNS resolver 超时不会无限占用 effect；
- 第一个安全 IP 不可达时 fallback 第二个；
- DNS 列表中只要有 private/special-use 地址则整组拒绝；
- 默认模式继续拒绝 `198.18.0.0/15`；
- trusted proxy 若实现，默认关闭。

---

# 总体安全原则

修复这些问题时应保持 CilExec 当前的核心网络安全模型：

1. 不允许因为兼容问题直接放宽 SSRF；
2. DNS 解析后必须对全部候选地址执行安全检查；
3. DNS pinning 后连接阶段不得重新解析 hostname；
4. HTTPS 必须验证原始逻辑 hostname，而不是 pinned IP；
5. 多块下载必须避免跨版本拼接；
6. 网络 effect 必须有明确的总执行时间边界；
7. proxy 支持必须是管理员显式配置，而不是用户可控绕过通道；
8. 遇到无法保证正确性的情况，优先失败，不要静默产生错误数据。
