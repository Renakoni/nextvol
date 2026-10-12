# NextVol 发布指南

NextVol 使用 [Renakoni/nextvol Releases](https://github.com/Renakoni/nextvol/releases) 分发版本。`v0.1.0` 的占位发布说明草案见 [0.1.0 发布说明](releases/0.1.0.md)。发布前必须按实际附件确认正文和发布类型。

## 版本与标签

- `app/build.gradle.kts` 中的 `versionName` 使用 `x.y.z`，Git 标签使用 `vx.y.z`。
- `versionCode` 是独立、严格递增的 Android 整数版本码。`0.1.0` 使用 `10300009`，高于此前的 `10300008`；后续发布继续增加，不再由版本名称计算。
- 标签必须指向实际发布的源码提交，APK 必须从该提交构建。标签下的版本名称、版本码与 APK 内的信息必须一致。
- 已发布的标签和附件不覆盖重用；后续改动发布新版本。

## 占位发布

没有 APK 的版本标记为 **Pre-release**，不设为 latest。附件仅包含发布说明与 `SHA256SUMS`；GitHub 另行提供源码压缩包。

GitHub 的 `/releases/latest` API 排除预发布。只有占位预发布时，该接口返回 404，这是尚无正式版本的正常状态；不能将无 APK 的占位版本提升为正式更新。

可通过以下入口读取占位发布的标签、正文和附件，不需要新增自定义更新清单：

```text
https://github.com/Renakoni/nextvol/releases/tag/v0.1.0
https://api.github.com/repos/Renakoni/nextvol/releases/tags/v0.1.0
```

## 正式 APK 发布

NextVol 更新接入的发布约定为：读取本仓库最新正式 Release，从发布标签下的 `app/build.gradle.kts` 获取版本信息，并选择该 Release 唯一的 APK。发布时应满足：

| 附件 | 要求 |
| --- | --- |
| `NextVol-x.y.z-release.apk` | 唯一的、已签名通用 APK，应用 ID 为 `indi.renakoni.nextvol` |
| `NextVol-x.y.z-release-notes.md` | 与 Release 正文一致的发布说明 |
| `SHA256SUMS` | 上述两个附件的 SHA-256 值和精确文件名 |

1. 在对应 issue 的独立分支和 PR 中更新版本与发布说明，完成相关验证和代码审查；正式 APK 应从已合入主分支的提交构建。
2. 从该提交执行 `:app:assembleRelease`，完成发布签名，并使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查签名、`aapt2 dump badging` 核对包名和版本。
3. 首次正式发布时确定并备份专用签名密钥。后续升级沿用同一签名；私钥和密码保存在仓库外，不作为发布附件。
4. 准备以上附件及哈希，先创建草稿并上传全部文件。不要把 Debug、Snapshot、未签名 APK 或多个 ABI APK 放入正式 Release。
5. 检查附件后，将 Release 发布为非草稿、非预发布，并设为 latest。发布后下载附件，核对哈希和签名。
6. 验证正式更新入口、版本比较、下载和安装；存在旧正式版时，验证同包名、同签名、递增版本码的覆盖升级。

正式更新 API：

```text
https://api.github.com/repos/Renakoni/nextvol/releases/latest
```

正式版包名、签名和版本码共同决定 Android 能否覆盖安装。Debug/Snapshot 使用不同应用 ID，上游 LightNovelReader 也是另一应用，不能作为正式版的原地升级来源。修改版本名称本身不能解决签名不一致。

仓库现有 Release 检查工作流只验证构建，不会自动完成 APK 正式签名、上传或发布。

## 签名配置与验证

NextVol 使用独立的 RSA 4096 位签名，别名为 `nextvol-release`。密钥放在仓库外，密码和别名存入密码管理器，并保留可恢复的独立密钥备份。

GitHub Actions 需要四个仓库 Secrets：

```text
ANDROID_RELEASE_KEYSTORE_BASE64
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

`Release signing verification` 工作流可以手动运行；修改工作流的同仓库 PR 也会触发。它解码密钥并比对原始密钥文件的 SHA-256，检查别名、证书有效期和密钥长度，再用两个密码实际签名并验证一个临时 JAR。外部 fork PR 不使用发布密钥。

成功后仅上传公开证书和验证结果，不上传私钥、密码或临时签名文件。密钥在步骤退出时清理。文件指纹固定原始密钥备份；如需重新封装密钥库，应先核对证书身份，再审查更新工作流中的指纹。

这项验证确认 GitHub Secrets 可以用于签名，不能代替 APK 的构建、签名验证、安装或覆盖升级验收。
