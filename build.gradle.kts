// The root of the Java and Kotlin kits' shared build (ADR 16.10.26af D5). It
// builds nothing itself: it pins the plugins once, and gives both kits the
// same version, JDK 17 toolchain, shaded jar and Maven Central publication.
//
// - The artifact is `lingara-cli-<project name>`: lingara-cli-java,
//   lingara-cli-kotlin, both under com.getlingara.
// - com.vanniktech.maven.publish speaks the Central Portal API and signs from
//   in-memory keys (ORG_GRADLE_PROJECT_signingInMemoryKey and friends), as
//   the API clients and the app kits do. Signing is required only when a key
//   is present, so a local build needs none.
// - Java's javadoc jar is real javadoc; Kotlin's is the plugin's empty jar,
//   since Central requires the file, not its contents.
// - The `staging` file repository under build/ is what the dry run publishes
//   to: only a repository publish writes the .md5 and .sha1 files Central
//   requires.
// - The shaded jar is the `all` classifier, and Main-Class is set on it and
//   on the main jar alike, so `java -jar …-all.jar` and jbang both run.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

val kitVersion = rootProject.file("VERSION").readText().trim()
val publicRepo = "https://github.com/Spinning-Cat-Studios/lingara_cli"

subprojects {
    group = "com.getlingara"
    version = kitVersion

    apply(plugin = "com.vanniktech.maven.publish")

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates("com.getlingara", "lingara-cli-${project.name}", kitVersion)
        pom {
            name.set("lingara-cli-${project.name}")
            description.set("lingara, the Lingara CLI: the ${project.name.replaceFirstChar(Char::uppercase)} kit.")
            url.set(publicRepo)
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/license/mit")
                }
            }
            scm {
                url.set(publicRepo)
                connection.set("scm:git:$publicRepo.git")
                developerConnection.set("scm:git:ssh://git@github.com/Spinning-Cat-Studios/lingara_cli.git")
            }
            developers {
                developer {
                    id.set("spinning-cat-studios")
                    name.set("Spinning Cat Studios")
                    email.set("support@getlingara.com")
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
        }
    }

    pluginManager.withPlugin("application") {
        if (!pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            extensions.configure<MavenPublishBaseExtension> {
                configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
            }
        }
    }
}
