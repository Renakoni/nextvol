@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "vendoredAndroidLibraries"
                    url = uri("gradle/maven")
                }
            }
            filter {
                listOf("", "-android", "-android-debug").forEach { suffix ->
                    includeModule("sh.calvin.reorderable", "reorderable$suffix")
                    includeModule("org.jetbrains.compose.ui", "ui-backhandler$suffix")
                }
                includeVersion("org.codehaus.groovy", "groovy", "3.0.22")
                includeVersion("org.jetbrains.kotlin", "compose-group-mapping", "2.4.10")
                includeVersion("org.jetbrains.kotlin", "kotlin-reflect", "2.2.10")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.10")
                includeVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
                includeVersion("org.jetbrains", "annotations", "13.0")
                listOf("asm", "asm-tree").forEach { module ->
                    includeVersion("org.ow2.asm", module, "9.9.1")
                }
                listOf("asm", "asm-commons", "asm-tree", "asm-analysis").forEach { module ->
                    includeVersion("org.ow2.asm", module, "9.9")
                }
                includeVersion("org.ow2", "ow2", "1.5.1")
                includeVersion("org.apache.httpcomponents", "httpclient", "4.5.6")
                // HttpClient inherits its dependency versions from these parent POMs.
                includeVersion("org.apache.httpcomponents", "httpcomponents-client", "4.5.6")
                includeVersion("org.apache.httpcomponents", "httpcomponents-parent", "10")
                includeVersion("org.apache", "apache", "18")
            }
        }
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven {
            setUrl("https://artifact.bytedance.com/repository/Volcengine")
            content {
                includeGroupByRegex("com\\.bytedance.*")
                includeGroup("org.chromium.net")
            }
        }
    }
}

rootProject.name = "NextVol"
include(":app")
include(":epub")
include(":api")
include(":benchmark")
include(":source-compatibility")
include(":source-rules")
include(":source-network")
include(":source-execution")
include(":source-import")
include(":source-rhino")
include(":source-content")
include(":source-speech")

// Keep Gradle project names stable while grouping source and test modules.
project(":benchmark").projectDir = file("tests/benchmark")
project(":source-compatibility").projectDir = file("tests/source-compatibility")
project(":source-rules").projectDir = file("sources/rules")
project(":source-network").projectDir = file("sources/network")
project(":source-execution").projectDir = file("sources/execution")
project(":source-import").projectDir = file("sources/import")
project(":source-rhino").projectDir = file("sources/rhino")
project(":source-content").projectDir = file("sources/content")
project(":source-speech").projectDir = file("sources/speech")
