package dev.hect.lauravoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    @Test fun opensSettings() {
        for (s in listOf(
            "Open settings", "Laura, open settings.", "Laura open the settings please", "Hey Laura, show me your settings",
            "Can you open settings?", "Go to settings", "Settings, please", "open settings, Laura", "take me to the settings page",
            "설정 열어", "로라, 설정 열어줘", "설정 좀 열어줘", "설정 화면 보여줘", "로라야 설정창 열어 줘", "환경설정 열어주세요", "설정으로 가자",
        )) assertEquals(s, VoiceCommands.OPEN_SETTINGS, VoiceCommands.match(s))
    }

    @Test fun answersEverythingElse() {
        for (s in listOf(
            "", "Laura", "What are the best camera settings for night photos?", "open the garage", "I changed the settings yesterday",
            "Open settings on my laptop and turn on dark mode", "설정이 뭐야", "카메라 설정 열어줘", "로라 어댑터 설정 열어줘",
        )) assertNull(s, VoiceCommands.match(s))
    }
}
