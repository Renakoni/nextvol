# NextVol 正式更新分发

设置中的分发平台为 **Nextvol api**（默认）和 **GitHub**。两者仅提供
`Renakoni/nextvol` 的正式应用版本，不支持测试渠道、预发布和 CI 构建。
`offline-runtime-*` 等组件 Release 不参与应用更新。

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

1. 现有 `Prepare signed release` 工作流构建签名 APK 并创建草稿 Release。
2. 审阅并发布正式 `vX.Y.Z` Release，附件必须包含唯一的
   `NextVol-X.Y.Z-release.apk` 和 `SHA256SUMS`。
3. `Sync formal app release to Nextvol api` 自动验证附件哈希、正式签名证书、
   包名以及标签对应的 Android 版本；附件上传成功后才发布清单。
   重试时复用哈希与大小一致的已有对象，不重复上传。

草稿、预发布和非应用组件不会同步。上传失败不会切换清单；较旧的版本不会覆盖当前版本。
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
