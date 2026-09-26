// HMCL-DSH — a DeepSeek Harness launcher built on HMCL's JavaFX UI kit.
//
// The presentation layer (window chrome, component library, animations, theme
// engine, image pipeline, async task engine, i18n) is transplanted from HMCL
// and kept under its original `org.jackhuang.hmcl.*` package names, as GPLv3
// requires the original copyright notices to stay intact. Everything that
// launched Minecraft has been removed; the domain layer is HMCL-DSH's own.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
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

// The version a build with no tag to stand on carries. A release takes its version from the
// tag, `-PreleaseVersion=1.2.3`; a build whose own commit is tagged takes the tag's version; a
// build past a tag carries that tag's version plus the commit it came from, `0.2.0+g1a613c5`,
// so a package built here is never mistaken for one a tag published. Only a repository with no
// tags at all falls back to this.
val untaggedVersion = "0.1.0"

// Runs git in the repository and returns its trimmed output, or null when git is
// missing, the directory is not a repository, or the command fails. The version below
// asks git once, at configuration time, so the answer is baked into the jar's manifest
// and the running launcher never needs git.
fun git(vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git", *arguments))
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (exception: Exception) {
    null
}

// A release is the build that was handed the tag's version. A build of a commit that is
// itself tagged is that tag's version too. Everything else is a git build, and names the
// nearest tag it stands on — read from git, not from a constant — so a build seven commits
// past `v0.2.0` says `0.2.0+...` and not the version this file happened to be written at.
val releaseVersion = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() }
val taggedVersion = releaseVersion
    ?: git("describe", "--tags", "--exact-match", "HEAD")?.removePrefix("v")
version = taggedVersion ?: run {
    val sha = git("rev-parse", "--short=7", "HEAD")
    val base = git("describe", "--tags", "--abbrev=0", "HEAD")?.removePrefix("v") ?: untaggedVersion
    if (sha != null) "$base+g$sha" else "$base-dev"
}

application {
    // HMCL-DSH application entry point.
    mainClass = "org.jackhuang.hmcl.Main"
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")

    // HMCL patches a handful of JFoenix classes with vendored sources that
    // shadow this jar at compile time; the rest of the library (converters,
    // unchecked skins, ...) still comes from here.
    flatDir {
        name = "libs"
        dirs = setOf(rootProject.file("lib"))
    }
}

// --------------------------------------------------------------- JavaFX ------
// HMCL-DSH runs on Linux and Windows. JavaFX must match the JDK that runs
// Gradle: the 21.x line supports JDK 17–22, the 25 line is required from JDK 23
// on. This mirrors HMCL's own JavaFXPlatform.CLASSIC/MODERN split without
// carrying its buildSrc plugin.
val javafxPlatform: String = (findProperty("javafxPlatform") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("windows") -> when (arch) {
            // OpenJFX publishes no win-aarch64 build; Windows on ARM runs the
            // x64 one under its emulation, which is what HMCL's own Windows
            // ARM64 users run too. The classifier is the same either way.
            "x86_64", "amd64", "aarch64", "arm64" -> "win"
            else -> error("Unsupported Windows architecture: $arch")
        }
        os.contains("linux") -> when (arch) {
            "aarch64", "arm64" -> "linux-aarch64"
            "x86_64", "amd64" -> "linux"
            else -> error("Unsupported Linux architecture: $arch")
        }
        else -> error("HMCL-DSH supports Linux and Windows (detected os.name=$os)")
    }
}

val javafxVersion: String = (findProperty("javafxVersion") as String?) ?: run {
    val feature = System.getProperty("java.specification.version").substringBefore('.').toInt()
    when {
        feature >= 23 -> "25"
        // The 21.0.8 line publishes no linux-aarch64 build; 21.0.1 is the one
        // of that line that does, and it is the version HMCL itself pins there.
        javafxPlatform == "linux-aarch64" -> "21.0.1"
        else -> "21.0.8"
    }
}

// ---------------------------------------------------------- dependencies -----
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
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        // The full form, so a failure's message and stack are in the log the
        // runner keeps, and not only in the report it holds at a local path.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    // The launcher keeps its state in one per-user home, and a test that writes
    // settings must not write into the one the user is running. Tests get a home
    // of their own inside the build tree, which is also what makes them able to
    // assert on what was persisted.
    systemProperty("hdsl.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // A JavaFX toolkit that failed to start leaves a test blocked forever on
    // the interface thread rather than failed — a build without the native GTK
    // libraries hangs here instead of reporting. A bound on every method turns
    // that hang into a failure the suite can name. No test is legitimately
    // slow: the longest waits for a child to exit and does so in seconds.
    systemProperty("junit.jupiter.execution.timeout.method.default", "120s")
}

