import kotlin.math.abs

/*
 * The frame every suite is written in.
 *
 * A suite is an object with a name and a run(); run() calls check() and near() and throws on the
 * first lie. Suites are plain code, not a test framework, so they compile with nothing but the
 * standard library and run the same way in the sandbox (tools/jvm/run.sh), on the laptop
 * (./gradlew :engine:test through EngineSuitesTest) and in CI.
 *
 * Every suite is also expected to have been seen failing: copy the engine file to a scratch
 * directory, put the fault back, point STAVE_ENGINE_SRC at the copy, run the one suite and read
 * the failure. A check nobody has seen fail is a check nobody has tested (Squish, 5 October).
 */
interface Suite {
    val name: String
    fun run()
}

class CheckFailed(message: String) : AssertionError(message)

fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw CheckFailed(message())
}

fun check(condition: Boolean, message: String) {
    if (!condition) throw CheckFailed(message)
}

/** |a - b| <= tolerance, with both numbers in the message so a drift reads as a number. */
fun near(actual: Double, expected: Double, tolerance: Double, what: String) {
    if (actual.isNaN() || abs(actual - expected) > tolerance) {
        throw CheckFailed("$what: expected $expected ± $tolerance, got $actual")
    }
}

fun near(actual: Float, expected: Float, tolerance: Float, what: String) = near(actual.toDouble(), expected.toDouble(), tolerance.toDouble(), what)

/** A section heading inside a suite, printed as it runs so a failure can be placed. */
fun section(title: String) = println("    - $title")
