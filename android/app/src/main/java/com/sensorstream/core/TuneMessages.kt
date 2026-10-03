package com.sensorstream.core

import org.json.JSONArray
import org.json.JSONObject

/** Guided-tuning control messages (server `--filter`): parse prompts/results, build replies. */
object TuneMessages {
    const val CAP = "tune"

    fun parsePrompt(m: JSONObject): TunePrompt? {
        if (m.optString("type") != "tune_prompt") return null
        val arr = m.optJSONArray("sensors") ?: return null
        val sensors = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("handle")) null else TuneSensor(o.optInt("handle"), o.optString("name", "Sensor"))
        }
        if (sensors.isEmpty()) return null
        return TunePrompt(
            id = m.optInt("id"),
            reason = m.optString("reason", "connect"),
            attention = m.optBoolean("attention", false),
            leadMs = (m.optDouble("lead_s", 3.0) * 1000).toLong(),
            captureMs = (m.optDouble("capture_s", 10.0) * 1000).toLong(),
            sensors = sensors,
            stillMs = (m.optDouble("still_s", 0.0) * 1000).toLong(),
        )
    }

    fun parseResult(m: JSONObject): Pair<Int, List<TuneSensorResult>>? {
        if (m.optString("type") != "tune_result") return null
        val arr = m.optJSONArray("sensors") ?: JSONArray()
        val rs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TuneSensorResult(
                handle = o.optInt("handle"),
                name = o.optString("name", "Sensor"),
                ok = o.optBoolean("ok", false),
                summary = if (o.isNull("summary")) null else o.optString("summary"),
                message = if (o.isNull("message")) null else o.optString("message"),
            )
        }
        return m.optInt("id") to rs
    }

    fun window(id: Int, windows: List<TuneWindow>): JSONObject {
        val arr = JSONArray()
        for (w in windows) {
            val o = JSONObject().put("handle", w.handle).put("from_ns", w.fromNs).put("to_ns", w.toNs)
            if (w.stillFromNs != null && w.stillToNs != null) o.put("still_from_ns", w.stillFromNs).put("still_to_ns", w.stillToNs)
            arr.put(o)
        }
        return JSONObject().put("type", "tune_window").put("id", id).put("windows", arr)
    }

    fun cancelled(id: Int): JSONObject = JSONObject().put("type", "tune_window").put("id", id).put("cancelled", true)

    fun request(): JSONObject = JSONObject().put("type", "tune_request")
}