// --------------------------------------------------------------- resources ---
// HMCL generates this list at build time. HMCL-DSH does the same, so the
// language picker can never drift from the .properties files actually shipped.
//
// Registered through `tasks.register` rather than the `by tasks.registering`
// delegate: Gradle 9.6 deprecated the delegate, and 9.7 rejects it as an
// error while compiling the script.
val generateLanguageList = tasks.register("generateLanguageList") {
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

// ------------------------------------------------------------- toolchain -----
java {
    // HMCL-DSH requires JDK 21+. We do not pin a toolchain so the build works
    // with whichever >= 21 JDK the developer has active (Arch: `archlinux-java`).
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// JavaFX internals used by the transplanted UI kit (skins, prism, glass).
// Kept identical to HMCL's build so the ported code compiles unchanged.
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
    // Report every error instead of stopping at the default 100. During the
    // port this is what proves that all remaining errors live in the known
    // cut-point files.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
}

tasks.named<JavaExec>("run") {
    jvmArgs(addExports.map { "--add-exports=$it=ALL-UNNAMED" })
    systemProperty("hdsl.version.override", project.version.toString())
}

// The application plugin is used for its `run` task only. Its distribution
// tasks would try to package the jar this build disables, and the launcher
// ships a self-executing .sh, a .deb and a launch4j-wrapped .exe instead, so
// they are switched off rather than taught to agree about the shadow jar.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("startScripts") { enabled = false }
tasks.named("installDist") { enabled = false }

// ------------------------------------------------------------------ fat jar --
// The launcher ships as a single self-contained jar, so the shell stub can be
// prepended to it, the whole thing wrapped into a Windows executable, and any
// copy run with `java -jar`.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    // Dependency signatures do not survive merging, and a stale one makes the
    // JVM refuse to start.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    manifest {
        attributes(
            "Main-Class" to "org.jackhuang.hmcl.Main",
            "Implementation-Title" to "HMCL-DSH",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// ----------------------------------------------------------- the executable --
// The Windows artifact is what the .sh is on Linux: one file that is the whole
// launcher. launch4j wraps the fat jar into a small executable that looks for
// a Java 21+ runtime and hands the jar to it, and the build works from any
// platform — launch4j carries the tool it needs for each one.
val artifactName: String = "hdsl-${project.version}"

launch4j {
    mainClassName = "org.jackhuang.hmcl.Main"
    // The fat jar, not the (disabled) plain one.
    setJarTask(tasks.named("shadowJar"))
    headerType = "gui"
    outfile = "$artifactName.exe"
    icon = "${projectDir}/packaging/hdsl.ico"
    // The jar is inside the executable, so there is no library directory to
    // fill beside it.
    copyConfigurable = files()
    // 21, the same line the .sh stub demands, enforced before the jar runs:
    // a JVM too old to read it only ever shows this message. launch4j wants the
    // version dotted out in full (the plugin normalizes its own default the
    // same way), and the stub compares missing parts as zero, so 21.0.0
    // accepts every 21.x release and nothing older.
    jreMinVersion = "21.0.0"
    requires64Bit = true
    // A JRE is enough; the launcher manages its own Node runtimes.
    requiresJdk = false
    // The working directory is left as the caller had it, the same as the .sh
    // stub leaves it: the launcher's data lives per-user, not beside it.
    chdir = ""
    errTitle = "Hello DeepSeek! Launcher"
    downloadUrl = "https://adoptium.net/temurin/releases/?os=windows"
    companyName = "HMCL-DSH contributors"
    fileDescription = "Hello DeepSeek! Launcher"
    productName = "Hello DeepSeek! Launcher"
    internalName = "HDSL"
    copyright = "GPLv3 with additional terms; see LICENSE"
}

// -------------------------------------------------------------- packaging ----
// Produce the same two Linux artifacts HMCL ships: a self-executing `.sh`
// (shell stub with the jar appended) and a `.deb` carrying it.
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

val makeDeb = tasks.register("makeDeb", CreateDeb::class) {
    group = "distribution"
    description = "Builds the Debian package."
    dependsOn(makeExecutable)

    version.set(project.version.toString())
    // A build from this repository is a release build: the stable channel is the
    // package a tag publishes, and the nightly channel is for builds that are not
    // one. Both can be installed side by side, which is what the channel names are
    // for.
    releaseType.set(ReleaseType.STABLE)
    launcherClassName.set("org.jackhuang.hmcl.Main")
    appShFile.set(layout.buildDirectory.file("libs/$artifactName.sh"))
    // The eight-times icon rather than the thirty-two pixel one: it is what the
    // desktops that scale an icon up for a launcher grid will use.
    iconFile.set(layout.projectDirectory.file("src/main/resources/assets/img/icon@8x.png"))
    homepage.set("https://github.com/MCXCC303/HDSL")
    outputFile.set(layout.buildDirectory.file("libs/${artifactName}.deb"))
}

// Publishes the executable launch4j built beside the other artifacts, with a
// checksum the way the Linux packages carry theirs.
//
// launch4j writes into its own `build/launch4j` directory, so the copy is what
// puts the Windows artifact where the Linux ones already are — `build/libs`,
// the one directory a release takes everything from.
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
        if (!source.isFile) {
            throw GradleException("launch4j did not produce ${source}")
        }
        val destination = target.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)

        // The fully-qualified `java.security.MessageDigest` does not work in a
        // Kotlin DSL script: `java` resolves to the JavaPluginExtension
        // accessor here, so the digest comes in through an import instead.
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
            digest.digest().joinToString(separator = "") { "%02x".format(it) }
                    + "  " + destination.name + "\n"
        )
        logger.lifecycle("Built ${destination.name} (${destination.length() / 1024 / 1024} MiB)")
    }
}

tasks.named("build") {
    dependsOn(makeExecutable, makeDeb, makeWindowsExecutable)
}
