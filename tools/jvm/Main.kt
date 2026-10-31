import kotlin.system.exitProcess

/*
 * Drives the suites. No arguments runs every suite in Registry; names run those; --list lists.
 * Exit code 1 on the first failing suite, after every suite has been given its turn, so one run
 * reports every broken thing rather than the first.
 */
fun main(args: Array<String>) {
    if (args.contains("--list")) { Registry.all.forEach { println(it.name) }; return }
    val chosen = if (args.isEmpty()) Registry.all else {
        val unknown = args.filter { a -> Registry.all.none { it.name == a } }
        if (unknown.isNotEmpty()) { System.err.println("no such suite: $unknown (try --list)"); exitProcess(2) }
        Registry.all.filter { it.name in args }
    }
    var failed = 0
    val started = System.nanoTime()
    for (suite in chosen) {
        println("=== ${suite.name} ===")
        val t = System.nanoTime()
        try {
            suite.run()
            println("    ok (${(System.nanoTime() - t) / 1_000_000} ms)")
        } catch (e: Throwable) {
            failed++
            println("    FAILED: ${e.message}")
            e.stackTrace.take(6).forEach { println("        at $it") }
        }
    }
    println()
    println("${chosen.size - failed}/${chosen.size} suites passed in ${(System.nanoTime() - started) / 1_000_000} ms")
    if (failed > 0) exitProcess(1)
}
