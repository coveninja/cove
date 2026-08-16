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
        val hardware = choice("Hardware", VideoDecoderSupport.Hardware, seeders = 109)
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
        // Both are read off the provider's second title line, which
        // displayLabel drops, and both land in the right-hand stat lane.
        compose.onNodeWithText("109").assertExists()
        compose.onNodeWithText("1.4 GB").assertExists()
        compose.onNodeWithText("Software decoding only · playback may stutter").assertExists()
        compose.onNodeWithText("Unsupported video codec on this device").assertExists()
        compose.onNodeWithText("Unsupported").assertIsNotEnabled()

        compose.onNodeWithText("Software").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(software, selected) }
    }

    private fun choice(
        name: String,
        support: VideoDecoderSupport,
        seeders: Int? = null,
    ): StreamChoice = StreamChoice(
        source = StreamSource(
            name = name,
            // Shaped like a real provider answer: the release name on the first
            // line, the peer/size summary on the second. The size arrives as a
            // field because AddonManager parses that line server-side; the peer
            // count has no field to arrive in, so the row reads it from here.
            title = seeders?.let { "$name\n👤 $it 💾 1.4 GB ⚙️ Example" } ?: name,
            url = "https://example.com/${name.lowercase()}.mkv",
            sizeBytes = if (seeders == null) 0 else (1.4 * (1L shl 30)).toLong(),
        ),
        compatibility = StreamCompatibility(codecLabel = "Test codec", support = support),
    )
}
