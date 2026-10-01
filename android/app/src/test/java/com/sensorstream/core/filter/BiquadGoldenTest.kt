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
            val chain = FilterChain(cfg, fs, 1)
            for (i in 0 until x.length()) {
                val out = chain.process(doubleArrayOf(x.getDouble(i)))[0]
                assertEquals("case $c sample $i", y.getDouble(i), out, 1e-9)
            }
        }
    }
}
