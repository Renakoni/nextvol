# Android build dependencies

This Maven repository contains build dependencies used by NextVol. Reorderable, BackHandler, Groovy, Kotlin and HttpClient use the [Apache License 2.0](../../LICENSE).

- Reorderable 3.1.0 — [Maven Central](https://repo.maven.apache.org/maven2/sh/calvin/reorderable/) · [source](https://github.com/Calvin-LL/Reorderable)
- Compose Multiplatform BackHandler 1.9.1 — [Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-backhandler/1.9.1/) · [source](https://github.com/JetBrains/compose-multiplatform-core)
- JsonPath uses SLF4J API 2.0.17. Its JAR includes the MIT license and the local repository supplies both parent POMs. [Maven Central](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.17/)
- Release builds use Compose Group Mapping 2.4.10 with Kotlin Stdlib 2.2.21, JetBrains Annotations 13.0 and ASM Core/Tree 9.9.1. [Compose Group Mapping](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-group-mapping/2.4.10/) · [ASM license](org/ow2/asm/LICENSE.txt)

Android Lint also resolves these versions from this directory.

- Groovy 3.0.22 — [Maven Central](https://repo.maven.apache.org/maven2/org/codehaus/groovy/groovy/3.0.22/)
- Kotlin Reflect and Stdlib 2.2.10 — [Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/)
- HttpClient 4.5.6 and its parent POMs — [Maven Central](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/4.5.6/)
- ASM 9.9 (core, commons, tree and analysis) — [BSD 3-Clause license](org/ow2/asm/LICENSE.txt) · [Maven Central](https://repo.maven.apache.org/maven2/org/ow2/asm/)
