package com.coveninja.cove

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MobileNavigationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun showApp() {
        compose.setContent {
            CoveTheme {
                CoveApp(
                    graph = FixtureAppGraph(),
                    navBarPlacement = NavBarPlacement.Bottom,
                )
            }
        }
    }

    @Test
    fun everyNavigationTabIsNamedReachableAndSelectable() {
        listOf("Home", "My List", "Explore", "Search", "Profile").forEach { label ->
            compose.onNodeWithContentDescription(label)
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }

        compose.onNodeWithContentDescription("Home").assertIsSelected()
        compose.onNodeWithContentDescription("My List").performClick()
        compose.onNodeWithContentDescription("My List").assertIsSelected()
    }

    @Test
    fun navigationSearchCanBeClosedWithoutSubmitting() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Close search")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test
    fun androidBackClosesTransientUiBeforeLeavingTheApp() {
        compose.onNodeWithContentDescription("Search").performClick()
        pressAndroidBack()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithContentDescription("Close search").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Home").assertIsSelected()

        compose.onNodeWithContentDescription("Explore").performClick()
        pressAndroidBack()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onNodeWithContentDescription("Home").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Selected
            ]
        }
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test
    fun androidBackReturnsFromAProfileSettingsCategoryBeforeLeavingProfile() {
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Account and sync").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Where streams come from and how they are configured")
                .fetchSemanticsNodes().isEmpty()
        }

        pressAndroidBack()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithText("Where streams come from and how they are configured")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profile").assertIsSelected()
    }

    private fun pressAndroidBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK")
            .close()
    }
}
