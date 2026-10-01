import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// All libGDX client code: screens, dialogs, rendering. JVM 17 because of the Android target.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}
tasks.withType<JavaCompile>().configureEach { options.release = 17 }

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
}

dependencies {
    api(project(":client"))
    api(libs.gdx)
    api(libs.ktx.app)
    api(libs.ktx.scene2d)
    api(libs.ktx.actors)
    api(libs.ktx.style)
    api(libs.ktx.async)
    api(libs.klogger)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

// Assets live in the root directory (gdx-liftoff convention). Tests load them from the classpath.
sourceSets.test { resources.srcDir(rootProject.file("assets")) }

// Generates BuildInfo.kt from gradle.properties (versionCode for the X-Client-Version header, server URL).
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionCode = providers.gradleProperty("ruinborn.versionCode")
    val versionName = providers.gradleProperty("ruinborn.versionName")
    val serverUrl = providers.gradleProperty("ruinborn.serverUrl")
    inputs.property("versionCode", versionCode)
    inputs.property("versionName", versionName)
    inputs.property("serverUrl", serverUrl)
    val output = layout.buildDirectory.dir("generated/buildinfo")
    outputs.dir(output)
    doLast {
        val file = output.get().file("bayern/kickner/ruinborn/client/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// GENERATED from gradle.properties. Do not edit by hand.
            |package bayern.kickner.ruinborn.client
            |
            |object BuildInfo {
            |    const val VERSION_CODE: Int = ${versionCode.get().toInt()}
            |    const val VERSION_NAME: String = "${versionName.get()}"
            |    const val SERVER_URL: String = "${serverUrl.get()}"
            |}
            |""".trimMargin()
        )
    }
}
tasks.matching { it.name == "compileKotlin" || it.name == "sourcesJar" }.configureEach { dependsOn(generateBuildInfo) }
