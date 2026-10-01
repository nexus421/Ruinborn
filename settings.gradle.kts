pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the toolchain JDK (Corretto 25) if needed, e.g. in GitHub Actions.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        maven("https://maven.kickner.bayern/releases") {
            content { includeGroup("bayern.kickner") }
        }
    }
}

rootProject.name = "ruinborn"

include("shared", "client", "core", "lwjgl3", "server", "tools")

// The Android app is only built if an Android SDK is found (ANDROID_HOME, local.properties or
// ~/Android/Sdk). This keeps the server and desktop client buildable without an SDK.
val androidSdk: File? = run {
    val local = file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.trim()
    listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), local, System.getProperty("user.home") + "/Android/Sdk")
        .map { File(it) }.firstOrNull { File(it, "platforms").isDirectory }
}
if (androidSdk != null && providers.gradleProperty("ruinborn.skipAndroid").isPresent.not()) {
    if (file("local.properties").isFile.not()) file("local.properties").writeText("sdk.dir=${androidSdk.absolutePath}\n")
    include("android")
} else {
    logger.lifecycle("Modul :android übersprungen (kein Android SDK gefunden oder -Pruinborn.skipAndroid gesetzt).")
}
