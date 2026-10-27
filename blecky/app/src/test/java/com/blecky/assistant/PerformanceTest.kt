package com.blecky.assistant

import com.blecky.assistant.voice.VoiceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class PerformanceTest {
    private val random = Random(1)
    private fun hiss() = ShortArray(1600) { (random.nextGaussian() * 120).toInt().toShort() }
    private fun voice() = ShortArray(1600) { (sin(it * 0.12) * 4000).toInt().toShort() }
    private fun Random.nextGaussian() = (1..6).sumOf { nextDouble() } - 3.0

    @Test
    fun `silence never reaches the recognizer`() {
        val gate = VoiceGate()
        val fed = (1..300).sumOf { gate.process(hiss()).size } // 30 seconds of a quiet room
        assertEquals(0, fed)
    }

    @Test
    fun `speech is passed on with pre-roll and a trailing tail`() {
        val gate = VoiceGate()
        repeat(20) { gate.process(hiss()) }
        val first = gate.process(voice())
        assertEquals("three chunks of pre-roll plus the speech", 4, first.size)
        repeat(5) { assertEquals(1, gate.process(voice()).size) }
        val tail = (1..15).map { gate.process(hiss()).size }
        assertEquals("one second of trailing quiet, then nothing", 10, tail.sum())
        assertTrue(tail.takeLast(5).all { it == 0 })
    }

    @Test
    fun `steady background noise raises the bar`() {
        val gate = VoiceGate()
        val fan = { ShortArray(1600) { (random.nextGaussian() * 200).toInt().toShort() } }
        repeat(100) { gate.process(fan()) }
        assertEquals(0, (1..50).sumOf { gate.process(fan()).size })
        assertTrue(gate.process(voice()).isNotEmpty())
    }
}
