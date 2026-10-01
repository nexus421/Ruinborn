import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop launcher (LWJGL3) for playing and testing on Linux.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    compilerOptions { jvmTarget = JvmTarget.JVM_25 }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.lwjgl3)
    implementation(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    implementation(libs.ktor.client.cio)
    // At least LWJGL 3.4.3, avoids warnings from Java 25 on (as in gdx-liftoff).
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.jemalloc)
    implementation(libs.lwjgl.openal)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.stb)
}

sourceSets.main { resources.srcDir(rootProject.file("assets")) }

application {
    mainClass = "bayern.kickner.ruinborn.lwjgl3.Lwjgl3LauncherKt"
    applicationName = "ruinborn"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("assets")
    // Passes launch options, e.g. -Pruinborn.args="--server http://localhost:8080 --profile a"
    providers.gradleProperty("ruinborn.args").orNull?.let { args(it.split(" ").filter(String::isNotBlank)) }
}
