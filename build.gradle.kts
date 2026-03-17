plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "dev.forge"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI framework
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    // Rich terminal output
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // YAML config
    implementation("org.yaml:snakeyaml:2.3")

    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Voice: Whisper JNI (speech-to-text)
    implementation("io.github.givimad:whisper-jni:1.7.1")

    // File processing: PDF
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // File processing: Word/Excel
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // File processing: HTML
    implementation("org.jsoup:jsoup:1.18.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("forge.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "forge.MainKt"
    }
}
