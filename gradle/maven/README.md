# Android build dependencies

This Maven repository contains build dependencies used by NextVol. Reorderable, BackHandler, Groovy, Kotlin and HttpClient use the [Apache License 2.0](../../LICENSE).

- Gradle settings load the Foojay toolchain resolver 1.0.0 from this repository. [Plugin Portal](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention/1.0.0) · [Apache License 2.0](https://github.com/gradle/foojay-toolchains/blob/main/LICENSE)
- Reorderable 3.1.0 — [Maven Central](https://repo.maven.apache.org/maven2/sh/calvin/reorderable/) · [source](https://github.com/Calvin-LL/Reorderable)
- Compose Multiplatform BackHandler 1.9.1 — [Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-backhandler/1.9.1/) · [source](https://github.com/JetBrains/compose-multiplatform-core)
- Release builds use Compose Group Mapping 2.4.10 with Kotlin Stdlib 2.2.21, JetBrains Annotations 13.0 and ASM Core/Tree 9.9.1. [Compose Group Mapping](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-group-mapping/2.4.10/) · [ASM license](org/ow2/asm/LICENSE.txt)

Android Lint also resolves these versions from this directory.

- Groovy 3.0.22 — [Maven Central](https://repo.maven.apache.org/maven2/org/codehaus/groovy/groovy/3.0.22/)
- Kotlin Reflect and Stdlib 2.2.10 — [Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/)
- HttpClient 4.5.6 and its parent POMs — [Maven Central](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/4.5.6/)
- ASM 9.9 (core, commons, tree and analysis) — [BSD 3-Clause license](org/ow2/asm/LICENSE.txt) · [Maven Central](https://repo.maven.apache.org/maven2/org/ow2/asm/)
