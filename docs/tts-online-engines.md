# MultiTTS 在线引擎

在线包保留原来的引擎代码、`engine.param`、发音人代码、`param` 和
`extendUI`。导入和预览只解析配置，不运行脚本、连接服务或加载 SDK。
实际合成复用现有分句、音频处理、取消和来源授权链。

| 引擎代码 | 协议及参数来源 | Android runtime |
| --- | --- | --- |
| `vivo` | 发音人 JSON 参数中的 appId、appKey、engineType、engineMode、voice；签名 WebSocket，原版 aue=1 | Vivo Opus 解码器 |
| `bdotts` | 发音人逗号参数中的 speaker/product；Baidu LVTV 二进制 WebSocket | Baidu SpeechDecoder |
| `msctts` | 发音人 appId、downfrom、voice；引擎 region；type=1..5 选择在线模式 | MSC |
| `azure` | 引擎 apiKey、region；发音人及其扩展控件生成 SSML | Microsoft Speech SDK 1.45.0 |
| `doubao` | 引擎 cookie；发音人 code；专用 WebSocket AAC | 公共音频解码 |
| `clipchamp` | 引擎 token 换取区域令牌；发音人 SSML；引擎 audioFormat | 公共音频解码 |
| `guagua` | 引擎 wxToken、userId；发音人 param 为 cvRobotId | 公共音频解码 |
| `msspeech` | 发音人 param 的第二项为 Speech Studio 的声音 GUID；引擎 audioFormat | 公共音频解码 |
| `readspk` | 发音人 code、locale；HTML/Base64 表单，语速为独立请求参数 | 公共音频解码 |

Vivo、Baidu 的请求通过 `SourceBroker`，仅音频解码调用原版 native 库。
Azure 和 MSC 的已审查宿主适配使用 SDK 自带传输；进入合成前检查来源生命周期
和配置端点授权，SDK 内部网络不经过书源脚本或 worker。该权限不暴露给脚本。
HTTP/WebSocket 后端继续使用 broker 的授权、响应限额和取消机制。
ReadSpeaker 服务可能重定向到另一音频域名，仍需通过现有来源授权处理。

Vivo 和 Baidu 的 JNI 库随 `native/offline/runtime.json` 固定版本获取，
库清单及 SHA-256 由 runtime manifest 记录。导入配置不依赖设备能否加载这些库。

## 引擎与发音人编辑

引擎列表直接提供新建发音人和编辑入口，新建引擎位于工具栏。编辑器使用与
规则和声音池一致的弹窗。引擎参数始终显示；HTTP 引擎另显示 URL 和请求头。
现有引擎代码、类型只读。新发音人由引擎代码建立独立配置，不从现有发音人复制。
新建默认值遵循 `Speaker(engineCode)`；包导入遵循原版的映射构造器，二者不混用。
原版声音身份由引擎、代码和类型共同确定；同一引擎可以包含多个发音人。
MSC 在线和离线类型共用一份引擎参数，导入另一种类型不会覆盖已经编辑的参数。
不提供引擎导出。

`engine.param.webView.loginUrl` 存在时显示登录入口，`userAgent` 传给已有隔离浏览器。
用户完成登录并确认后，`mapping` 把 `cookies`、`cookies.NAME` 或
`localStorage.KEY` 写回指定引擎参数。登录先更新编辑草稿，保存后才影响合成。
Cookie 按登录完成页读取，保留该页面路径对应的会话信息。
没有登录配置的引擎可以直接编辑原包参数。

声音目录仅由声音库的手动刷新入口请求，打开页面、导入和预览不触发目录联网。
刷新保留已有声音的身份、授权及用户编辑。刷新期间引擎被修改时，不提交过期结果。
Guagua 自动发现的 userId 在会话结束后保存，避免写回配置撤销仍在运行的自身请求。

## 原版依据和兼容修正

对照目标为 MultiTTS 1.8.3(84) arm64 APK，SHA-256：
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`。
REA 对照入口包括 `R4.b`、`Z1.c`、`d4.c/d4.d`、`r4.a`、`F6.b/F6.c`、
`F6.e`、`F6.j/F6.k`、`F6.n`、`F6.r`、`Y6.j.n` 和 `Speaker` 构造器。
native `initCodeMap/code2id` 确认 `msspeech=7`、`readspk=19`，
ReadSpeaker 服务名没有被用来创造另一个包键。

保留以下已确认的兼容修正：

- 豆包请求使用 JSON 序列化，修复原版直接拼接引号、换行时生成无效请求的问题。
- ReadSpeaker 转义 HTML 文本，防止书中文字被当成标签而丢失。
- Speech Studio 原版忽略 audioFormat 且其默认 MP3 已被当前服务拒绝；现在尊重显式参数，
  缺省采用当前服务使用的 24 kHz PCM，并封装 WAV 供公共音频链读取。
