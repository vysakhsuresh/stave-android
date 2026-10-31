pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Two modules of code and no more.
//
// :engine is pure Kotlin - it imports nothing from android.* - and holds every rule and every
// piece of arithmetic that can be separated from the framework: the DSP, the project model,
// the edit history, the loudness meter, the transcript edits, the sequencer. It compiles with
// the standalone Kotlin compiler and its suites run in seconds on any machine with a JDK
// (tools/jvm/run.sh), which is what lets a number be *executed* rather than reasoned about.
//
// :app is the Android half: Compose, Oboe, the native graph under app/src/main/cpp, the file
// doors. It depends on :engine and never the other way round; tools/jvm/run.sh fails the moment
// anything under engine/ imports an Android class.
rootProject.name = "Stave"
include(":engine")
include(":app")
