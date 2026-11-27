package net.extrawdw.apps.miuisucks.notificationchannel

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI-only fixtures: none of these callbacks invoke Shizuku or change notification settings. */
class RepairLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = listOf(
        AppEntry("com.example.messages", "Messages", 4, false, true),
        AppEntry("com.example.mail", "Mail", 3, false, true),
        AppEntry("com.example.calendar", "Calendar", 2, false, true),
        AppEntry("com.example.blocked", "Blocked", 2, false, false),
    )
    private fun label(id: Int) = compose.activity.getString(id)
    private fun ready() = RepairUiState(status = label(R.string.ready), connected = true, apps = apps)

    @Before fun edgeToEdgeHost() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun gridReflowsWhenWindowResizes() {
        var size by mutableStateOf(DpSize(360.dp, 800.dp))
        val state = ready()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                RepairTheme { RepairContent(state) }
            }
        }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app:com.example.messages")
        fun messages() = compose.onNode(hasText("Messages") and isToggleable()).fetchSemanticsNode().boundsInRoot
        fun mail() = compose.onNode(hasText("Mail") and isToggleable()).fetchSemanticsNode().boundsInRoot
        assertEquals(messages().left, mail().left, 1f)
        assertTrue(mail().top > messages().top)
        compose.runOnIdle { size = DpSize(1024.dp, 900.dp) }
        compose.waitForIdle()
        assertEquals(messages().top, mail().top, 1f)
        assertTrue(mail().left > messages().left)
    }

    @Test fun searchAndFilterSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        val state = ready()
        restoration.setContent { RepairTheme { RepairContent(state) } }
        compose.onNodeWithText(label(R.string.show_system)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Mail")
        compose.onNode(hasSetTextAction()).performImeAction()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("Mail")
        compose.onNodeWithText(label(R.string.show_system)).assertIsOn()
    }

    @Test fun planDefaultsToSkippingFixedChannelsAndCanIncludeThemAgain() {
        val data = JSONObject().put("id", "messages").put("name", "Messages").put("eligible", true)
            .put("importance", 3).put("popup", false).put("sound", false).put("vibrate", true)
            .put("lockscreen", true).put("soundUri", JSONObject.NULL)
        val channel = PlannedChannel(data, "key", "signature", previouslyFixed = true)
        val plan = RepairPlan("test.app", "{}", listOf(channel))
        var state by mutableStateOf(ready().copy(plans = listOf(plan)))
        compose.setContent {
            RepairTheme { RepairContent(state, RepairActions(setSkipFixed = { state = state.copy(skipFixed = it) })) }
        }
        val grid = compose.onNode(hasScrollToKeyAction())
        grid.performScrollToKey("skip_fixed")
        val option = compose.onNodeWithText(label(R.string.skip_fixed_option))
        option.assertIsOn()
        grid.performScrollToKey("channel:test.app:messages")
        compose.onNodeWithText(label(R.string.skip_fixed)).assertIsDisplayed()
        grid.performScrollToKey("skip_fixed")
        option.performClick().assertIsOff()
        grid.performScrollToKey("channel:test.app:messages")
        compose.onNodeWithText(label(R.string.skip_fixed)).assertDoesNotExist()
        compose.onNodeWithText("Messages").assertIsDisplayed()
    }

    @Test fun largeTextKeepsStopReachableInNarrowWindow() {
        var stopped = false
        val state = ready().copy(running = true, status = label(R.string.working))
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 720.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                RepairTheme { RepairContent(state, RepairActions(stop = { stopped = true })) }
            }
        }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("connection")
        compose.onNodeWithText(label(R.string.cancel)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test fun wholeCardSelectsButBlockedCardCannotBeSelected() {
        var selected by mutableStateOf(emptySet<String>())
        val state = ready()
        compose.setContent {
            RepairTheme { RepairContent(state.copy(selected = selected), RepairActions(select = { selected = it })) }
        }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app:com.example.mail")
        compose.onNode(hasText("Mail") and isToggleable()).performClick().assertIsOn()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("app:com.example.blocked")
        compose.onNode(hasText("Blocked") and isToggleable()).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(setOf("com.example.mail"), selected) }
    }

    @Test fun keyboardLeavesSearchFieldVisible() {
        val state = ready()
        compose.setContent { RepairTheme { RepairContent(state) } }
        compose.onNode(hasSetTextAction()).performScrollTo().performClick().performTextInput("Mail")
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        val keyboardTop = compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        assertTrue("Search field is covered by IME: $field, IME top=$keyboardTop", field.bottom <= keyboardTop + 1)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performImeAction()
    }
}
