// {{display_name}}: {{description}}

plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.getlingara:lingara-apps-java:0.1.0-alpha.8")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "app.Main"
}

// `lingara apps update` runs `gradle --quiet manifest` and uploads what it prints.
tasks.register<JavaExec>("manifest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.ManifestMain"
}

tasks.test {
    useJUnitPlatform()
}
