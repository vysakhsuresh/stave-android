import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.json.JsonArray
import com.layerbit.stave.engine.json.JsonException
import com.layerbit.stave.engine.json.JsonObject

object JsonChecks : Suite {
    override val name = "json"

    override fun run() {
        section("round trip of every type")
        val o = JsonObject().put("name", "Ta\"ke\n1 ✓ മലയാളം").put("count", 3).put("big", 1L shl 40).put("on", true)
            .putFinite("gain", -6.5).put("nested", JsonObject().put("x", 1)).put("list", JsonArray().add(1).add("two").addFinite(3.25).add(false))
        val text = Json.write(o)
        val back = Json.parseObject(text)
        check(back == o) { "round trip differs:\n$text\n${Json.write(back)}" }
        check(back.string("name") == "Ta\"ke\n1 ✓ മലയാളം" && back.int("count") == 3 && back.long("big") == (1L shl 40) && back.bool("on")) { "values" }
        near(back.double("gain"), -6.5, 1e-12, "double")
        check(back.obj("nested")!!.int("x") == 1 && back.arr("list")!!.string(1) == "two" && back.arr("list")!!.double(2) == 3.25) { "nested" }
        check(Json.write(Json.parse(Json.write(o, pretty = true))) == text) { "pretty form parses back the same" }

        section("non-finite numbers never reach the file")
        val nan = JsonObject().putFinite("a", Double.NaN).putFinite("b", Float.POSITIVE_INFINITY).putFinite("c", 1.0)
        check(!nan.has("a") && !nan.has("b") && nan.has("c")) { "NaN and infinity are left out of an object" }
        near(nan.double("a", 0.75), 0.75, 0.0, "a missing field reads the default, not NaN")
        val arr = JsonArray.of(floatArrayOf(1f, Float.NaN, 3f))
        check(arr.size == 3 && arr.float(1) == 0f) { "an array keeps its length with a zero in place" }
        check(!Json.write(JsonObject().putFinite("z", Double.NaN)).contains("NaN")) { "writer" }

        section("whole numbers write without a fraction, doubles keep theirs")
        check(Json.write(JsonObject().putFinite("a", 2.0).putFinite("b", 2.5)) == "{\"a\":2,\"b\":2.5}") { Json.write(JsonObject().putFinite("a", 2.0).putFinite("b", 2.5)) }

        section("defaults and missing")
        val empty = JsonObject()
        check(empty.string("x") == null && empty.int("x", 7) == 7 && empty.long("x", 9L) == 9L && empty.float("x", 1.5f) == 1.5f && !empty.bool("x") && empty.obj("x") == null) { "defaults" }
        check(empty.string("x", "d") == "d") { "string default" }

        section("parser refuses garbage and accepts the awkward")
        for (bad in listOf("{", "[1,", "{\"a\" 1}", "tru", "{\"a\":1} x", "\"unterminated")) {
            var threw = false
            try { Json.parse(bad) } catch (_: JsonException) { threw = true }
            check(threw) { "accepted: $bad" }
        }
        check(Json.parse(" \n[ ] ") is JsonArray && Json.parse("{ }") is JsonObject && Json.parse("-12.5e2") == -1250.0 && Json.parse("\"\\u0041\\n\"") == "A\n") { "awkward forms" }
        check(Json.parse("12345678901234") == 12345678901234L) { "a long stays a long" }

        section("array helpers")
        val a = JsonArray.ofObjects(listOf(JsonObject().put("i", 1), JsonObject().put("i", 2)))
        check(a.objects().map { it.int("i") } == listOf(1, 2)) { "objects" }
        check(JsonArray.ofStrings(listOf("a", "b")).strings() == listOf("a", "b")) { "strings" }
        check(JsonArray.of(longArrayOf(5, 6)).longs().toList() == listOf(5L, 6L)) { "longs" }
        check(JsonArray.of(doubleArrayOf(0.5, 1.5)).doubles().toList() == listOf(0.5, 1.5)) { "doubles" }
    }
}
