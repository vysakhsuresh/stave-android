import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin. No Android plugin, no Android dependency, nothing but the standard library: the
// whole point of the module is that it compiles and runs on a bare JDK (tools/jvm/run.sh).
// Every suite under tools/jvm is also a JUnit test here, so `./gradlew :engine:test` on the
// laptop and `sh tools/jvm/run.sh` in the sandbox execute the same code.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The suites divide by measured widths and compare floats; a warning about an unused
        // variable in a check is noise, a failing check is the signal.
        allWarningsAsErrors.set(false)
    }
}

sourceSets {
    test {
        kotlin.srcDir("../tools/jvm")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // One line per suite, and the assertion message when one fails.
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
