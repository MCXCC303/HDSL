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

plugins {
    java
    application
    alias(libs.plugins.shadow)
}

group = "org.jackhuang.hmcl"
version = "0.1.0"

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
// HMCL-DSH targets Linux only. JavaFX must match the JDK that runs Gradle:
// the 21.x line supports JDK 17–22, the 25 line is required from JDK 23 on.
// This mirrors HMCL's own JavaFXPlatform.CLASSIC/MODERN split without carrying
// its buildSrc plugin.
val javafxPlatform: String = (findProperty("javafxPlatform") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    require(os.contains("linux")) { "HMCL-DSH supports Linux only (detected os.name=$os)" }
    when (arch) {
        "aarch64", "arm64" -> "linux-aarch64"
        "x86_64", "amd64" -> "linux"
        else -> error("Unsupported Linux architecture: $arch")
    }
}

val javafxVersion: String = (findProperty("javafxVersion") as String?) ?: run {
    val feature = System.getProperty("java.specification.version").substringBefore('.').toInt()
    if (feature >= 23) "25" else "21.0.8"
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
}

// --------------------------------------------------------------- resources ---
// HMCL generates this list at build time. HMCL-DSH does the same, so the
// language picker can never drift from the .properties files actually shipped.
val generateLanguageList by tasks.registering {
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
    systemProperty("hmcldsh.version.override", project.version.toString())
}

// ------------------------------------------------------------------ fat jar --
// The launcher ships as a single self-contained jar, so the shell stub can be
// prepended to it and the whole thing run with `java -jar`.
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

// -------------------------------------------------------------- packaging ----
// Produce the same two Linux artifacts HMCL ships: a self-executing `.sh`
// (shell stub with the jar appended) and a `.deb` carrying it.
val artifactName: String
    get() = "hmcl-dsh-${project.version}"

val makeExecutable by tasks.registering {
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

val makeDeb by tasks.registering(CreateDeb::class) {
    group = "distribution"
    description = "Builds the Debian package."
    dependsOn(makeExecutable)

    version.set(project.version.toString())
    releaseType.set(ReleaseType.NIGHTLY)
    launcherClassName.set("org.jackhuang.hmcl.Main")
    appShFile.set(layout.buildDirectory.file("libs/$artifactName.sh"))
    iconFile.set(layout.projectDirectory.file("src/main/resources/assets/img/icon.png"))
    outputFile.set(layout.buildDirectory.file("libs/${artifactName}.deb"))
}

tasks.named("build") {
    dependsOn(makeExecutable, makeDeb)
}
