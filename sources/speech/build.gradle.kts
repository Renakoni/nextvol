plugins { kotlin("jvm"); `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(project(":source-execution"))
    implementation(project(":source-network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation(project(":source-rhino"))
}

tasks.test { useJUnit() }
