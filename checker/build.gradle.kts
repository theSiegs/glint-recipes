import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Versions match Glint's gradle/libs.versions.toml (kotlin, serialization, re2j).
plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
    sourceSets["main"].kotlin.srcDir("src/catalog/kotlin")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.google.re2j:re2j:1.8")
}

val catalog = rootProject.layout.projectDirectory.dir("..")

// `./gradlew -p checker run --args="<recipes dir> <fixtures dir> <revoked.txt>"` (paths from checker/).
application {
    mainClass.set("com.thesiegs.glint.exchange.CatalogCheckKt")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
}

// `./gradlew -p checker checkCatalog` (also run by `check`): the whole catalog.
val checkCatalog = tasks.register<JavaExec>("checkCatalog") {
    group = "verification"
    description = "Checks every catalog recipe against the schema, its fixtures and the catalog rules."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.thesiegs.glint.exchange.CatalogCheckKt")
    workingDir = projectDir
    args(catalog.dir("recipes").asFile.path, catalog.dir("fixtures").asFile.path, catalog.file("revoked.txt").asFile.path)
}

tasks.named("check") { dependsOn(checkCatalog) }

// `./gradlew -p checker learn --args="<fixtures dir> [lang] [--out=<dir>]"`: the shape learner,
// headless (no AI). Paths are from the catalog's root.
tasks.register<JavaExec>("learn") {
    group = "verification"
    description = "Learns a tile recipe from a folder of scrubbed notification samples (no AI)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.thesiegs.glint.recipes.learn.LearnCliKt")
    workingDir = catalog.asFile
}
