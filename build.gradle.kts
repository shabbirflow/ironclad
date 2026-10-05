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

// Benchmark harness defaults. One fork and three iterations is enough for a
// smoke test and useless for a result: on a laptop the error bars can exceed the
// score. These defaults are strong enough to compare two implementations, and
// `-Pbench=<regex>` narrows the run so it finishes in minutes.
//
// The gc profiler reports bytes allocated per operation, which answers "is this
// allocation or is this layout" with a measurement rather than a guess.
jmh {
    (project.findProperty("bench") as String?)?.let { includes = listOf(it) }
    fork = 2
    warmupIterations = 5
    iterations = 5
    profilers = listOf("gc")
}

application {
    mainClass = "io.github.shabbirflow.ironclad.Main"
}

tasks.test {
    useJUnitPlatform()
}
