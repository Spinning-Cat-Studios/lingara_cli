// The one Gradle build the Java and Kotlin kits share (ADR 16.10.26af D5;
// the app kits' shape, 30.9.26am D8).
//
// scs-snapshot reads this file statically to learn which directories the
// build needs, so it holds only literal include(...) calls: no loop, and no
// project directory reassigned. Every project's directory is its path.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "lingara-cli"

include(":java")
include(":kotlin")
