package com.sensorstream.core.filter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BiquadGoldenTest {
    @Test
    fun matchesTheLaptopFilterOutputs() {
        val text = javaClass.classLoader!!.getResource("filter_golden.json")!!.readText()
        val root = JSONObject(text)
        val fs = root.getDouble("fs")
        val cases = root.getJSONArray("cases")
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val cfg = FilterConfigCodec.parse(case.getJSONObject("config"))!!
            val x = case.getJSONArray("x")
            val y = case.getJSONArray("y")
            if (case.optBoolean("axes3")) {
                // Per-axis filter: three different filters on three columns.
                val chain3 = FilterChain(cfg, fs, 3)
                for (i in 0 until x.length()) {
                    val xi = x.getJSONArray(i)
                    val out = chain3.process(DoubleArray(3) { xi.getDouble(it) })
                    for (a in 0 until 3) assertEquals("case $c sample $i axis $a", y.getJSONArray(i).getDouble(a), out[a], 1e-9)
                }
                continue
            }
            val chain = FilterChain(cfg, fs, 1)
            for (i in 0 until x.length()) {
                val out = chain.process(doubleArrayOf(x.getDouble(i)))[0]
                assertEquals("case $c sample $i", y.getDouble(i), out, 1e-9)
            }
        }
    }
}
