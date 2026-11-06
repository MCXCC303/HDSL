import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jackhuang.hmcl.gradle.pack.CreateDeb
import org.jackhuang.hmcl.gradle.pack.ReleaseType

import java.security.MessageDigest

plugins {
    java
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.launch4j)
}

group = "org.jackhuang.hmcl"

// Version precedence: releaseVersion >> tag on HEAD >> <tag>+r<commits>+g<sha>
val untaggedVersion = "0.1.0"

// Trim git output
fun git(vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git", *arguments)).directory(projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (_: Exception) {
    null
}

val releaseVersion = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() }
val taggedVersion = releaseVersion ?: git("describe", "--tags", "--exact-match", "HEAD")?.removePrefix("v")

version = taggedVersion ?: run {
    val sha = git("rev-parse", "--short=7", "HEAD")
    val tag = git("describe", "--tags", "--abbrev=0", "HEAD")
    val base = tag?.removePrefix("v") ?: untaggedVersion
    val commits = git("rev-list", "--count", tag?.let { "$it..HEAD" } ?: "HEAD")

    when {
        sha != null && commits != null -> "$base+r$commits+g$sha"
        sha != null -> "$base+g$sha"
        else -> "$base-dev"
    }
}

application {
    mainClass = "org.jackhuang.hmcl.Main"
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")

    flatDir {
        name = "libs"
        dirs = setOf(rootProject.file("lib"))
    }
}

/* JavaFX */
val javafxPlatform: String = (findProperty("javafxPlatform") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isMac = os.contains("mac") || os.contains("darwin") || os.contains("osx")
    when {
        os.contains("windows") -> when (arch) {
            // Currently no win-aarch64 build is published
            "x86_64", "amd64", "aarch64", "arm64" -> "win"
            else -> error("Unsupported Windows architecture: $arch")
        }

        isMac && (arch == "aarch64" || arch == "arm64") -> "mac-aarch64"
        isMac -> when (arch) {
            "x86_64", "amd64" -> "mac"
            else -> error("Unsupported macOS architecture: $arch")
        }

        os.contains("linux") -> when (arch) {
            "aarch64", "arm64" -> "linux-aarch64"
            "x86_64", "amd64" -> "linux"
            else -> error("Unsupported Linux architecture: $arch")
        }

        else -> error("HDSL supports Windows, Linux and macOS (detected os.name=$os)")
    }
}

val javafxVersion: String = (findProperty("javafxVersion") as String?) ?: run {
    val feature = System.getProperty("java.specification.version").substringBefore('.').toInt()
    when {
        feature >= 23 -> "25"
        // 21.0.8 publishes no linux-aarch64 build
        javafxPlatform == "linux-aarch64" -> "21.0.1"
        else -> "21.0.8"
    }
}

/* dependencies */
dependencies {
    implementation("libs:JFoenix")

    implementation(libs.jetbrains.annotations)
    implementation(libs.gson)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.kala.compress.zip)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.ar)
    implementation(libs.kala.encoding.detctor)
    implementation(libs.simple.png.javafx)
    implementation(libs.xz)
    implementation(libs.pci.ids)
    implementation(libs.nanohttpd)
    implementation(libs.jsoup)
    implementation(libs.fxsvgimage)
    implementation(libs.monet.fx)
    implementation(libs.jwebp)
    implementation(libs.weburl)
    implementation(libs.uuid.tools)
    implementation(libs.commonmark)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.underline)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.table)

    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // CI perform -Pexternal everytime
        if (!project.hasProperty("external")) excludeTags("external")
        // all type of l10n key-missing Test
        if (!project.hasProperty("all-locales")) excludeTags("all-locales")
    }
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        info.events("failed", "skipped")
        debug.events("passed", "failed", "skipped")
        showStackTraces = true
    }

    // Tests write settings into build/test-home
    systemProperty("hdsl.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
}

/* resources */
val generateLanguageList = tasks.register("generateLanguageList") {
    description = ""
    val langDir = layout.projectDirectory.dir("src/main/resources/assets/lang")
    val outputDir = layout.buildDirectory.dir("generated/languageList")

    inputs.dir(langDir)
    outputs.dir(outputDir)

    doLast {
        val tags = sortedSetOf<String>()
        langDir.asFile.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("I18N") && name.endsWith(".properties")) {
                val tag = name.removePrefix("I18N").removeSuffix(".properties").removePrefix("_")
                tags.add(if (tag.isEmpty()) "en" else tag.replace('_', '-'))
            }
        }
        val target = outputDir.get().dir("assets/lang").file("languages.json").asFile
        target.parentFile.mkdirs()
        target.writeText(tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"" + it + "\"" })
    }
}

sourceSets.main {
    resources.srcDir(generateLanguageList)
}

/* toolchain */
java {
    // JDK 21+
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// JavaFX (same as HMCL)
val addExports = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding",
    "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime",
    "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css",
    "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene",
    "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage",
    "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control.skin",
)

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(addExports.map { "--add-exports=$it=ALL-UNNAMED" })
    // Report error instead of stopping at the default 100.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

