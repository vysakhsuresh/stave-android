import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The app's strings, read as aapt2 and java.util.Formatter will read them. Three faults reached
 * the phone on 1 November 2026, none visible to the sandbox's structural compile: a name defined
 * twice (fx_apply), four bare apostrophes (aapt2 refuses them), and eight plurals whose "%1$d"
 * a script had turned into "%1 " - a format that throws the first time a count is shown, which
 * crashed the Studio tab. Each check below was seen failing on a copy with the fault put back
 * (STAVE_STRINGS points the suite at the copy).
 */
object ResourceChecks : Suite {
    override val name = "resources"

    private val spec = Regex("""%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])""")
    private val conversions = setOf('s', 'S', 'd', 'f', 'x', 'X', 'c', 'e', 'g', 'b', 'o', 'n', '%')

    override fun run() {
        val file = System.getenv("STAVE_STRINGS")?.let { File(it) }
            ?: listOf(File("app/src/main/res/values/strings.xml"), File("../app/src/main/res/values/strings.xml")).firstOrNull { it.isFile }
            ?: throw CheckFailed("strings.xml not found from ${File(".").absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement

        section("every name once")
        val names = ArrayList<String>()
        val texts = ArrayList<Pair<String, String>>()   // name, text as written
        val plurals = HashMap<String, List<String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            val n = e.getAttribute("name")
            names += "${e.tagName}:$n"
            when (e.tagName) {
                "string" -> texts += n to e.textContent
                "plurals" -> { val items = e.getElementsByTagName("item"); val l = (0 until items.length).map { items.item(it).textContent }; plurals[n] = l; l.forEach { texts += n to it } }
                "string-array" -> { val items = e.getElementsByTagName("item"); (0 until items.length).forEach { texts += n to items.item(it).textContent } }
            }
        }
        val twice = names.groupBy { it }.filter { it.value.size > 1 }.keys
        check(twice.isEmpty()) { "defined more than once: $twice" }
        check(texts.size > 100) { "only ${texts.size} strings read - is this the right file?" }

        section("no bare apostrophe or quote (aapt2 refuses them)")
        val bare = texts.filter { (_, t) -> !(t.startsWith("\"") && t.endsWith("\"")) && Regex("""(?<!\\)['"]""").containsMatchIn(t) }
        check(bare.isEmpty()) { "unescaped ' or \" in: ${bare.map { it.first }}" }

        section("every format specifier is one Formatter accepts")
        for ((n, t) in texts) {
            var i = t.indexOf('%')
            while (i >= 0) {
                val m = spec.matchAt(t, i)
                check(m != null && m.groupValues[2].single() in conversions) { "$n: a '%' Formatter cannot read at ${t.substring(i, minOf(t.length, i + 6))}… in \"$t\"" }
                i = t.indexOf('%', m!!.range.last + 1)
            }
        }

        section("a plural says the same numbers in every form")
        for ((n, forms) in plurals) {
            val specs = forms.map { f -> spec.findAll(f).map { it.value }.toSet() }
            check(specs.toSet().size == 1) { "$n: forms differ in their specifiers: $specs" }
        }
    }
}
