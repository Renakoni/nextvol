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
