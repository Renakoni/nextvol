import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.dagger.hilt)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
    id("com.mikepenz.aboutlibraries.plugin.android")
}

// The ordinary APK includes the source-built backend; other ABIs retain system/HTTP speech.
val matchaNative = providers.gradleProperty("matchaNative").map(String::toBoolean).getOrElse(true)
val matchaOutput = providers.gradleProperty("matchaBuildDir").map { file(it) }
    .getOrElse(layout.buildDirectory.dir("matcha").get().asFile)
// Voice packages supply models; ordinary APKs embed the separate SDK runtime bundle.
val suppliedOfflineRuntime = providers.gradleProperty("offlineRuntimeDir").map { file(it) }.orNull
val offlineRuntime = suppliedOfflineRuntime ?: layout.buildDirectory.dir("offline-runtime").get().asFile
if (suppliedOfflineRuntime != null) {
    require(offlineRuntime.resolve("jniLibs").isDirectory) { "offlineRuntimeDir must contain jniLibs" }
} else {
    val runtimeSpec = groovy.json.JsonSlurper().parse(rootProject.file("native/offline/runtime.json")) as Map<*, *>
    val runtimeUrl = providers.gradleProperty("offlineRuntimeUrl").getOrElse(runtimeSpec["url"] as String)
    val runtimeSha256 = providers.gradleProperty("offlineRuntimeSha256").getOrElse(runtimeSpec["sha256"] as String)
    val prepareOfflineRuntime by tasks.registering(Exec::class) {
        inputs.property("runtimeUrl", runtimeUrl)
        inputs.property("runtimeSha256", runtimeSha256)
        inputs.file(rootProject.file("native/offline/fetch_runtime.py"))
        outputs.dir(offlineRuntime)
        commandLine(
            providers.gradleProperty("matchaPython").getOrElse("python"),
            rootProject.file("native/offline/fetch_runtime.py"),
            "--url", runtimeUrl,
            "--sha256", runtimeSha256,
            "--output", offlineRuntime,
        )
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareOfflineRuntime) }
}
if (matchaNative) {
    val buildMatchaNative by tasks.registering(Exec::class) {
        inputs.property("host", System.getProperty("os.name"))
        inputs.files(rootProject.fileTree("native/matcha") { exclude("build/**", "__pycache__/**") })
        inputs.files(rootProject.fileTree("native/onnxruntime") { exclude("build/**", "__pycache__/**") })
        inputs.properties(listOf("matchaPython", "matchaNdk", "matchaCmake", "matchaNinja", "matchaOrtCacheKey")
            .associateWith { providers.gradleProperty(it).getOrElse("") })
        outputs.dir(matchaOutput.resolve("jniLibs"))
        outputs.dir(matchaOutput.resolve("assets"))
        outputs.cacheIf { true }
        commandLine(
            providers.gradleProperty("matchaPython").getOrElse("python"),
            rootProject.file("native/matcha/build_android.py"),
            "--ndk", providers.gradleProperty("matchaNdk").getOrElse(
                androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("ndk/28.2.13676358").path),
            "--cmake", providers.gradleProperty("matchaCmake").getOrElse("cmake"),
            "--ninja", providers.gradleProperty("matchaNinja").getOrElse("ninja"),
            "--output", matchaOutput,
            "--ort-cache-key", providers.gradleProperty("matchaOrtCacheKey").getOrElse(""),
        )
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildMatchaNative) }
}

