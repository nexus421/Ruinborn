// Android app (concept section 12): launcher, manifest, signing. All game code comes from :core.
// AGP 9 ships its own Kotlin support (no kotlin-android plugin needed).
plugins {
    alias(libs.plugins.android.application)
}

val versionCodeProp = providers.gradleProperty("ruinborn.versionCode").get().toInt()
val versionNameProp = providers.gradleProperty("ruinborn.versionName").get()
// Server URL: dedicated Android property (e.g. http://10.0.2.2:8080 for the emulator), otherwise the shared one.
val serverUrl = (providers.gradleProperty("ruinborn.androidServerUrl").orNull ?: providers.gradleProperty("ruinborn.serverUrl").get())

android {
    namespace = "bayern.kickner.ruinborn"
    // 37 instead of 36 (gdx-liftoff): OkHttp 5.5 from Ktor 3.6 requires at least compileSdk 37. targetSdk follows.
    compileSdk = 37

    defaultConfig {
        applicationId = "bayern.kickner.ruinborn"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeProp
        versionName = versionNameProp
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Signing from ~/.gradle/gradle.properties (concept section 16). Without a key the release APK is unsigned.
    val storeFile = providers.gradleProperty("ruinborn.storeFile").orNull
    if (storeFile != null) {
        signingConfigs {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("ruinborn.storePassword").get()
                keyAlias = providers.gradleProperty("ruinborn.keyAlias").get()
                keyPassword = providers.gradleProperty("ruinborn.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: libGDX, Ktor and kotlinx.serialization would otherwise need extensive keep rules.
            isMinifyEnabled = false
            if (storeFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets.getByName("main") {
        assets.directories += rootProject.file("assets").path
        jniLibs.directories += layout.buildDirectory.dir("natives").get().asFile.path
    }

    lint {
        // Portrait is intentional (concept section 12). Android 16 ignores it only on large screens.
        disable += setOf("LockedOrientationActivity", "DiscouragedApi")
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
            pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

val natives: Configuration = configurations.create("natives")

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.android)
    implementation(libs.ktor.client.okhttp)
    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
        natives(variantOf(libs.gdx.platform) { classifier("natives-$abi") })
    }
}

// Extracts the native libGDX libraries per ABI to build/natives/<abi>/ (where the APK build looks for them).
val copyAndroidNatives = tasks.register("copyAndroidNatives") {
    val out = layout.buildDirectory.dir("natives")
    inputs.files(natives)
    outputs.dir(out)
    doLast {
        natives.files.forEach { jar ->
            val abi = listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86").firstOrNull { jar.name.endsWith("natives-$it.jar") } ?: return@forEach
            copy {
                from(zipTree(jar)) { include("*.so") }
                into(out.get().dir(abi))
            }
        }
    }
}
tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach { dependsOn(copyAndroidNatives) }
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach { dependsOn(copyAndroidNatives) }
