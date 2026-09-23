package com.zszc.voicepipeline.llm

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SparkHttpRequestBuilderTest {
    @Test
    fun `system message uses the demo assistant name`() {
        val request = SparkHttpRequestBuilder.build(
            userText = "你好",
            history = emptyList(),
            model = "test-model",
        )

        val messages = JsonParser.parseString(request)
            .asJsonObject
            .getAsJsonArray("messages")
        val systemMessage = messages[0].asJsonObject

        assertEquals("system", systemMessage.get("role").asString)
        assertEquals(
            "你是桌面机器人语音助手小飞。回答要简短、自然、适合 TTS 播报，通常不超过 80 个中文字。" +
                "你可以陪用户聊天、解释问题、给出建议。涉及打开应用、系统设置、天气等设备动作时，说明你会尽力处理；不要编造已经执行了本地代码没有执行的动作。",
            systemMessage.get("content").asString,
        )
    }
}
