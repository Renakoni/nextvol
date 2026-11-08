# ONNX Runtime Android arm64 源码构建

这是 #793 的独立原生构建入口，供 #791 的 Sherpa 后端链接。当前没有改变 App 的 Gradle 依赖、最低 Android 版本或听书行为。

固定基线为 ONNX Runtime **1.17.1**，微软提交 `8f5c79cb63f09ef1302e85081093a3fe4da1bc7d`。只构建 CPU、`arm64-v8a`、Android API 26，保留普通及 contrib CPU 算子，不进行模型驱动的算子裁剪。GPU、NNAPI、XNNPACK、训练和语言绑定关闭。

## 目录与来源

- `vendor/`：实际参与构建的运行时及 14 个固定依赖的源码快照、许可和版权声明。
- `sources.json`：上游归档 URL、版本或提交、归档哈希、许可路径及已应用补丁。
- `sources.sha256`：应用补丁后的逐文件清单。构建前检查文件缺失、改动和未登记文件；Git 对 vendor 禁用换行转换。
- `cmake/android-arm64.cmake`：固定的 CPU 构建配置和本地依赖映射。
- `build_android.py`：先构建主机 `protoc`，再交叉编译运行时，检查 ELF 并导出头文件、库、许可和产物身份。
- `validation/`：链接公开 C/C++ API、检查版本和 CPU 后端的 Android 可执行测试。

快照保留所需源码、CMake 文件、生成脚本、头文件和许可。省略原始模型、测试数据、无关语言实现、示例、文档、预编译文件以及其他执行后端的实现目录；没有修改 CPU 算子注册表。最终保留范围以逐文件清单为准。上游 CMake 文件中的其他平台下载声明仍保留，但本构建将每个所需依赖映射到仓库内源码，并设置 `FETCHCONTENT_FULLY_DISCONNECTED=ON` 和 `FETCHCONTENT_TRY_FIND_PACKAGE_MODE=NEVER`。不使用本机预装的 ONNX Runtime 或 Maven/AAR 运行时。

未参与主机 protoc 或 Android 运行时构建的上游测试、基准、模糊测试、CI 脚本、诊断命令、其他语言绑定和重复 JSON 头文件不纳入快照。按编译命令、头文件依赖和主机项目核对后，进一步省略 805 个文件；保留 Protobuf 配置输入及 Abseil 无条件声明目标所需的辅助源码。源码完整性测试和实际 C/C++ API 冒烟测试由本目录维护。

除 Eigen 外，各依赖归档均核对了上游 `cmake/deps.txt` 的 SHA-1，并另记 SHA-256。Eigen 固定提交的 GitLab ZIP 已重新打包，与上游旧 SHA-1 不同；归档内全部 1,784 个文件均与该提交的 Git blob 身份一致，实际归档哈希及例外记录见 `sources.json`。

## 补丁

快照已经应用补丁，构建时不再次修改源码：

| 对象 | 补丁 | 用途 |
| --- | --- | --- |
| ONNX Runtime | `patches/0001-pinned-build-identity.patch` | 用固定上游提交标识构建，避免误读外层 NextVol Git 身份 |
| ONNX Runtime | `patches/0002-android-no-runpath.patch` | Android 系统依赖无需 RUNPATH；避免 Windows 交叉编译把 shell 单引号写进 ELF，导致加载器告警 |
| ONNX | 上游 `cmake/patches/onnx/onnx.patch` | 依照 ORT 1.17.1 配方调整告警设置 |
| Protobuf | 上游 `cmake/patches/protobuf/protobuf_cmake.patch` | 依照 ORT 1.17.1 配方调整告警设置 |
| FlatBuffers | 上游 `cmake/patches/flatbuffers/flatbuffers.patch` | 保留 ORT 1.17.1 配方的 CMake 告警调整；未使用的 Rust 生成器不纳入快照 |

没有应用 Windows 目标的 Abseil 补丁，本任务的目标平台是 Android。当前不宣称复现 `csukuangfj/onnxruntime-libs` 的历史 Android 二进制；该发行标签没有提供对应包的完整 Android 构建配方。

