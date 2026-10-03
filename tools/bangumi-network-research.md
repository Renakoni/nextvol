# Bangumi 官方直连：DoH 与 ECH 调研

2026-10-03。本轮只验证网络可行性，不修改业务代码或应用配置。结论限于本次手机蜂窝网络与所列版本，不能外推为所有运营商均可用。

**单独内置 DoH 不足以解决本次故障；支持 ECH 的客户端配合可直连的 DoH，已经能访问 `https://bgm.tv/` 和官方 `api.bgm.tv`。`https://bangumi.tv/` 的服务器与前两者不同，本轮未解决它的直连。**

## 现有功能的实际边界

| 入口 | 调用链与域名 | 对后续改造的含义 |
|---|---|---|
| Wenku8 搜索补充 | `Wenku8SearchSession` → `BangumiSearchExpansion` → `BangumiSearchApi` → 官方 `BangumiApi`，使用 `https://api.bgm.tv/` | 关键词、标签和条目关系都是公开查询 |
| 搜索备用线路 | `BangumiSearchApi` 单独创建客户端访问 `https://api.bangumi.vip/` | 仅用于上述公开查询；不接收账号令牌、不参与同步 |
| 设置中的账号验证、匹配与同步 | `BangumiViewModel` / `BangumiSyncWork` → `BangumiRepository` → `BangumiApi` | `/v0/me`、收藏读取、创建与进度更新仍须访问官方 API |
| 获取令牌 | `BangumiScreen` 使用 `openUri("https://next.bgm.tv/demo/access-token")` | 打开外部浏览器；应用内部的 DNS/TLS 配置无法接管这个请求 |
| 粘贴条目链接 | `BangumiApi.subjectId()` 接受 `bgm.tv`、`bangumi.tv`、`chii.in` | 只解析条目 ID，再查询官方 API，不会请求粘贴链接的网页 |

当前 `BangumiApi` 使用 OkHttp 5.4.0，30 秒调用超时，禁止 HTTP/HTTPS 重定向；`BangumiSession` 负责添加 Bearer 令牌和取消会话请求。搜索官方线路与同步共享这个官方 API 实例。搜索镜像客户端独立，保留现有 750 毫秒启动备用线路、每路最多 8 秒、最多四个并发请求的规则即可。

因此，“修复应用内搜索和同步”首先要覆盖 `api.bgm.tv`，不能把网页域名直接替换成 API 地址。`.vip` 的使用范围无需扩大。

## 实测方法与结果

电脑启用了 Mihomo TUN。即便设置 `--noproxy '*'`，电脑请求仍可能经过 TUN，因此电脑只用于下载参考资料、工具及解析结果的对照，不作为直连成功证据。

直连请求全部在 Android 14 真机的 adb shell 中运行。检查时默认网络是蜂窝网络，未发现活动 VPN，系统 HTTP 代理为空；前后默认网络一致。使用系统 curl 8.0.1-DEV / BoringSSL，以及隔离目录中的静态 curl 8.22.0 / OpenSSL 4.0.2。后者明确列出 ECH、HTTPSRR 功能，下载包 SHA-256 与发布页 digest 一致。HTTPS 保留证书链和目标主机名校验，没有使用 `-k`、信任所有证书或改写账号接口的 Host。

所有操作都在后台完成：没有启动/停止阅读器、安装 APK、操作界面、重启 adb、切换网络或调整系统 DNS/代理。没有读取账号令牌，也没有向真实账号写入收藏或进度。两个 adb 客户端可以同时使用现有 adb server；本轮只按指定设备执行独立 shell 请求。

