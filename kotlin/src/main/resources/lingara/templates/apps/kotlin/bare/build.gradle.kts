// {{display_name}}: {{description}}

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.getlingara:lingara-apps-kotlin:0.1.0-alpha.8")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "app.ServerKt"
}

// `lingara apps update` runs `gradle --quiet manifest` and uploads what it prints.
tasks.register<JavaExec>("manifest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.ManifestKt"
}

tasks.test {
    useJUnitPlatform()
}
