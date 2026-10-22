// The Kotlin kit (ADR 16.10.26af D2, D5): `com.getlingara:lingara-cli-kotlin`,
// binary `lingara`, on Clikt. The root build gives it its version, the JDK 17
// toolchain and the Central publication (KotlinJvm, an empty javadoc jar);
// this file names the dependencies, the two jars and the generated resources.
//
// - The shaded jar is `lingara-cli-kotlin-<VERSION>-all.jar`, the artefact
//   conformance runs (D5, D8), with Main-Class on it and on the main jar, so
//   `java -jar …-all.jar` and jbang both start it.
// - VERSION is read here, at build time, into a generated resource: the kit
//   never reads ../VERSION at run time (D1, AC7).
// - The templates are classpath resources, and a jar cannot list a
//   directory, so the build writes their index beside the version.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("com.gradleup.shadow")
}

base { archivesName.set("lingara-cli-kotlin") }

val mainClassName = "com.getlingara.cli.kotlin.MainKt"
application { mainClass.set(mainClassName) }

dependencies {
    // clikt-core, not clikt: the full artefact pulls Mordant's terminal
    // stack, which this kit never draws with and every start-up would load.
    implementation("com.github.ajalt.clikt:clikt-core:5.0.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.tomlj:tomlj:1.1.1")
    // tomlj annotates its getters with checker-qual, which it ships only at run time.
    compileOnly("org.checkerframework:checker-qual:3.21.2")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // java-keyring and the validator log through SLF4J; with no provider it
    // prints a warning on stderr, which B1 reserves for the CLI's own lines.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val kitVersion = rootProject.file("VERSION").readText().trim()
val assetsDir = layout.projectDirectory.dir("src/main/resources/lingara")
val generatedResources = layout.buildDirectory.dir("generated/lingara-build")

// `lingara-build/version.txt` and `lingara-build/templates.txt`: the version
// `--version` prints and every template file's path under lingara/templates/.
val writeBuildInfo by tasks.registering {
    val out = generatedResources
    val templates = assetsDir.dir("templates")
    inputs.file(rootProject.file("VERSION"))
    inputs.dir(templates)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("lingara-build")
        dir.mkdirs()
        dir.resolve("version.txt").writeText(kitVersion + "\n")
        val root = templates.asFile
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
        dir.resolve("templates.txt").writeText(files.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main { resources.srcDir(writeBuildInfo) }

// The pom rides inside the main jar under META-INF/maven/, as Maven's own
// jars carry it, so 16.10.26af AC14's metadata scan reads it there too (D5).
tasks.jar {
    manifest { attributes("Main-Class" to mainClassName) }
    into("META-INF/maven/com.getlingara/lingara-cli-kotlin") {
        from(tasks.named("generatePomFileForMavenPublication"))
        rename { "pom.xml" }
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest { attributes("Main-Class" to mainClassName) }
}

tasks.test {
    useJUnitPlatform()
}
