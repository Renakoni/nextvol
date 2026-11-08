# TTS 配置投影与复用边界

本文记录 #946 提取后的职责与 `8a07ef753b3a206975da161b9580b62e4fc2e021`
基线的既有行为。设置界面继续集中编辑 `SpeechSettings`，序列化字段、默认值、
`tts.schemes` 存储和包导入导出格式不变。

## 职责选择

配置规则集中在 [SpeechConfiguration.kt](../app/src/main/kotlin/indi/renakoni/nextvol/tts/SpeechConfiguration.kt)。
它只有纯转换函数，不读写数据库、不解析实际声音目录、不执行脚本，也不打开引擎。
保留 `SpeechSettings` 作为持久化、编辑和调用边界，避免为同一份数据增加一组仅包装它的新类型。

| 负责人 | 负责的内容 |
| --- | --- |
| `SpeechConfiguration` | 方案内容与共享运行设置的拆出、有效配置装配、会话／单声音投影、文本身份编码，以及这些投影使用的路由存在判定 |
| `SpeechSettingsRepository` / `SpeechSchemes` | 读写、默认方案、复制、重命名、删除和书籍绑定；在原来的时点调用配置转换 |
| `ReadAloudConfiguration` / `SpeechVoiceCatalog` | 跟随书籍选择配置，读取声音／人物配对的实际版本；目录负责外部引用解析、可用性和输入上限 |
| `ReadAloudSession` / `SpeechClipProducer` | 设置何时生效、是否重启、是否重置上下文、播放／暂停、提供者打开时机 |
| `SpeechPlan` / `SpeechPlanPreparer` | 章节计划、书签、裁剪与预处理会话；保留章节、上下文、长度和 locale 条件 |
| `CachedSpeechSynthesizer` | 按收到的完整配置判等；容量、过期、淘汰、互斥后端及资源释放 |

这里不继续拆分路由算法、引擎编辑编排或底层合成器。配置字段的投影规则已有共同负责人，
这些消费者的生命周期和算法条件仍属于各自的业务。

## 保存与装配顺序

1. `SpeechSchemes.update` 取得叠加共享 runtime 的有效设置，调用修改函数，再执行既有 `validated()`。
   `schemeContent()` 保存方案部分；`runtimeSettings()` 取出共享部分。没有增加验证或修改验证时点。
2. `schemeContent()` 清除当前书籍、方案显示身份、已解析版本和临时人物映射，并把共享字段还原到原有默认值。
   除下表列出的字段外，其余字段保留。
3. `SpeechSchemes.settings(id)` 选择方案后调用 `effectiveSettings(runtime)`，叠加方案 ID、名称和共享字段。
   `forBook(bookId)` 先按 `bindings[bookId] ?: defaultId` 选择方案，再调用同一入口设置书籍身份。
   无书籍的 `settings(id)` 不另行改写输入的 `bookId`；持久化读取时该 transient 字段原本就是 `null`。
4. 复制方案使用 `settings(copyFrom).schemeContent()`，复制内容但仍共享 runtime。
   空白方案仍使用 `SpeechSettings()`，保留创建时的 locale 默认值。
5. `updateAll` 对各方案的**存储内容**调用修改函数，再调用 `schemeContent()`；它不修改共享 runtime，
   也不补调用 `validated()`。共享声音改名／删除后的跨方案引用修复仍走这里。

共享 runtime 只有六项：`engineCacheCapacity`、`maxCacheVoiceNum`、`diagnosticLogging`，
以及 `text.recordText`、`text.recordVoice`、`text.filterLanguage`。它们在方案内容中的占位值分别为
`10`、`50`、`false`、`false`、`false`、`false`，读取时被共享值覆盖。

## 字段用途基线

“文本身份”列指直接对输入调用 `textProcessingKey` 时的字段参与情况。它不自动执行其他投影。
“单声音”列指 `forVoice` 的最终输出：先替换声音并清除多声音路由，再执行 `synthesisSettings`。
该输出仍是完整 `SpeechSettings`，引擎缓存会比较其中所有字段。

