package app.cpapmaskguard.core

/**
 * MiniMax / OpenAI 兼容响应里的 usage。没有该字段时两个数都是 -1。
 * prompt_tokens 优先于 input_tokens，completion_tokens 优先于 output_tokens。
 */
object CloudUsage {
    data class Tokens(
        val promptTokens: Int = -1,
        val completionTokens: Int = -1,
    ) {
        val known: Boolean get() = promptTokens >= 0 || completionTokens >= 0
    }

    private val usageBlock = Regex(""""usage"\s*:\s*\{([^{}]*)\}""")

    fun parse(json: String): Tokens {
        val block = usageBlock.find(json)?.groupValues?.getOrNull(1) ?: return Tokens()
        val prompt = field(block, "prompt_tokens") ?: field(block, "input_tokens") ?: -1
        val completion = field(block, "completion_tokens") ?: field(block, "output_tokens") ?: -1
        return Tokens(prompt, completion)
    }

    private fun field(block: String, name: String): Int? {
        val match = Regex(""""$name"\s*:\s*(-?\d+)""").find(block) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }
}
