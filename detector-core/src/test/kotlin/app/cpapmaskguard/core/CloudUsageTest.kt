package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudUsageTest {
    @Test
    fun parsesPromptAndCompletionTokens() {
        val json = """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}"""
        val usage = CloudUsage.parse(json)
        assertEquals(120, usage.promptTokens)
        assertEquals(4, usage.completionTokens)
        assertTrue(usage.known)
    }

    @Test
    fun acceptsInputAndOutputAliases() {
        val json = """{"usage":{"input_tokens":80,"output_tokens":2}}"""
        val usage = CloudUsage.parse(json)
        assertEquals(80, usage.promptTokens)
        assertEquals(2, usage.completionTokens)
    }

    @Test
    fun prefersCanonicalNamesOverAliases() {
        val json = """{"usage":{"prompt_tokens":10,"input_tokens":99,"completion_tokens":3,"output_tokens":8}}"""
        val usage = CloudUsage.parse(json)
        assertEquals(10, usage.promptTokens)
        assertEquals(3, usage.completionTokens)
    }

    @Test
    fun missingUsageIsUnknown() {
        val usage = CloudUsage.parse("""{"choices":[{"message":{"content":"MASK_ON"}}]}""")
        assertEquals(-1, usage.promptTokens)
        assertEquals(-1, usage.completionTokens)
        assertFalse(usage.known)
    }
}