| 字段 | 方案内容／有效装配 | 会话 `synthesisSettings` | 文本身份 | 单声音 `forVoice` |
| --- | --- | --- | --- | --- |
| `engine`, `voice` | 保存 | 有路由时保留；无路由且选 HTTP／离线时清空 | 不直接参与 | 从引用替换；HTTP／离线时清空 |
| `httpSource`, `offlineVoice` | 保存，包含离线引用版本 | 保留 | 不直接参与；在候选引用中时随候选编码 | 从引用替换，包含离线版本 |
| `httpRevision` | 清空；实际版本稍后由目录提供 | 保留 | 不直接参与 | 保留输入值；HTTP 目录随后覆盖实际版本 |
| `rate`, `pitch`, `volume`, `audio` | 保存 | 保留 | 不参与 | 保留 |
| `replacements` | 保存原顺序 | 保留 | JSON 编码 | 保留 |
| `text` 的其他字段，见下文 | 保存 | 保留 | 整个 `text` 的 JSON 编码 | 保留 |
| `text.recordText`, `text.recordVoice` | 方案内为 `false`，从 runtime 叠加 | 保留 | 随 `text` 编码 | 保留 |
| `text.filterLanguage` | 方案内为 `false`，从 runtime 叠加 | 置 `false` | 直接输入中的值随 `text` 编码 | 置 `false` |
| `narrationVoices`, `dialogueVoices` | 保存 | 保留 | JSON 编码，含引用顺序和版本 | 清空 |
| `speakerVoices`, `speakerBookId` | 清空 | 保留 | 映射 JSON 与书籍字符串 | 清空 |
| `voiceRevision` | 清空；目录解析后叠加 | 保留 | 原字符串 | 保留 |
| `voiceRoutingConfigured` | 保存 | 保留 | 布尔值字符串 | 置 `false` |
| `allowMultipleVoices` | 保存，控制编辑选择方式 | 置 `false`，不裁剪已有候选 | 不参与 | 置 `false` |
| `roleRules` | 保存 | 保留 | JSON 编码 | 清空 |
| `roleContextPattern` | 保存 | 保留 | 可空字符串，`null` 连接为字面量 `null` | 保留 |
| `voiceItems`, `autoAssignRole` | 保存 | 保留 | JSON／布尔值字符串 | 清空／置 `false` |
| `engineCacheCapacity` | 方案内为 `10`，从 runtime 叠加 | 保留 | 不参与 | 保留 |
| `maxCacheVoiceNum` | 方案内为 `50`，从 runtime 叠加 | 保留 | 整数值 | 保留 |
| `diagnosticLogging` | 方案内为 `false`，从 runtime 叠加 | 置 `false` | 不参与 | 置 `false` |
| `backgroundMusic` | 保存 | 重置为 `SpeechSchemeMusic()` | 不参与 | 重置为默认 |
| `schemeId` | 方案内为 `default`；装配时取实际方案 ID | 保留 | 原字符串 | 保留 |
| `schemeName` | 清空；装配时取名称；字段为 transient | 清空 | 不参与 | 清空 |
| `bookId` | 清空；`forBook` 装配；字段为 transient | 保留 | 不直接参与 | 保留 |

`text` 的其他字段是：`dialogue`、`continueDialogue`、`replaceEmoji`、`separateEnglish`、
`filterUnspoken`、`languageDetector`、`paragraphPauseMs`、`nameDiscoveryAlgorithm`、
`personNameRegex`、`onlyNewPerson`、`maxAutoCheckedPerson`、`maleNameRegex`、`femaleNameRegex`、
`maxSentenceLength`。它们全部按原模型参与序列化；带 `EncodeDefault` 的字段仍照常输出。

`hasVoiceRouting` 的原判定为：显式配置标记、旁白／对话候选、人物映射、任一启用的人物规则，
或自动分配人物。`voiceItems` 非空本身不使该判定成立；这也保持原样。

## 从有效设置到合成输入

正式朗读先通过 `ReadAloudConfiguration` 读取 `forBook` 的有效设置，再由 `SpeechVoiceCatalog.revised`
叠加声音目录／人物配对版本。会话在既有位置调用 `validated()`，随后执行 `synthesisSettings()`。
有声音路由时，具体提供者延迟到片段选出声音后打开；无路由时直接用该配置打开。

试听使用请求中的候选设置，先执行原有 `validated()`，再执行
`forVoice(selected.voiceReference())`。这样仍沿用文本处理与合成参数，但不要求旁白／对话候选齐全。
试听不改变书籍的持久设置和进度；暂停与返回仍由会话管理。

计划内的声音解析遵循以下顺序：

- 离线声音：目录先把引用换成当前安装的 `voice.reference`，再调用 `forVoice(current)`。
  `offlineVoice.revision` 因此进入合成配置。目录另外计算资源引用、配置及启用状态的 `SpeechResolvedVoice.revision`。
- HTTP 声音：目录先读取定义、凭据、origins 与可用性并计算 revision；调用 `forVoice(current)` 后，
  通过 `copy(httpRevision = revision)` 注入实际版本。不要把版本清除和注入移到其他时点。
