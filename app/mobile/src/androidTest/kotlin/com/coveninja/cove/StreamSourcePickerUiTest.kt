package com.coveninja.cove

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.components.player.StreamSourcePicker
import com.coveninja.cove.ui.state.StreamChoice
import com.coveninja.cove.ui.state.StreamCompatibility
import com.coveninja.cove.ui.state.VideoDecoderSupport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StreamSourcePickerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compatibilityWarningsMatchWhetherRowsCanBeSelected() {
        var selected: StreamChoice? = null
        val hardware = choice("Hardware", VideoDecoderSupport.Hardware)
        val software = choice("Software", VideoDecoderSupport.SoftwareOnly)
        val unsupported = choice("Unsupported", VideoDecoderSupport.Unsupported)
        compose.setContent {
            CoveTheme {
                StreamSourcePicker(
                    sources = listOf(hardware, software, unsupported),
                    onSelect = { selected = it },
                )
            }
        }

        compose.onNodeWithText("Hardware").assertIsEnabled()
        compose.onNodeWithText("BEST").assertExists()
        compose.onNodeWithText("Software decoding only · playback may stutter").assertExists()
        compose.onNodeWithText("Unsupported video codec on this device").assertExists()
        compose.onNodeWithText("Unsupported").assertIsNotEnabled()

        compose.onNodeWithText("Software").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(software, selected) }
    }

    private fun choice(name: String, support: VideoDecoderSupport): StreamChoice = StreamChoice(
        source = StreamSource(
            name = name,
            title = name,
            url = "https://example.com/${name.lowercase()}.mkv",
        ),
        compatibility = StreamCompatibility(codecLabel = "Test codec", support = support),
    )
}
