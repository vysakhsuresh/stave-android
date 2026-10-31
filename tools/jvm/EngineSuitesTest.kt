import org.junit.Test

/**
 * The same suites as JUnit, for `./gradlew :engine:test` on the laptop and in CI. One test per
 * suite so the report names the one that failed. Excluded from the sandbox runner's compile
 * (run.sh), which has no JUnit; everything else in tools/jvm is shared.
 */
class EngineSuitesTest {
    @Test fun everySuitePasses() {
        val failures = mutableListOf<String>()
        for (suite in Registry.all) {
            try { suite.run() } catch (e: Throwable) { failures += "${suite.name}: ${e.message}" }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }
}
