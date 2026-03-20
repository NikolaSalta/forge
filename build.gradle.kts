plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "dev.forge"
version = "0.2.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"

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

    // Ktor embedded HTTP server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // Logging (required by Ktor/Netty)
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

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
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
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

// ── Fat JAR for jpackage ───────────────────────────────────────────────────
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat JAR with all dependencies for jpackage"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "forge.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}

// ── jpackage task for native installer ─────────────────────────────────────
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Creates a native installer using jpackage (requires JDK 21+)"
    dependsOn("fatJar")

    val fatJar = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val outputDir = layout.buildDirectory.dir("jpackage").get().asFile
    val appVersion = project.version.toString().replace(Regex("[^0-9.]"), "").ifEmpty { "0.2.0" }

    val os = System.getProperty("os.name").lowercase()
    val installerType = when {
        os.contains("mac") -> "dmg"
        os.contains("win") -> "msi"
        else -> "deb"
    }

    val icon = when {
        os.contains("mac") -> listOfNotNull(
            file("src/main/resources/icons/forge.icns").takeIf { it.exists() }
        )
        os.contains("win") -> listOfNotNull(
            file("src/main/resources/icons/forge.ico").takeIf { it.exists() }
        )
        else -> listOfNotNull(
            file("src/main/resources/icons/forge.png").takeIf { it.exists() }
        )
    }

    val jpackageCmd = if (os.contains("win")) "jpackage.exe" else "jpackage"

    doFirst {
        outputDir.mkdirs()
    }

    val args = mutableListOf(
        jpackageCmd,
        "--input", fatJar.parentFile.absolutePath,
        "--main-jar", fatJar.name,
        "--main-class", "forge.MainKt",
        "--name", "FORGE",
        "--app-version", appVersion,
        "--vendor", "FORGE Project",
        "--description", "Local AI Code Intelligence - Desktop Edition",
        "--dest", outputDir.absolutePath,
        "--type", installerType,
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Xmx2g"
    )

    if (icon.isNotEmpty()) {
        args.addAll(listOf("--icon", icon.first().absolutePath))
    }

    // macOS-specific
    if (os.contains("mac")) {
        args.addAll(listOf(
            "--mac-package-identifier", "dev.forge.app",
            "--mac-package-name", "FORGE"
        ))
    }

    // Linux-specific
    if (!os.contains("mac") && !os.contains("win")) {
        args.addAll(listOf(
            "--linux-shortcut",
            "--linux-menu-group", "Development"
        ))
    }

    commandLine(args)
}
