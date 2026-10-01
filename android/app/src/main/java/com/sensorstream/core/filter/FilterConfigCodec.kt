package com.sensorstream.core.filter

import org.json.JSONObject

object FilterConfigCodec {
    /** One config in the laptop's JSON shape, or null if it is malformed. */
    fun parse(o: JSONObject): FilterConfig? = runCatching {
        val lp = o.optJSONObject("lowpass")
        val arr = o.optJSONArray("notches")
        val notches = (0 until (arr?.length() ?: 0)).map { i ->
            val n = arr!!.getJSONObject(i)
            Notch(n.getDouble("hz"), n.getDouble("q"))
        }
        val cfg = FilterConfig(lp?.getDouble("hz"), lp?.optInt("order", 4) ?: 4, notches)
        if (Biquad.validate(cfg, null) != null) null else cfg
    }.getOrNull()
}
