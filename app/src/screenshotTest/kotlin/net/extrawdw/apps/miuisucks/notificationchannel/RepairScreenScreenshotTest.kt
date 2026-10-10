package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@Preview(name = "Phone", widthDp = 360, heightDp = 800)
@Preview(name = "Narrow window", widthDp = 320, heightDp = 640)
@Preview(name = "Landscape", widthDp = 800, heightDp = 360)
@Preview(name = "Foldable", widthDp = 700, heightDp = 840)
@Preview(name = "Tablet", widthDp = 1024, heightDp = 900)
@Preview(name = "Desktop", widthDp = 1440, heightDp = 900)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f)
@Preview(name = "Dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Chinese", widthDp = 360, heightDp = 800, locale = "zh-rCN")
annotation class AdaptivePreviews

private val sampleApps = listOf(
    AppEntry("com.example.messages", "Messages", 4, false, true),
    AppEntry("com.example.calendar", "Calendar", 3, false, true),
    AppEntry("com.example.long.packagename.notifications", "An application with a longer display name", 12, false, true),
    AppEntry("com.example.mail", "Mail", 5, false, true),
    AppEntry("com.example.blocked", "Blocked notifications", 2, false, false),
    AppEntry("com.example.notes", "Notes", 1, false, true),
)

@PreviewTest
@AdaptivePreviews
@Composable
fun AppSelectionPreview() {
    RepairTheme {
        RepairContent(RepairUiState(
            status = stringResource(R.string.ready),
            connected = true,
            apps = sampleApps,
            selected = setOf(sampleApps.first().pkg),
        ))
    }
}

@PreviewTest
@Preview(name = "Review phone", widthDp = 360, heightDp = 800)
@Preview(name = "Review tablet", widthDp = 1024, heightDp = 900)
@Composable
fun ChannelReviewPreview() {
    RepairTheme {
        RepairContent(RepairUiState(
            status = stringResource(R.string.ready), connected = true,
            plans = listOf("""{"package":"com.example.messages","channels":[
                {"id":"messages","name":"Incoming messages","eligible":true,"importance":4,"popup":true,"sound":true,"vibrate":true,"lockscreen":true,"soundUri":"android.resource://com.example.messages/raw/message"},
                {"id":"calls","name":"Calls","eligible":true,"importance":4,"popup":true,"sound":true,"vibrate":true,"lockscreen":true,"soundUri":null},
                {"id":"blocked","name":"Blocked channel","eligible":false},
                {"id":"mipush|com.example.messages|news","name":"MiPush channel","eligible":false}
            ]}"""),
        ))
    }
}

@PreviewTest
@Preview(name = "Running large text", widthDp = 320, heightDp = 720, fontScale = 2f)
@Composable
fun RunningPreview() {
    RepairTheme {
        RepairContent(RepairUiState(status = stringResource(R.string.working), connected = true, running = true))
    }
}
