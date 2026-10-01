import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral client layer (Kotlin Multiplatform): HTTP API, WebSocket, game state.
// The HTTP engine is passed in by the app (CIO on desktop, OkHttp on Android).
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
            dependencies {
                api(project(":shared"))
                api(libs.coroutines.core)
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
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
