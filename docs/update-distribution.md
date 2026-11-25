# NextVol 正式更新分发

设置中的分发平台为 **Nextvol api**（默认）和 **GitHub**。两者仅提供
`Renakoni/nextvol` 的正式应用版本，不支持测试渠道、预发布和 CI 构建。
`offline-runtime-*` 等组件 Release 不参与应用更新。

正式 Release 和 Snapshot APK 仅支持 `arm64-v8a`，无法在其他架构或 32 位 Android 系统上安装。
继续发布单个 APK，文件名与更新协议不变。
Debug、Benchmark 和 ReaderBenchmark 保留其他架构，用于开发及模拟器验证。
厂商语音和 GPT-SoVITS 运行库由听书设置按需下载。客户端从 APK 指定的 GitHub 地址或官网镜像取包，解包成功后才显示已安装。
发布工作流单独保存原生符号表，供崩溃定位，不放入 APK。

## 网站接口

`GET https://download.nextvolcom.com/updates/stable.json`

这是托管在 Cloudflare R2 上的公开 JSON 清单，无需运行后端服务器，也不需要客户端凭据。
清单使用 `Cache-Control: no-store`；Cloudflare 的 `/updates/` 缓存规则设为绕过缓存。
不存在清单时返回 404，应用显示暂无正式版本；其他请求或解析失败显示检查失败。

```json
{
  "schemaVersion": 1,
  "tag": "v0.1.1",
  "versionCode": 10300010,
  "versionName": "0.1.1",
  "packageName": "indi.renakoni.nextvol",
  "releaseNotes": "发布说明（Markdown）",
  "downloadUrl": "https://download.nextvolcom.com/releases/v0.1.1/NextVol-0.1.1-release.apk",
  "sha256": "APK 的 64 位小写 SHA-256 十六进制值"
}
```

APK 与 `SHA256SUMS` 放在 `/releases/vX.Y.Z/`，使用带版本的不可变地址和长期缓存。
两个平台均要求 SHA-256 并校验下载哈希，再核对 APK 包名、
版本号和当前安装应用的签名，校验通过后才提供安装入口。

## 发布与同步

正式版 Release Notes 只写本版更新内容与变更记录，省略「下载与安装」章节。

1. 普通构建从 `native/sovits/runtime.json` 读取已公开的 GPT-SoVITS 地址。`Prepare signed release` 使用 `-PpublishSpeechRuntime=true` 打包本次运行库，并将对应地址和大小写入正式 APK。
2. 从签名 APK 读取 `assets/speech-runtimes/{vendor,sovits,sovits-data}.json`。厂商 v5 按 `native/offline/README.md` 制作，GPT 桥接 ZIP 取自同次工作流的 `NextVol-X.Y.Z-speech-runtime` 附件。
   按 APK 中的下载地址准备组件草稿，核对附件摘要。已有相同摘要的组件可以复用。发布应用前先公开组件，组件使用预发布且不设为 Latest。发布新版组件后将 APK 中的 `sovits.json` 更新到 `native/sovits/runtime.json`，供后续开发构建使用。
   共享模型继续使用原来的权重目录，发音数据和声码器图由 `sovits-data.json` 独立固定。更新这份数据时，用 `:app:packageSovitsData` 生成附件，公开后同步其地址、大小和摘要。
3. 若签名 APK 包含 `assets/reader-fonts.json`，先公开该目录指定的字体组件，标记为预发布并保留应用为 Latest。上传各款原始字体和对应的许可，核对字体 SHA-256；后续应用版本可复用同一组件。
4. 审阅并发布正式 `vX.Y.Z` Release，附件必须包含唯一的
   `NextVol-X.Y.Z-release.apk` 和 `SHA256SUMS`。
5. `Sync formal app release to Nextvol api` 验证正式 APK，再从其中的目录读取运行库和字体的地址与摘要。
   组件镜像位于 `https://download.nextvolcom.com/releases/<组件标签>/<附件文件名>`。
   APK、校验文件、运行库和字体全部上传后才切换正式清单。重试时复用摘要和大小一致的已有对象。

只有正式应用发布会触发同步。上传失败时保留现有清单；较旧的版本不会覆盖当前版本。
同一 Android 版本号的不同内容或同一文件地址的不同内容会被拒绝。
同步任务串行执行。需要重试或补传既有版本时，在 main 上手动运行同步工作流并填写标签。
工作流合并到 main 后才会启用正式发布触发器。

## 上传凭据

Cloudflare Account API Token：`NextVol GitHub Release Sync`，仅授予
`nextvol-downloads` 存储桶的 **Object Read & Write**，不授予存储桶管理权限。
GitHub 仓库的 Actions Secrets 保存：

- `CLOUDFLARE_R2_ACCESS_KEY_ID`
- `CLOUDFLARE_R2_SECRET_ACCESS_KEY`

凭据不进入应用、网页或仓库文件。GitHub 读取 Release 使用工作流自带的只读 `GITHUB_TOKEN`。
轮换时先创建同等范围的新凭据，更新上述两个 Secret，再撤销旧 Token。
如需在 Actions 外执行补传，使用相同脚本、临时环境变量和 Android build-tools 36.0.0；
不要将凭据写入命令参数或提交到版本库。
