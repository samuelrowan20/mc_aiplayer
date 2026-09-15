package io.github.zoyluo.aibot;

import io.github.zoyluo.aibot.brain.DeepSeekApiClient;
import io.github.zoyluo.aibot.brain.DeepSeekApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroqLegacyBudgetTest {
    @Test
    void legacyGroqCallsFailBeforeSendingHistoryOrSpendingQuota() {
        var config = new AIBotConfig.DeepSeek("", "https://api.groq.com/openai/v1",
                "qwen/qwen3.8-27b", 1024, 0.3D, 60, 0, 500, false, "low");
        var error = assertThrows(DeepSeekApiException.class,
                () -> new DeepSeekApiClient(config).chat(List.of(), List.of()));
        assertTrue(error.getMessage().startsWith("groq_requires_budgeted_autonomy"));
    }
}
