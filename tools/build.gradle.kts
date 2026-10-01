import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build tools: texture packing (gdx-tools) and bitmap fonts (FreeType, only here).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    compilerOptions { jvmTarget = JvmTarget.JVM_25 }
}

dependencies {
    implementation(libs.gdx)
    implementation(libs.gdx.tools)
    implementation(libs.gdx.backend.headless)
    implementation(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    implementation(libs.gdx.freetype)
    implementation(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
}

tasks.register<JavaExec>("packTextures") {
    group = "ruinborn"
    description = "Packt assets-raw/sprites mit dem libGDX-TexturePacker nach assets/atlas/game.atlas."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "bayern.kickner.ruinborn.tools.PackTexturesKt"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("generateFonts") {
    group = "ruinborn"
    description = "Erzeugt die Bitmap-Schriften (Noto Sans 16/20/28 px, 1,5-fach) nach assets/fonts/."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "bayern.kickner.ruinborn.tools.GenerateFontsKt"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
