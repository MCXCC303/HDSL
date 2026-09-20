// Build logic for HMCL-DSH packaging. Kept minimal on purpose: unlike HMCL's
// buildSrc this only needs the archive writers used to produce a .deb.
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation(libs.jetbrains.annotations)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.ar)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