tasks.named<JavaExec>("run") {
    jvmArgs(addExports.map { "--add-exports=$it=ALL-UNNAMED" })
    systemProperty("hdsl.version.override", project.version.toString())
}

// -PdebugHome=<path> overrides the directory
tasks.register<JavaExec>("debug-run") {
    group = "application"
    description = "Runs the launcher from the build tree, with a home of its own."
    mainClass.set("org.jackhuang.hmcl.Main")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs(addExports.map { "--add-exports=$it=ALL-UNNAMED" })
    systemProperty("hdsl.version.override", project.version.toString())
    systemProperty(
        "hdsl.home",
        (findProperty("debugHome") as String?) ?: layout.buildDirectory.dir("debug-home").get().asFile.absolutePath
    )
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("startScripts") { enabled = false }
tasks.named("installDist") { enabled = false }

/* fat jar */
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("hdsl")
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    manifest {
        attributes(
            "Main-Class" to "org.jackhuang.hmcl.Main",
            "Implementation-Title" to "HDSL",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

/* Artifacts */
val artifactName: String = "hdsl-${project.version}"

// Windows
launch4j {
    mainClassName = "org.jackhuang.hmcl.Main"
    setJarTask(tasks.named("shadowJar"))
    headerType = "gui"
    outfile = "$artifactName.exe"
    // including 32/64/128/256 px PNG entries
    icon = "${projectDir}/packaging/hdsl.ico"
    copyConfigurable = files()
    jreMinVersion = "21.0.0"
    requires64Bit = true
    requiresJdk = false
    chdir = ""
    errTitle = "Hello DeepSeek! Launcher"
    downloadUrl = "https://adoptium.net/temurin/releases/?os=windows"
    companyName = "HDSL contributors"
    fileDescription = "Hello DeepSeek! Launcher"
    productName = "Hello DeepSeek! Launcher"
    internalName = "HDSL"
    copyright = "GPLv3 with additional terms; see LICENSE"
}

// two Linux artifacts: a self-executing `.sh` and `.deb`
val makeExecutable = tasks.register("makeExecutable") {
    group = "distribution"
    description = "Builds the self-executing .sh launcher."
    dependsOn(tasks.named("shadowJar"))

    val jarTask = tasks.named<Jar>("shadowJar")
    val stub = layout.projectDirectory.file("packaging/launcher.sh")
    val output = layout.buildDirectory.file("libs/$artifactName.sh")

    inputs.file(stub)
    inputs.file(jarTask.flatMap { it.archiveFile })
    outputs.file(output)

    doLast {
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.outputStream().use { stream ->
            stub.asFile.inputStream().use { it.copyTo(stream) }
            jarTask.get().archiveFile.get().asFile.inputStream().use { it.copyTo(stream) }
        }
        target.setExecutable(true, false)
        logger.lifecycle("Built ${target.name} (${target.length() / 1024 / 1024} MiB)")
    }
}

// This build produces the stable channel only.
val makeDeb = tasks.register("makeDeb", CreateDeb::class) {
    group = "distribution"
    description = "Builds the Debian package."
    dependsOn(makeExecutable)

    version.set(project.version.toString())
    releaseType.set(ReleaseType.STABLE)
    launcherClassName.set("org.jackhuang.hmcl.Main")
    appShFile.set(layout.buildDirectory.file("libs/$artifactName.sh"))
    iconFile.set(layout.projectDirectory.file("src/main/resources/assets/img/icon@8x.png"))

    homepage.set("https://github.com/MCXCC303/HDSL")
    outputFile.set(layout.buildDirectory.file("libs/${artifactName}.deb"))
}

val makeWindowsExecutable = tasks.register("makeWindowsExecutable") {
    group = "distribution"
    description = "Publishes the Windows executable and its checksum."
    dependsOn(tasks.named("createExe"))

    val built = layout.buildDirectory.file("launch4j/${artifactName}.exe")
    val target = layout.buildDirectory.file("libs/${artifactName}.exe")
    val checksum = layout.buildDirectory.file("libs/${artifactName}.exe.sha256")

    inputs.file(built)
    outputs.files(target, checksum)

    doLast {
        val source = built.get().asFile
        if (!source.isFile) throw GradleException("launch4j did not produce $source")

        val destination = target.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)

        // `java.security.MessageDigest` cannot be fully qualified here
        val digest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        checksum.get().asFile.writeText(
            digest.digest()
                .joinToString(separator = "") { "%02x".format(it) } + "  " + destination.name + "\n")
        logger.lifecycle("Built ${destination.name} (${destination.length() / 1024 / 1024} MiB)")
    }
}

tasks.named("build") {
    dependsOn(makeExecutable, makeDeb)
    if (javafxPlatform == "win") dependsOn(makeWindowsExecutable)
}
