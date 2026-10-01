import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ktor server as a fat JAR: HTTP API, WebSocket, engine, database, jobs, admin, simulation.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    compilerOptions { jvmTarget = JvmTarget.JVM_25 }
}

application {
    mainClass = "bayern.kickner.ruinborn.server.MainKt"
    // sqlite-jdbc loads a native library. Without this option Java 25 prints a warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

ktor {
    fatJar {
        archiveFileName.set("ruinborn-server.jar")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)
    implementation(libs.klogger)
    implementation(libs.kotnexlib)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    // End-to-end tests: a real client from the client module against a real server.
    testImplementation(project(":client"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // No test may hang.
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
}

// Balancing simulation (concept section 17): real engine, controllable clock, 60 days.
tasks.register<JavaExec>("runSim") {
    group = "application"
    description = "Simuliert 60 Spieltage mit drei Spielertypen und schreibt sim-result.csv."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "bayern.kickner.ruinborn.server.sim.SimMainKt"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args("--balance", rootProject.file("dev/balance.json").path, "--out", rootProject.file("sim-result.csv").path)
    providers.gradleProperty("sim.days").orNull?.let { args("--days", it) }
}
