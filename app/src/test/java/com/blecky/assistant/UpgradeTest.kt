package com.blecky.assistant

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaToolUnion
import com.blecky.assistant.brain.ClaudeEngine
import com.blecky.assistant.tools.Contact
import com.blecky.assistant.tools.NameMatch
import com.blecky.assistant.tools.allTools
import com.blecky.assistant.tools.closestContacts
import com.blecky.assistant.tools.pickSim
import com.blecky.assistant.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeTest {
    private fun body(fallbacks: Boolean): String {
        val tools = allTools().map { BetaToolUnion.ofBetaTool(ClaudeEngine.toBetaTool(it)) }
        val params = ClaudeEngine.requestParams("claude-opus-5-5", "system", tools, listOf(BetaMessageParam.ofUser("hi")), fallbacks)
        return jsonMapper().writeValueAsString(params._body())
    }

    @Test
    fun `request has no empty required lists and asks for default fallbacks`() {
        val json = body(fallbacks = true)
        assertFalse(json.contains("\"required\":[]"))
        assertTrue(json.contains("\"fallbacks\":\"default\""))
        assertTrue(json.contains("\"effort\":\"low\""))
        assertTrue(json.contains("\"name\":\"call\""))
        assertFalse(body(fallbacks = false).contains("fallbacks"))
    }

    @Test
    fun `server errors are explained in plain words`() {
        assertTrue(ClaudeEngine.explainServerError(400, "Your credit balance is too low to access the Anthropic API.").contains("out of credit"))
        assertTrue(ClaudeEngine.explainServerError(404, "model: claude-x not found").contains("model"))
        assertTrue(ClaudeEngine.explainServerError(400, "tools.0: bad schema").contains("tools.0: bad schema"))
        assertTrue(ClaudeEngine.explainServerError(529, "Overloaded").contains("busy"))
    }

    @Test
    fun `misheard names still find the contact`() {
        assertTrue(NameMatch.score("jash", "Jayesh Patel") >= 0.8)
        assertTrue(NameMatch.score("jayesh", "Jayesh") > 0.99)
        assertTrue(NameMatch.score("mom", "Rahul") < 0.6)
        val contacts = listOf(Contact("Jayesh Patel", "1"), Contact("Rahul", "2"), Contact("Priya", "3"))
        assertEquals(listOf("Jayesh Patel"), closestContacts("jash", contacts).map { it.name })
        assertTrue(closestContacts("zzz", contacts).isEmpty())
    }

    @Test
    fun `picks the named sim`() {
        val sims = listOf("Jio 4G", "airtel")
        assertEquals(0, pickSim(sims, "jio"))
        assertEquals(0, pickSim(sims, "Jio sim"))
        assertEquals(1, pickSim(sims, "Airtel"))
        assertEquals(1, pickSim(sims, "sim 2"))
        assertEquals(1, pickSim(listOf("SIM 1", "SIM 2"), "sim 2"))
        assertEquals(0, pickSim(sims, "first"))
        assertNull(pickSim(sims, "vodafone"))
    }

    @Test
    fun `replies are cleaned up for speaking`() {
        assertEquals("Done! Here's the link", SpeechText.clean("**Done!** 🎉 Here's https://example.com"))
        assertEquals("milk eggs", SpeechText.clean("- milk\n- eggs"))
    }
}