## 构建

需要 Python 3.10+、CMake 3.26–3.x、Ninja、Android NDK **28.2.13676358**。Windows 主机还需要 Visual Studio 2022 C++ Build Tools 和 Windows SDK，用于从同一份 Protobuf 21.12 源码构建主机 `protoc`。Python 只使用标准库。安装好工具后，构建不需要联网。

在仓库根目录执行，`cmake` 和 `ninja` 应位于 PATH；也可用参数指定它们的完整路径：

```powershell
python native/onnxruntime/verify_sources.py
python native/onnxruntime/build_android.py `
  --ndk "$env:ANDROID_SDK_ROOT/ndk/28.2.13676358" `
  --cmake cmake --ninja ninja --jobs 4
```

默认输出到本目录下被 Git 忽略的 `build/`。可用 `--output` 指定独立输出目录，避免 Windows 路径过长；同一目录不应混用不同主机或工具链。

CI 通过 `--cache-key` 显式启用安装产物复用。该键由 ORT 源码、配方、固定工具链和 runner 镜像版本生成；应用及 Matcha/JNI 改动不影响它。恢复 `install/` 后仍检查源码清单、配方和工具版本，以及所有导出文件的 SHA-256；匹配时跳过主机 protoc 和 ORT 编译。缺失、不匹配或损坏的导出重新构建，首次没有缓存仍需完整编译。直接调用脚本时不传该参数，继续使用原有 CMake 增量构建。

成功后 `build/install/` 包含：

- `lib/libonnxruntime.so`，供 Sherpa 链接的共享运行时。
- `include/`，与运行时来自同一份源码的公开头文件。
- `licenses/`，各组件的许可和上游通知。
- `build-manifest.json`，库 SHA-256、体积、源码清单哈希、主机 protoc 哈希、工具链、ABI、API、动态依赖和 LOAD 对齐。
- `elf.txt`，NDK `llvm-readelf` 的实际检查结果。

Sherpa 可通过 `SHERPA_ONNXRUNTIME_INCLUDE_DIR=<install>/include`、`SHERPA_ONNXRUNTIME_LIB_DIR=<install>/lib` 链接该产物。#791 负责将它接入应用构建与文件合成生命周期；#794 负责真实包安装及正文朗读。

## 非本仓库源码构建的工具链与平台依赖

推理运行时、Protobuf 及全部上述第三方依赖均从仓库源码编译。仍使用已安装工具链提供的编译器、链接器、主机 C/C++ 运行库、Android NDK 的 `libc++_static`、`libc++abi`、`libunwind`、compiler-rt builtins 和 CRT 对象；本任务没有重新构建 LLVM/NDK。Android 的 `libc.so`、`libm.so`、`libdl.so`、`liblog.so` 由操作系统提供，构建时链接 NDK API stubs。没有从 APK 复制库，也没有额外分发 `libc++_shared.so`。NDK 自带的许可位于其 `NOTICE` 文件。

## 验证

源码完整性检查的失败分支：

```powershell
python native/onnxruntime/test_verify_sources.py
```

可用同一 NDK 配置 `validation/CMakeLists.txt`，传入 `-DORT_INSTALL=<install>`、`-DANDROID_ABI=arm64-v8a`、`-DANDROID_PLATFORM=android-26` 和 `-DANDROID_STL=c++_static`。将 `nextvol-ort-smoke` 与该运行时放在显式选择的模拟器目录，设置 `LD_LIBRARY_PATH` 后运行，应报告 `ONNX Runtime 1.17.1, API 17, CPUExecutionProvider`。

实际 Sherpa Matcha 合成结果及产物身份见 [VALIDATION.md](VALIDATION.md)。ELF 的 16 KiB LOAD 对齐检查不等于已通过 16 KiB 页设备验证，也不涵盖未来 APK 的 ZIP 对齐或其他 native 库。MuMu 结果仅用于兼容性验证，不能代表真机性能或生产听书验收。
