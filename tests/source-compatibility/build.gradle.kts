plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    sourceSets.test {
        kotlin.srcDir("reference/kotlin")
    }
}

dependencies {
    testImplementation(project(":source-import"))
    testImplementation(project(":source-rules"))
    testImplementation(project(":source-network"))
    testImplementation(libs.junit)
    // Keep oracle dependencies at the reference revision's versions, not the app's.
    testImplementation(libs.jsoup)
    testImplementation("com.jayway.jsonpath:json-path:2.10.0")
    testImplementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.mozilla:rhino:1.8.1")
}

tasks.test {
    useJUnit()
    maxHeapSize = "512m"
    systemProperty("compatibility.reportDir", layout.buildDirectory.dir("reports/source-compatibility").get().asFile.absolutePath)
    systemProperty("compatibility.projectDir", projectDir.absolutePath)
    val productTestRoots = rootProject.subprojects.flatMap { module ->
        listOf(module.file("src/test/kotlin"), module.file("src/androidTest/kotlin"))
    }
    systemProperty("compatibility.testRoots", productTestRoots.joinToString(File.pathSeparator))
    inputs.files(productTestRoots.map { root -> fileTree(root) { include("**/*.kt") } })
        .withPropertyName("productTests").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("reference"))
        .withPropertyName("referenceSnapshot").withPathSensitivity(PathSensitivity.RELATIVE)
}