| 对象 | 系统 DNS / 普通 TLS | 修正地址、保留普通 TLS | DoH + ECH |
|---|---|---|---|
| `https://bgm.tv/` | 连接超时 | Cloudflare IPv4、IPv6 均在 TLS 阶段被重置 | HTTP 200，ECH succeeded，主机名和证书校验通过 |
| `https://api.bgm.tv/v0/subjects/19441` | 连接超时 | Cloudflare IPv4、IPv6 均在 TLS 阶段被重置 | HTTP 200，JSON 中 ID 为 19441 |
| `/v0/search/subjects` | 同一官方 API 域名 | 同上 | 关键词、标签两个 POST 均返回 200；解析到非空 JSON 结果列表 |
| `/v0/subjects/19441/subjects`、`/persons` | 同一官方 API 域名 | 同上 | HTTP 200，均可解析为 JSON 数组 |
| `https://api.bgm.tv/v0/me`，不带令牌 | 连接超时 | TLS 被重置 | HTTP 401，证明已到达认证接口；不代表账号同步验收通过 |
| `https://next.bgm.tv/demo/access-token` | 连接超时 | 已核实的 IPv6 地址仍在 TLS 阶段被重置 | HTTP 302，指向同站 `/demo/login?backTo=%2Fdemo%2Faccess-token`；未执行登录 |
| `https://bangumi.tv/` | 在 `178.79.181.137` 建立 TCP 后，TLS 被重置 | 部分国内 DoH 给出与境外解析不一致的地址 | Total-ECH 返回该独立服务器地址及空 HTTPS 答案，严格 ECH 模式失败 |
| `http://bgm.tv/` | 连接超时 | IPv4 超时、IPv6 在 HTTP 请求后重置 | ECH 不适用于明文 HTTP；应在发请求前选择官方 HTTPS 地址 |
| 搜索镜像 `api.bangumi.vip` 的公开条目接口 | HTTP 200 | 未进一步改造 | 不承担官方账号服务 |

为区分 DNS 和 TLS 的作用，先通过 DoH 获取地址，再使用 `--resolve` 保留原 URL、SNI 和证书验证。正确地址能建立 TCP，却在发送普通 TLS 握手后失败；同一域名、地址和客户端开启 ECH 后成功，支持“存在基于握手信息的阻断”的判断。没有抓包，不能把全部网络失败都归因于同一种阻断。

首轮八组开关对照中，关闭 ECH 的八次请求全部失败，开启 ECH 的六次返回预期 HTTP 状态，两次 IPv6 请求仍被重置。随后换用同组 DNS 答案中的另一对 IPv4/IPv6 地址，对主站、条目、认证接口、令牌页入口各复测两轮，16 次均返回预期状态。样本规模不足以估计长期成功率，也没有覆盖 ECH 密钥轮换或网络切换。

还完成了无需电脑提供目标解析结果的完整流程：手机先解析 DoH 服务的引导地址，curl 设置 Total-ECH demo 的 `--doh-url` 和 `--ech hard`，自动查询目标地址及 HTTPS RR，再访问官方服务。`bgm.tv`、`api.bgm.tv`、`next.bgm.tv` 分别返回 200、200、302；日志明确显示 `status is succeeded`、正确的 inner 域名与 `cloudflare-ech.com` outer 名称。`bangumi.tv` 则报告 `requested but no ECHConfig available`。

## DoH 服务与 Total-ECH 的作用

DoH 加密的是 DNS 查询的传输，不能保证解析器返回正确结果，也不会自动加密业务连接中的 SNI。ECH 需要客户端 TLS 实现、可用 ECHConfig 和服务端共同支持，且只适用于 TLS 1.3。[1][2]

| 本轮查询方式 | 观察 | 选型含义 |
|---|---|---|
| 手机直连阿里 DoH | `bgm.tv` 的 A 记录出现 `202.160.128.205`，与境外解析及成功连接不一致 | 不能因为是 HTTPS DNS 就认定答案可靠 |
| 手机直连腾讯 DoH | JSON 查询一度返回正确的 `bgm.tv`、`api.bgm.tv` Cloudflare 地址；后续 wire-format 查询又返回不一致地址及提示地址 | 结果依赖查询时刻和接口，不能将一次成功作为稳定保证 |
| 手机直连腾讯 DoH 查询 `cloudflare-ech.com` | HTTPS/type 65 查询可返回 ECHConfig，与本次成功握手使用的配置一致 | 本轮可用于配置获取，但不等于它对全部 Bangumi 域名都可靠 |
| 手机直接查询 Google / Cloudflare DoH | 所测试的普通连接超时或 TLS 超时 | 还需解决 DoH 服务本身的可达性与首次解析 |
| 电脑经 TUN 查询 Google DoH | `bgm.tv`、`api.bgm.tv`、`next.bgm.tv` 有 Cloudflare 地址及 HTTPS RR，但没有 `ech` 参数；`bangumi.tv` 为独立服务器且无 HTTPS RR | 仅作为解析对照，不能作为手机直连证据 |
| 手机直连 Total-ECH demo | 为三个 Cloudflare 域名返回带 `ech` 的 HTTPS RR，完整流程成功；未给 `bangumi.tv` 添加配置 | 验证了方案机制，不能把公开 demo 当作应用的长期基础设施 |

