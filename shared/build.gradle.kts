import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared game rules, DTOs and balance data classes (Kotlin Multiplatform).
// Everything lives in commonMain. For now there is only the JVM target (server, desktop, Android).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/balance"))
            dependencies {
                api(libs.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

// The default balance file is the single source of all game numbers. It is embedded as a constant
// so tests, server (writes it to balancePath on first start) and simulation use the same values
// without platform-specific resource loading.
val generateDefaultBalance = tasks.register("generateDefaultBalance") {
    val input = layout.projectDirectory.file("balance/balance.json")
    val output = layout.buildDirectory.dir("generated/balance")
    inputs.file(input)
    outputs.dir(output)
    doLast {
        val json = input.asFile.readText(Charsets.UTF_8)
        val escaped = buildString {
            json.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> {}
                    else -> append(c)
                }
            }
        }
        val file = output.get().file("bayern/kickner/ruinborn/shared/balance/DefaultBalance.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// GENERATED from shared/balance/balance.json. Do not edit by hand.
            |package bayern.kickner.ruinborn.shared.balance
            |
            |object DefaultBalance {
            |    const val JSON: String = "$escaped"
            |}
            |""".trimMargin()
        )
    }
}

tasks.matching { it.name.startsWith("compileKotlin") || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generateDefaultBalance)
}