- 系统声音：使用 `forVoice(reference)`，系统最大输入长度和显示标签仍由目录提供。
- 无目录结果的路由回退仍使用 `forVoice(reference)`；缺失声音的错误处理不变。

`SpeechVoiceCatalog.revised` 汇总已解析声音的 `reference.key + revision + error`，按现有顺序以 NUL 连接后取摘要，
再拼接 `":" + pairs.revision(bookId, schemeId)` 得到 `voiceRevision`。这些实际版本的来源仍由目录负责。
包别名解析、声音引用修复和来源可用性不属于配置字段裁剪。

## 六种判定不能互换

| 场景 | 实际比较／规则 | 非配置条件的负责人 |
| --- | --- | --- |
| 保存方案内容 | `schemeContent` 与 `runtimeSettings` 分离；仓库比较序列化结果决定是否写入 | 仓库和方案业务 |
| 会话应用新设置 | `previous.synthesisSettings() != value.synthesisSettings()` | 会话还检查试听、停止／完成状态，保留暂停及当前位置 |
| 重置文本上下文 | 仅在前一项要求重建后，比较**原有效设置**的 `textProcessingKey(Int.MAX_VALUE)` | 会话清理书签上下文和当前计划 |
| 文本处理／恢复身份 | `textProcessingKey(maxInputLength, locale)` | 书签另查书籍、章节、文本指纹、完成状态和偏移；预处理会话另加书籍与章节 ID |
| 完整计划复用 | 完整 `synthesis` 相等 | `SpeechPlan.matches` 另查未裁剪、章节、输入上下文、最大输入长度和当前 locale |
| 片段提供者／引擎复用 | `SpeechClipProducer` 比较完整 `activeSettings`；缓存以收到的完整配置为键 | 缓存另管容量、五分钟过期和互斥模型 |

例如，修改语速会使完整合成配置不同，需要重建音频／计划，但文本 key 可以相同。
显示名称、日志开关、背景音乐和多选开关被会话投影排除，不因此重建。
`text.filterLanguage` 单独变化也被会话投影排除；若它与会引起重建的字段一起变化，
会话随后对**未经会话投影的有效设置**比较文本 key，仍可能清空上下文。不要在提取时换掉这个入参。

单声音投影保留 `voiceRevision`、`roleContextPattern`、方案／书籍身份、文本设置和其他未明确裁掉的字段。
它们即使不是某个底层引擎直接读取的参数，也仍参与现有完整值判等。本次不缩小失效范围，不引入万能缓存键。

## 文本 key 的持久兼容

`textProcessingKey` 使用默认 `Json`，保持 `speech-text-v5` 前缀和 SHA-256 UTF-8 摘要。
NUL 分隔的前六段依次是：前缀、方案 ID、替换规则 JSON、文本设置 JSON、最大输入长度、locale language tag。
随后一个 NUL 后的尾部按原顺序直接连接：

```text
narrationVoices JSON
+ dialogueVoices JSON
+ speakerVoices JSON
+ speakerBookId
+ voiceRevision
+ voiceRoutingConfigured
+ roleRules JSON
+ roleContextPattern
+ voiceItems JSON
+ autoAssignRole
+ maxCacheVoiceNum
```

尾部各项之间原本没有新增分隔符。不能调整 JSON 配置、字段／集合顺序、可空字符串处理、分隔符或版本前缀。
这些字节决定已有书签的片段索引和上下文能否继续使用。

## 后续修改的查阅入口

新增字段时，先在 `SpeechSettings` 或其现有嵌套模型定义其存储含义，再到 `SpeechConfiguration.kt`
明确它是否属于共享 runtime、方案内容、会话输入、单声音输入或文本身份。
需要实际后端版本的字段仍从目录读取后装配；需要章节／播放状态的判断留在原消费者。
不要为配置提取扩大 `validated()` 的调用范围，或改变导入与静态预览的非执行边界。

现有回归分工：`SpeechSettingsRepositoryTest` 覆盖方案／runtime／绑定与 `updateAll`；
`SpeechPlanTest` 保护固定 v5 文本身份及“文本身份相同但完整计划不能复用”的边界；
`SpeechVoiceSelectionTest`、`SpeechVoiceRoutingTest`、`SpeechDiagnosticsTest` 保护选择、路由与日志投影；
`SpeechSynthesizerCacheTest` 覆盖单声音切回、版本变化、淘汰及冲突模型；
`ReadAloudSessionTest` 继续覆盖正式朗读、试听、设置更新、暂停和恢复。
包导入、预处理 locale 与背景音乐分别由原有 `SpeechConfigurationImportTest`、`SpeechTextPipelineTest`
和 `ReadAloudConfigurationTest` 覆盖。
