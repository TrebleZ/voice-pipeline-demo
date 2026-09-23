package com.zszc.voicepipeline

import com.zszc.voicepipeline.VoicePipelineDemoPhase
import com.zszc.voicepipeline.VoicePipelineDemoStateMachine
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePipelineDemoStateMachineTest {
    @Test
    fun `partial transcript flows into spark thinking and tts speaking states`() {
        val machine = VoicePipelineDemoStateMachine()

        val listening = machine.listening("今天天气怎么样")
        val thinking = machine.thinking("今天天气怎么样")
        val speaking = machine.speaking("今天适合出门散步")

        assertEquals(VoicePipelineDemoPhase.LISTENING, listening.phase)
        assertEquals("今天天气怎么样", listening.transcript)
        assertEquals(VoicePipelineDemoPhase.THINKING, thinking.phase)
        assertEquals("今天天气怎么样", thinking.transcript)
        assertEquals(VoicePipelineDemoPhase.SPEAKING, speaking.phase)
        assertEquals("今天天气怎么样", speaking.transcript)
        assertEquals("今天适合出门散步", speaking.modelReply)
    }

    @Test
    fun `standby clears the previous turn text`() {
        val machine = VoicePipelineDemoStateMachine()

        machine.listening("上一轮问题")
        machine.speaking("上一轮回复")
        val standby = machine.standby()

        assertEquals(VoicePipelineDemoPhase.STANDBY, standby.phase)
        assertEquals("", standby.transcript)
        assertEquals("", standby.modelReply)
    }

    @Test
    fun `standby tells the user to say the configured wake phrase`() {
        val standby = VoicePipelineDemoStateMachine().standby()

        assertEquals("请说“小飞小飞”", standby.detail)
    }
}
