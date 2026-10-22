// {{display_name}}: {{description}}

plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.getlingara:lingara-apps-java:0.1.0-alpha.8")
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

tasks.test {
    useJUnitPlatform()
}