Total-ECH 是部署在 Cloudflare Workers 上的 DNS 服务。所审阅版本从 Google DoH 获取答案，识别 Cloudflare/Meta 地址归属，对符合条件的 HTTPS/type 65 查询构造带 ECH 参数的答案；Cloudflare 配置来自 `cloudflare-ech.com`，还支持显式指定替换地址。[3]

它不是转发 Bangumi 账号请求的业务镜像，也不能让任意服务器支持 ECH。本次 `bangumi.tv` 的地址不属于上述已验证的 Cloudflare 服务；向该服务器提供 Cloudflare ECHConfig，以及把它强行连到测试用 Cloudflare 地址，均发生握手失败。不能把域名重定向到某个 Cloudflare IP 当作通用修复。

上游 README 自述只验证过桌面 Chrome/Firefox，公开 demo 不承诺维护。本轮补充的是 Android shell 中独立 TLS 客户端的验证，不是 NextVol 应用内实现。若采用这种方式，须有可维护的解析服务、引导地址策略、缓存与轮换处理；不应把本次 IP、ECHConfig 或 demo 地址写死为长期保证。

## Android 接入选择

核对的是项目当前使用的 **OkHttp 5.4.0 tag**，不是不断变化的主分支。该版本 `DnsOverHttps.lookup()` 只发 A/AAAA 查询，`DnsRecordCodec` 只解码地址记录；`Dns.lookup()` 返回 `List<InetAddress>`。把 Total-ECH URL 填进这个 DoH 实现，无法把 HTTPS RR 中的 ECHConfig 交给 TLS 握手。[4]

| 方案 | 本轮证据与代价 | 建议 |
|---|---|---|
| 现有 OkHttp 只增加 DoH | 改动较小，但普通 TLS 对照已失败；5.4.0 的 DoH 接口没有 ECH 配置通路 | 不能作为本需求的完整交付 |
| 官方 API 使用支持 ECH 的原生 HTTPS 传输 | 独立 libcurl/OpenSSL 工具已经验证协议和连接可行性；还需 Android 库封装、系统信任接入、取消、超时、ABI 与包体评估 | 优先做下一轮小范围 PoC，先只覆盖 `api.bgm.tv` |
| 博客所述 Rust 本地 CONNECT/MITM 代理 | 能适配现有 OkHttp，但多出本地代理、证书生成、双层 TLS 和生命周期管理；所审阅源码存在关闭上游证书验证的实现 | 不直接移植 |
| Cronet/其他 Chromium 网络实现 | 本轮未验证具体 Android 分发版本的 ECH、DoH 配置入口与失败行为 | 保留为候选，不能因浏览器支持 ECH 就认定现成 SDK 可直接满足需求 |

博客确实对应 `czy0729/Bangumi` 的 Android Rust 模块。[5][6] 固定版本源码中，`tls_skip()` 与 `build_client_ctx()` 使用 `SslVerifyMode::NONE`；Java 端对代理目标域名允许宽松的信任/主机名验证，Rust 端也没有据 ECH 返回状态作成功判定。应用到本地代理的证书链，不能替代代理到官方服务器的身份验证。对会携带账号令牌的 NextVol 同步请求，这些实现不能照搬。该模块 README 还限定当前构建脚本为 macOS、arm64-v8a，不能等同于现有项目的 Android API 24+ 兼容性验证。

建议下一轮的实施边界：

