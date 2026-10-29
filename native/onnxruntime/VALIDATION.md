# #793 构建与 Matcha 验证

验证日期：2026-10-28。本记录对应下面的确切产物；构建目录、APK、日志、WAV 和原始模型均未提交。

## 源码及构建

Windows 主机，CMake 3.29.5-msvc4、Ninja 1.10.2、NDK 28.2.13676358、MSVC 2022，目标 Android API 26 / arm64-v8a / CPU。Protobuf 21.12 的主机 protoc 和 Android 运行时均从本仓库快照构建；protoc 报告 `libprotoc 3.21.12`。构建脚本没有下载入口，空目录配置在不可达 HTTP(S) 代理下成功。

| 身份 | 结果 |
| --- | --- |
| ONNX Runtime | 1.17.1，微软提交 `8f5c79cb63f09ef1302e85081093a3fe4da1bc7d` |
| 源码清单 SHA-256 | `fb49b588518464efe2d2993c086605cdd3aa540f3c39a387f695647e488eb1af` |
| 来源元数据 SHA-256 | `8f9a04fc051e7bc39f53a738eb46b2dc090d08db9b5b6ba4dc9655f72654eae2` |
| 构建配置 SHA-256 | `82c37973a60fdaa794009acc4c0a228d04908ddba6d7b956d204925c0c039312` |
| libonnxruntime.so 字节数 | 13,983,968 |
| libonnxruntime.so SHA-256 | `88e98f484f266d10253be1375384a97196b66812a6ddd180290ee1901d846692` |
| 主机 protoc SHA-256 | `6c6c7a51e0a199a6a0683abed29193516a6884b8dbf9fb0e765e6b503dea35b4` |
| ELF | AArch64，共享库；导出 `OrtGetApiBase`；三个 LOAD 段均为 `0x4000`，文件偏移与虚拟地址满足 16 KiB 同余 |
| 动态依赖 | `libdl.so`、`liblog.so`、`libm.so`、`libc.so`，均为 Android 系统库；无 RPATH/RUNPATH |

API 冒烟测试通过实际 Android 可执行文件运行，报告 `ONNX Runtime 1.17.1, API 17, CPUExecutionProvider`，同时核对了内嵌的固定上游提交。移除 Android 无用 RUNPATH 后，复验没有加载器路径告警。

源码完整性检查通过；失败分支测试覆盖改动、缺失、未登记文件和越界清单路径。快照共有 4,866 个文件，逐文件与上游来源比对后，仅有两份本地构建补丁、三份 ORT 上游依赖补丁及补充的 Boost 许可产生的差异。NDK 提供的静态库和 CRT 对象没有自行重建，其名称、NDK 内相对路径和哈希记录在每次构建导出的 `build-manifest.json`；完整边界见 [README.md](README.md)。

## Sherpa Matcha 实际合成

仅在显式指定的 MuMu `127.0.0.1:16448` 上验证：Android API 32、4 KiB 页、x86_64 主系统，通过 ARM 兼容机制运行 arm64 代码。验证 App 没有联网权限。

Sherpa JNI 复用前期从公开提交 `6b1fbdedd2d90dba2714b7aff825412d89e45eba`（1.12.10）构建的产物，SHA-256 为 `33dd8cc0fb78172d7a28fd55f54df6e307249c6ba19492b24304a24b6dacb830`。本次将其 ONNX Runtime 换成上述仓库源码产物；不是 APK 提取的 Sherpa 或 ONNX Runtime。验证 App 分别计算两个安装库的 SHA-256，并核对进程映射确实指向该 App 的两个库。Sherpa 进入项目源码及生产文件合成契约仍由 #791 交付。

复用既有 Matcha 中文样例，CPU、2 个线程、speaker 0、speed 1.0；文本为“这是一次离线语音验证。我们正在使用导入的语音包，在应用内部生成声音。”。

| 输入 | SHA-256 |
| --- | --- |
| model-steps-3.onnx | `afcf35609e5698e8a06bd30482ac1e47dfbbb9003d10a2eb837541b42572ff02` |
| hifigan_v2.onnx | `a41d404cce7924493540238da5b30a4bc14b6ddaf1a37f3c79fa4f59548c19f0` |

初始化、生成、保存、释放均成功：单声道、22,050 Hz、16-bit PCM、136,134 帧，约 6.173878 秒，WAV 272,312 字节，独立读取 WAV 头及样本确认有非零音频。RMS 0.058503，peak 0.410464；WAV SHA-256 为 `30b8678d987c0af0e1e38bac022101af35960acdaef76d3762e6680520e79486`。本次初始化 8799 ms、生成 4732 ms、释放 277 ms，仅为这次实验记录。

这证明指定源码运行时能与既有 Sherpa/Matcha 输入完成实际合成，不证明与历史二进制逐字节或音质等价。未验证真机性能、16 KiB 页设备、未来 APK ZIP 对齐、生产包导入、NextVol 章节会话、取消、并发及长时稳定性；这些不在本构建 PR 的交付范围。未运行仓库全量本地测试。
