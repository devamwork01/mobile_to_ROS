package com.sensorstream.core.filter

import org.json.JSONObject

object FilterConfigCodec {
    private fun parseOne(o: JSONObject): FilterConfig {
        val lp = o.optJSONObject("lowpass")
        val arr = o.optJSONArray("notches")
        val notches = (0 until (arr?.length() ?: 0)).map { i ->
            val n = arr!!.getJSONObject(i)
            Notch(n.getDouble("hz"), n.getDouble("q"))
        }
        return FilterConfig(lp?.getDouble("hz"), lp?.optInt("order", 4) ?: 4, notches)
    }

    /** One config in the laptop's JSON shape (plain or per-axis), or null if it is malformed. */
    fun parse(o: JSONObject): FilterConfig? = runCatching {
        val axesArr = if (o.has("axes") && !o.isNull("axes")) o.getJSONArray("axes") else null
        val axes = axesArr?.let { a -> (0 until a.length()).map { parseOne(a.getJSONObject(it)) } }
        val cfg = parseOne(o).copy(axes = axes)
        if (Biquad.validate(cfg, null) != null) null else cfg
    }.getOrNull()

    /** The laptop's `configs` map; malformed entries are skipped. */
    fun parseConfigs(configs: JSONObject): Map<String, FilterConfig> {
        val out = LinkedHashMap<String, FilterConfig>()
        for (key in configs.keys()) {
            val o = configs.optJSONObject(key) ?: continue
            parse(o)?.let { out[key] = it }
        }
        return out
    }

    fun toJson(cfg: FilterConfig): JSONObject {
        val o = toJsonOne(cfg)
        cfg.axes?.let { axes -> o.put("axes", org.json.JSONArray().also { a -> axes.forEach { a.put(toJsonOne(it)) } }) }
        return o
    }

    private fun toJsonOne(cfg: FilterConfig): JSONObject {
        val o = JSONObject()
        o.put("lowpass", cfg.lowpassHz?.let { JSONObject().put("hz", it).put("order", cfg.order) } ?: JSONObject.NULL)
        val arr = org.json.JSONArray()
        cfg.notches.forEach { arr.put(JSONObject().put("hz", it.hz).put("q", it.q)) }
        return o.put("notches", arr)
    }

    fun encode(m: Map<String, FilterConfig>): String {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, toJson(v)) }
        return o.toString()
    }

    fun decode(s: String?): Map<String, FilterConfig> =
        if (s.isNullOrBlank()) emptyMap() else runCatching { parseConfigs(JSONObject(s)) }.getOrDefault(emptyMap())

    private fun hz(x: Double) = if (x == Math.floor(x)) String.format(java.util.Locale.US, "%.0f", x) else String.format(java.util.Locale.US, "%.1f", x)

    /** e.g. "LP 5 Hz · 4th + notch 8 Hz"; per-axis: "X/Y LP 0.5 Hz · 4th | Z LP 5 Hz · 4th + notch 8 Hz"
     *  (axes grouped only when their filters are identical, not merely displayed alike). */
    fun summary(cfg: FilterConfig): String {
        val axes = cfg.axes ?: return summaryOne(cfg)
        val groups = LinkedHashMap<FilterConfig, MutableList<Char>>()
        axes.forEachIndexed { i, ax -> groups.getOrPut(ax) { ArrayList() }.add("XYZ"[i]) }
        return groups.entries.joinToString(" | ") { (ax, names) -> names.joinToString("/") + " " + summaryOne(ax) }
    }

    private fun summaryOne(cfg: FilterConfig): String {
        val parts = ArrayList<String>()
        cfg.lowpassHz?.let { parts.add("LP ${hz(it)} Hz · ${if (cfg.order == 2) "2nd" else "4th"}") }
        if (cfg.notches.isNotEmpty()) parts.add("notch " + cfg.notches.joinToString(", ") { hz(it.hz) } + " Hz")
        return parts.joinToString(" + ")
    }
}
