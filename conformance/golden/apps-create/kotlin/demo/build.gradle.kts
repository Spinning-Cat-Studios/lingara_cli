// Demo: A Lingara app.

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.getlingara:lingara-apps-kotlin:0.1.0-alpha.8")
    implementation("io.ktor:ktor-server-core:3.0.1")
    implementation("io.ktor:ktor-server-cio:3.0.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "app.ServerKt"
}

tasks.test {
    useJUnitPlatform()
}
