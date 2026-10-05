plugins {
    application
    // Generates the JMH runner code and adds the src/jmh source set, so a
    // benchmark is a @Benchmark method and `./gradlew jmh` is the whole command.
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.shabbirflow"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.shabbirflow.ironclad.Main"
}

tasks.test {
    useJUnitPlatform()
}
