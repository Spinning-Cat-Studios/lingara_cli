// lingara-cli-java: the Java kit of `lingara` (ADR 16.10.26af D2, D5).
//
// The root build.gradle.kts gives this project its group, version, JDK 17
// toolchain and Central publication; this file adds what only the Java kit
// needs:
//
// - picocli for the command tree, java-keyring (and the JNA it already
//   brings, which the TTY test calls too) for the keychain, tomlj and
//   networknt's json-schema-validator for platform.toml, and Jackson for
//   every JSON value. HTTP is java.net.http, so no client library.
// - Two generated resources, never committed: kit.properties (the repo's
//   VERSION, so `--version` reads no ../VERSION at run time) and
//   templates.idx (every file under the committed assets' templates/apps/,
//   because a jar's classpath cannot be listed). The assets directory itself
//   holds only the shared set, as tools/kit-assets.sh requires (D8).
// - Main-Class on the main jar and the shaded `-all` jar alike (D5): jbang
//   runs the first with its dependencies resolved, `java -jar` the second
//   with none.
// - Checkstyle at the repo's budgets (.code-health/thresholds.toml), and a
//   15-files-per-directory check Checkstyle cannot express.

plugins {
    application
    checkstyle
    id("com.gradleup.shadow")
}

description = "lingara, the Lingara developer CLI: the Java kit."

base { archivesName.set("lingara-cli-java") }

application { mainClass.set("com.getlingara.cli.java.Main") }

dependencies {
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    // networknt logs through SLF4J; with no provider, SLF4J's own warning
    // would reach stderr, which B1 keeps for this CLI's lines alone.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val kitVersion = rootProject.file("VERSION").readText().trim()
val assetsDir = layout.projectDirectory.dir("src/main/resources/lingara")
val generatedResources = layout.buildDirectory.dir("generated/kit-resources")

// kit.properties and templates.idx, under the kit's own package so nothing
// is added beside the shared assets.
val kitResources by tasks.registering {
    val templates = assetsDir.dir("templates/apps")
    inputs.dir(templates)
    inputs.property("version", kitVersion)
    outputs.dir(generatedResources)
    doLast {
        val out = generatedResources.get().dir("com/getlingara/cli/java").asFile
        out.mkdirs()
        out.resolve("kit.properties").writeText("version=$kitVersion\n")
        val root = templates.asFile
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        out.resolve("templates.idx").writeText(files.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main { resources.srcDir(files(generatedResources).builtBy(kitResources)) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Xlint:-try"))
}

// Gradle's default excludes would drop a template's dotfile; the templates
// spell `.gitignore` as `_gitignore` (spec/templates.md), but a future one
// must not vanish silently from the jar.
tasks.processResources { duplicatesStrategy = DuplicatesStrategy.FAIL }

val mainClassName = "com.getlingara.cli.java.Main"

// Central's javadoc jar is real javadoc (the root's JavaLibrary config); the
// comments here explain why, not every parameter, so doclint skips "missing".
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
    options.quiet()
}

// The pom rides inside the main jar under META-INF/maven/, as Maven's own
// jars carry it, so 16.10.26af AC14's metadata scan reads it there too (D5).
tasks.jar {
    manifest { attributes("Main-Class" to mainClassName) }
    into("META-INF/maven/com.getlingara/lingara-cli-java") {
        from(tasks.named("generatePomFileForMavenPublication"))
        rename { "pom.xml" }
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest { attributes("Main-Class" to mainClassName) }
    mergeServiceFiles()
}

// The `-all` jar rides the Central publication beside the main jar (D5,
// 16.10.26af AC9) with no line here: shadow 9 adds its outgoing variant to
// the `java` component the publication is built from.

tasks.test {
    useJUnitPlatform()
    // The shared vectors and fixtures are read in place by the unit tests
    // only; the kit itself never reads outside its own jar (D8).
    systemProperty("lingara.repo", rootProject.projectDir.path)
}

checkstyle {
    toolVersion = "10.26.1"
    configDirectory.set(layout.projectDirectory.dir("config/checkstyle"))
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.checkstyleTest { configFile = file("config/checkstyle/checkstyle-test.xml") }

// .code-health's dir_files: 15 source files per directory, test files apart.
val checkFolderSizes by tasks.registering {
    val roots = listOf("src/main/java").map { layout.projectDirectory.dir(it).asFile }
    inputs.files(roots)
    doLast {
        val crowded = roots.flatMap { root -> root.walkTopDown().filter { it.isDirectory }.toList() }
            .map { dir -> dir to (dir.listFiles { f -> f.isFile }?.size ?: 0) }
            .filter { (_, count) -> count > 15 }
        check(crowded.isEmpty()) { crowded.joinToString("\n") { (d, n) -> "$d holds $n files; the budget is 15" } }
    }
}

tasks.check { dependsOn(checkFolderSizes) }
