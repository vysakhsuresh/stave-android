import java.io.File

/**
 * Every *Checks.kt under tools/jvm is a suite Registry names, and every suite Registry names
 * has a file. A suite nothing runs reads as cover and is none (Squish found four of them,
 * one of which had not compiled for months).
 */
object RunnerChecks : Suite {
    override val name = "runner"

    override fun run() {
        val dir = listOf(File("tools/jvm"), File("../tools/jvm")).firstOrNull { it.isDirectory }
            ?: throw CheckFailed("tools/jvm not found from ${File(".").absolutePath}")
        val files = dir.listFiles { f -> f.name.endsWith("Checks.kt") }!!.map { it.name.removeSuffix(".kt") }.filter { it != "Checks" }.toSet()
        val registered = Registry.all.map { it::class.simpleName!! }.toSet()
        val unregistered = files - registered
        val missing = registered - files
        check(unregistered.isEmpty()) { "suites no runner names: $unregistered - add them to Registry" }
        check(missing.isEmpty()) { "Registry names suites with no file: $missing" }
        val names = Registry.all.map { it.name }
        check(names.toSet().size == names.size) { "duplicate suite names: $names" }
        check(Registry.all.size >= 10) { "only ${Registry.all.size} suites registered - has the list rotted?" }
        // The engine must never import Android.
        val engine = listOf(File("engine/src/main/kotlin"), File("../engine/src/main/kotlin")).firstOrNull { it.isDirectory }
        if (engine != null) {
            val offenders = engine.walkTopDown().filter { it.name.endsWith(".kt") }.filter { f -> f.readLines().any { it.startsWith("import android") } }.map { it.path }.toList()
            check(offenders.isEmpty()) { "engine imports android.*: $offenders" }
        }
    }
}