android {
    sourceSets.getByName("main").jniLibs.srcDir(offlineRuntime.resolve("jniLibs"))
    sourceSets.getByName("main").assets.srcDir(offlineRuntime.resolve("assets"))
    if (matchaNative) {
        sourceSets.getByName("main").jniLibs.srcDir(matchaOutput.resolve("jniLibs"))
        sourceSets.getByName("main").assets.srcDir(matchaOutput.resolve("assets"))
    }
    sourceSets.getByName("androidTest").assets.srcDir("../sources/rhino/src/test/resources")
    sourceSets.getByName("androidTest").assets.srcDir("../tests/source-compatibility/browser")
    sourceSets.getByName("androidTest").assets.srcDir("../tests/source-compatibility/fixtures/native-routing/site")
    namespace = "indi.renakoni.nextvol"
    compileSdk = 37

    lint {
        baseline = file("lint-baseline.xml")
        // Full scans must reject stale entries. Vital scans do not run every baseline detector.
        error += "LintBaselineFixed"
    }

    defaultConfig {
        multiDexEnabled = true
        applicationId = "indi.renakoni.nextvol"
        minSdk = 24
        targetSdk = 37
        // 独立发布从 0.1.0 起，versionCode 延续历史值并递增，避免 Android 判定为降级。
        versionCode = 10_300_010
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "BENCHMARK", "false")
        buildConfigField("boolean", "MATCHA_NATIVE", matchaNative.toString())
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    @Suppress("UnstableApiUsage")
    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isJniDebuggable = true
            vcsInfo.include = false
            versionNameSuffix = "-" + defaultConfig.versionCode.toString()
        }

        register("snapshot") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            applicationIdSuffix = ".snapshot"
            isShrinkResources = true
            isMinifyEnabled = true
            vcsInfo.include = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US)
            versionNameSuffix = "_SN (${dateFormat.format(Date())})"
        }

        register("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            vcsInfo.include = false
            buildConfigField("boolean", "BENCHMARK", "true")
        }

        register("readerBenchmark") {
            initWith(getByName("benchmark"))
            applicationIdSuffix = ".readerbenchmark"
            matchingFallbacks += listOf("release")
            // Keep the ordinary directory/legacy-cache refresh decisions under measurement.
            buildConfigField("boolean", "BENCHMARK", "false")
            buildConfigField("String", "READER_BENCHMARK_SHA",
                '"' + providers.gradleProperty("readerBenchmarkSha").getOrElse("UNSPECIFIED") + '"')
        }

        base {
            archivesName = "NextVol-${defaultConfig.versionName}"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Multi-SDK Robolectric resource tests exceed Gradle's default 512 MiB heap.
            it.maxHeapSize = "2g"
            it.jvmArgs(
                // JDK 22 C2 crashes in Node::uncast in test workers; leave app/runtime compilation unchanged.
                "-XX:TieredStopAtLevel=1",
                // Robolectric/Compose exhaust C1's code cache across the full test suite.
                "-XX:ReservedCodeCacheSize=256m",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }
    packaging {
        // Embedded engines such as Sogou open companion libraries by their filesystem paths.
        jniLibs.useLegacyPackaging = true
        if (matchaNative) {
            // Preserve the source-build manifest's hashes in every APK.
            jniLibs.keepDebugSymbols += setOf("**/libnextvol-matcha.so", "**/libonnxruntime.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JAR lookup indexes are not used by Android; both pinned Hutool artifacts contain one.
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("snapshot")) { variant ->
        variant.outputs.forEach { output ->
            val outputImpl = output as com.android.build.api.variant.impl.VariantOutputImpl
            val originalFileName = outputImpl.outputFileName.get()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val newFileName = originalFileName.replace(".apk", " (${dateFormat.format(Date())}).apk")
            outputImpl.outputFileName = newFileName
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// PotatoEPUB ships Java 22 bytecode. Exercise real exports on the JDK already used by CI.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(22)) })
    val scopes = providers.gradleProperty("ciTestScope").orElse("full").get().split(",").toSet()
    val uiTests = mapOf(
        "bookshelf" to listOf("ui.home.bookshelf.*", "ui.home.BookshelfHomeLifecycleTest"),
        "explore" to listOf("ui.home.explore.*"),
        "catalog" to listOf(
            "ui.home.discovery.*", "ui.home.categories.*", "ui.home.explore.*",
            "ui.home.MainNavigationTest", "data.web.rules.DiscoveryRoutingTest",
        ),
        "stats" to listOf("ui.home.reading.stats.*"),
        "bangumi" to listOf("ui.bangumi.*"),
        "about" to listOf("ui.home.settings.SettingsHierarchyTest"),
        "logs" to listOf("ui.home.settings.SettingsHierarchyTest", "data.logging.LoggerRepositoryTest"),
    )
    // Directory search, fonts, reading records and reader controls have consumers
    // outside the reader package. Add scopes by union, without exclusions.
    val readerTests = listOf(
        "ui.book.reader.*", "data.content.*", "data.web.rules.MixedSourceAcceptanceTest",
        "ui.book.detail.*", "ui.dialog.MarkAllChaptersAsReadDialogViewModelTest",
        "ui.home.settings.SettingsHierarchyTest", "ui.tts.SpeechControlsTest",
    )
    val scopeTests = uiTests + mapOf(
        "ui" to uiTests.values.flatten(),
        "reader" to readerTests,
        "speech" to readerTests + listOf("tts.*", "ui.tts.*"),
        "update" to listOf("data.update.*", "ui.home.settings.SettingsHierarchyTest"),
        "export" to listOf(
            "data.export.*", "data.work.EpubExportWorkerAdapterTest", "data.work.EpubExportRegressionTest",
            "data.work.EpubXhtmlWorkerTest", "data.work.SourceExportWorkerTest", "data.work.BookWorkIdentityTest",
            "data.download.BookDownloadTest", "data.book.BookRepositoryOperationsTest",
            "data.web.HostMultiSourceIntegrationTest", "data.web.rules.MixedSourceAcceptanceTest",
            "ui.book.detail.DetailExportTest",
        ),
        "backup" to listOf(
            "data.backup.*", "data.local.SourceIdentityRoomTest", "data.bookmark.ReadingBookmarkTest",
            "data.download.BookDownloadTest", "data.web.HostMultiSourceIntegrationTest",
            "data.localbook.LocalBookRelinkTest", "data.ExternalFilesTest", "ui.ExternalFileViewModelTest",
            "ui.book.reader.bookmark.ReaderBookmarksUiTest",
        ),
        "storage" to listOf(
            "data.download.*", "data.localbook.*", "ui.bookmanager.*", "ui.book.download.*", "ui.localbook.*",
            "data.local.SourceIdentityRoomTest", "data.local.UserReadingDataTransactionTest",
            "data.work.BookWorkIdentityTest", "data.backup.BackupRestoreTransactionTest",
            "ui.book.reader.ReaderReadingRecordsTest", "ui.book.reader.ReaderDirectoryProgressTest",
            "ui.home.settings.SettingsHierarchyTest",
        ),
    )
    require(scopes.all { it == "full" || it in scopeTests }) { "Unknown ciTestScope: $scopes" }
    failOnNoDiscoveredTests.set(true)
    if ("full" !in scopes) {
        filter {
            isFailOnNoMatchingTests = true
            scopes.flatMap { scopeTests.getValue(it) }.distinct().forEach {
                includeTestsMatching("indi.renakoni.nextvol.$it")
            }
        }
    }
}

composeCompiler {
    includeSourceInformation = true
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xwhen-expressions=indy"
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
}

dependencies {
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.45.0") {
        // Embedded synthesis does not use Azure token credentials; azure-core requires API 26.
        exclude(group = "com.azure", module = "azure-core")
    }
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Android lib
    implementation(libs.androidx.core.ktx)
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(libs.androidx.foundation)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent.compose)
    // Compose
    implementation(libs.compose.animation.graphics)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation("io.mockk:mockk-android:${libs.versions.mockk.get()}") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    testImplementation(libs.work.testing)
    // Hilt
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.common)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    // Navigation
    implementation(libs.navigation.ui.ktx)
    implementation(libs.navigation.compose)
    // coil3
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // jsoup
    implementation(libs.jsoup)
    // Markdown
    implementation(libs.markdown)
    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    // Splash API
    // WorkManager
    implementation(libs.work.runtime.ktx)
    // Potato EPUB
    implementation(project(":epub"))
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    // Swipe
    implementation(libs.swipe)
    // Chart
    implementation(libs.vico.compose.m3)
    // Shimmer
    implementation(libs.compose.shimmer)
    // About Libraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    // LNR API
    implementation(project(":api"))
    implementation(libs.dom4j)
    implementation(libs.kotlin.result)
    implementation(libs.kotlin.result.coroutines)
    // http
    implementation(libs.okhttp)
    implementation(libs.conscrypt.android)
    implementation(libs.dnsjava)
    implementation(libs.okhttp3.logging.interceptor)
    implementation(libs.androidx.profileinstaller)
    // RE2J
    implementation(libs.re2j)
    implementation(libs.universal.chardet)
    // Matomo
    implementation(libs.matomo.sdk.android)
    // Reorderable
    implementation(libs.reorderable)
    // TinyPinyin
    implementation(libs.tinypinyin)
    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    // Logger
    implementation(libs.slf4j.android)
    // ZoomImage
    implementation(libs.zoomimage.compose.coil3)
    // Resilient
    implementation(libs.resilient)
}

configurations.implementation {
    exclude(group = "com.intellij", module = "annotations")
}

// Robolectric supplies desktop Conscrypt; Android's JNI binary cannot run in the JVM test worker.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}

tasks.register("printVersion") {
    description = "print the version name of the project"
    doFirst {
        println(android.defaultConfig.versionName)
    }
}

tasks.register("printVersionCode") {
    description = "print the version code of the project"
    doFirst {
        println(android.defaultConfig.versionCode)
    }
}


dependencies {
    implementation(project(":source-execution"))
    implementation(project(":source-speech"))
    implementation(project(":source-rhino"))
    implementation(project(":source-content"))
    testImplementation(testFixtures(project(":source-content")))
    androidTestImplementation(testFixtures(project(":source-content")))
    implementation(project(":source-network"))
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