1. 先为官方 `api.bgm.tv` 验证独立的 ECH HTTPS 传输，搜索的官方通道与同步共用它，`.vip` 继续只走独立的公开搜索客户端。不要扩展为全局 DNS、VPN 或通用书源网络改造。
2. 保留目标域名、证书链/主机名校验、令牌所属主机限制、禁止同步重定向和会话取消语义。不得为重试而把账号请求转发到镜像。
3. DoH 首次连接不依赖同一个尚未初始化的解析器；解析及 ECHConfig 缓存遵守 TTL，并验证轮换、过期刷新、多地址选择与失败边界。ECH 不可用时不能假定普通 TLS 一定可用。
4. 维持现有请求预算。只对明确安全的查询作有限重试；同步 POST/PATCH 在结果未知时继续依赖现有远端状态核对，不能因为切换传输而盲目重放。
5. 将外部浏览器的令牌获取流程单独评估。应用内传输成功不能作为网页登录成功的验收依据；本轮不新增 WebView、登录页面或账号流程。
6. 对网页入口优先使用官方 HTTPS 地址。若后续需要将 `bangumi.tv` 链接转换为官方 `bgm.tv`，须按具体路径和登录/Cookie 语义另行确认，不能把它包装成已实现 `bangumi.tv` 原域名直连。

## 复验与尚未覆盖的部分

关键复验方式是同一个目标地址分别关闭/开启 ECH，随后再验证自动 DoH + ECH。下面是完整流程的命令模板；需要具备 ECH/HTTPSRR 的 curl，以及当时可用、经过核实的 DoH 服务与引导地址，不能直接使用旧版系统 curl：

```sh
curl --noproxy '*' --cacert /path/to/trusted-ca-bundle.pem \
  --resolve '<DoH域名>:443:<已核实的引导IP>' \
  --doh-url 'https://<DoH域名>/<返回ECH记录的DoH路径>' \
  --ech hard --connect-timeout 8 --max-time 20 -v \
  'https://api.bgm.tv/v0/subjects/19441'
```

验收要同时检查 ECH succeeded、原目标主机名及证书验证、HTTP 状态和 JSON 内容，不能只看 TCP 连通或返回 200。`/v0/me` 无令牌时应为 401；不要为网络探测读取真实账号或调用收藏写入接口。

本轮未运行应用构建、全量测试或 Android instrumentation。未验证真实账号认证/同步写入、外部浏览器完整登录、Android JNI 集成、系统证书策略与原生库兼容性、ECH 密钥轮换、长时间运行、其他运营商/Wi-Fi、代理并存及性能/包体成本。实验脚本、下载工具和原始记录保留在独立 worktree 的忽略目录中；仓库只保留本说明作为后续网络选型依据。

## 参考资料

1. [RFC 9849：TLS Encrypted Client Hello](https://www.rfc-editor.org/rfc/rfc9849.html)。
2. curl 官方文档：[CURLOPT_ECH](https://curl.se/libcurl/c/CURLOPT_ECH.html)、[CURLOPT_DOH_URL](https://curl.se/libcurl/c/CURLOPT_DOH_URL.html)；实验工具：[static-curl 8.22.0](https://github.com/stunnel/static-curl/releases/tag/8.22.0)。
3. Total-ECH，审阅版本 `31806ff2c8b3d4b8a32c91b629081878cc7a5685`：[README](https://github.com/RememberOurPromise/Total-ECH/blob/31806ff2c8b3d4b8a32c91b629081878cc7a5685/README.md)、[Worker](https://github.com/RememberOurPromise/Total-ECH/blob/31806ff2c8b3d4b8a32c91b629081878cc7a5685/_worker.js)。
4. OkHttp `parent-5.4.0`：[DnsOverHttps](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-dnsoverhttps/src/main/kotlin/okhttp3/dnsoverhttps/DnsOverHttps.kt)、[DnsRecordCodec](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-dnsoverhttps/src/main/kotlin/okhttp3/dnsoverhttps/DnsRecordCodec.kt)、[Dns](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Dns.kt)。
5. 用户提供的[博客](https://blog.csdn.net/gitblog_01415/article/details/150544943)，作为源码线索，结论以固定版本源码和实测为准。
6. `czy0729/Bangumi`，审阅版本 `706ae6f82e5dfa14a507c796c3b90ad50ab380ec`：[Rust README](https://github.com/czy0729/Bangumi/blob/706ae6f82e5dfa14a507c796c3b90ad50ab380ec/android/rust/README.md)、[Rust lib.rs](https://github.com/czy0729/Bangumi/blob/706ae6f82e5dfa14a507c796c3b90ad50ab380ec/android/rust/src/lib.rs)、[OkHttp 接入](https://github.com/czy0729/Bangumi/blob/706ae6f82e5dfa14a507c796c3b90ad50ab380ec/android/app/src/main/java/com/czy0729/bangumi/doh/BangumiOkHttpClientFactory.java)。
