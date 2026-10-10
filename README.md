# HyperOS notification channel repair

Android app that uses Shizuku to read Android notification channels and operate Xiaomi Settings to restore notification popups, sound, vibration, and lock-screen display.

Installation requires Xiaomi's `com.miui.system` shared library, enforced by the APK manifest.

The interface uses Kotlin, Jetpack Compose, and Material 3. The UI and Settings matching resources include English, Simplified Chinese, and Traditional Chinese. App language and system Settings language are resolved independently by Android. Matching labels were extracted from the installed Xiaomi packages; unsupported system languages produce an explicit error.

## Use

1. Start Shizuku in ADB mode, open Channel Repair, and grant Shizuku access.
2. Set Xiaomi **Lock-screen notifications → Display rules → Show notification content** before repair. Other global choices remove channel-level options needed by the automation. The app checks both underlying secure settings before preview and again before each app/channel operation. It stops with a shortcut to Xiaomi's settings when the prerequisite is unmet; it never changes global privacy itself. You can restore your preferred global privacy choice afterward.
3. Scan apps, select the apps to repair, and review their channel plans.
4. Start repair and allow the progress notification. Keep the phone unlocked and avoid interacting with Settings while it runs.
5. The app returns automatically after completion, an error, or Stop, showing the result after channel cleanup. If Shizuku disconnects and Android blocks the return, a result notification remains available to reopen the app. The ongoing notification also provides a Stop action.

Xiaomi's sound picker may refuse to apply a sound while silent mode is enabled without Bluetooth headphones. When the phone is in silent or vibrate mode, the repair confirmation includes a highlighted warning and a **Start anyway** button. It checks only ringer mode, without probing Bluetooth audio. Disable silent mode before a repair that needs sound changes. The app does not change volume, silent mode, Do Not Disturb, or global lock-screen privacy.

If an operation is interrupted and restoration cannot be verified, the app retains a private recovery journal. Reconnect Shizuku and use the recovery button before continuing. Recovery restores the saved Android fields for that channel; completed Xiaomi changes remain. Review any manual channel changes made since the interruption before using recovery.

## Mapping

| Saved Android channel state | Repair behavior |
| --- | --- |
| Importance HIGH or greater | Enable Xiaomi popup permission |
| Importance DEFAULT or greater, non-null sound URI | Enable Xiaomi sound permission |
| Importance DEFAULT or greater, vibration enabled | Enable Xiaomi vibration permission |
| Low/minimum importance | Retain Android alert fields without enabling audible/vibrating alerts |
| No channel lock-screen override (`-1000`) | Xiaomi “show notifications and content”; restore `-1000` afterward |
| Explicit PRIVATE / SECRET | Preserve hide-content / hide-notification behavior |
| Blocked app, group, channel, legacy `miscellaneous`, or channel ID starting with `mipush\|` | Skip channel repair; retain a grayed-out preview card with the exact channel ID |
| Selected app | Always enable Xiaomi's app-level launcher badge gate; never change per-channel badges |

Every preview card shows its full, selectable channel ID. All skipped channels are grayed out; MiPush channels have a specific skip reason. Android global privacy and notification visibility remain authoritative. For an explicit SECRET channel, the saved Android visibility blocks lock-screen display even if an older Xiaomi allow flag exists.

## Implementation

- `NotificationAccess`: shell-only Binder access to saved channels. The full channel parcel is captured before work; restoration updates only importance, sound/audio attributes, vibration, and visibility on the current channel object. Other fields are retained.
- `SettingsAutomation`: package-scoped accessibility-node actions through Shizuku `UiAutomation`; no separate accessibility service or fixed-coordinate automation. Preference labels come from the installed Settings XML where available. Remaining labels are localized in `xiaomi_strings.xml`.
- `XiaomiPolicy`: reads effective OEM gates from SystemUI diagnostics. The complete system dump is never saved. A successful Android field update alone is insufficient to report repair success.
- `RepairRunner`: finite foreground operation, cancellation, and a private atomic recovery journal. Stale previews are rejected. Known failures that made no change or successfully restored the channel clear the journal; unknown Binder outcomes retain it.
- Lock-screen and sound repair decisions use the saved Android fields and effective Xiaomi policy, without interpreting Settings summary text. Their selectors are opened only when Xiaomi blocks a required permission. After a sound change, Xiaomi returns to the channel page automatically; the original URI is restored through Binder and verified.

The Settings UI and privileged Binder update may mark channel fields as user-modified. The app preserves their values, but does not reset Android's internal user-lock bookkeeping.

## Build and test

Requires JDK 21, Android SDK platform 37.1, and the included Gradle wrapper. Set `sdk.dir` in an untracked `local.properties` or configure the SDK in Android Studio.

```powershell
.\gradlew.bat :app:assembleDebug :testapp:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :testapp:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
```

Compile SDK is Android 17.1 (37.1); target SDK is 37 because Android target SDK declarations do not accept a minor API level. Minimum SDK is 30, but compatibility with other ROMs is not implied.

After changing AIDL or Shizuku shell service code, use a clean build and a full Package Manager reinstall so the shell process loads the current classes.

The `testapp` target creates 12 disposable channels covering importance 0–4, custom/default/no sound, vibration, channel badges, and lock-screen privacy. Launch it after clearing its data to recreate the channels. Android ignores app-created lock-screen overrides; the **debug-only** `FixtureShell` entry point seeds PRIVATE and SECRET through shell Binder calls. It accepts only its fixed `.testapp` package. HyperOS forbids ordinary apps from creating `mipush|` channels, so MiPush coverage uses policy tests, a direct service skip test, and a sample preview card.

After installing both apps and the test APK, grant Shizuku to the main app and notification permission to the fixture. For an explicitly selected test device:

```powershell
adb -s DEVICE push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/channel-repair-debug.apk
adb -s DEVICE shell 'CLASSPATH=/data/local/tmp/channel-repair-debug.apk app_process /system/bin net.extrawdw.apps.miuisucks.notificationchannel.FixtureShell seed-privacy'
adb -s DEVICE shell am instrument -w -e repairFixture true -e class net.extrawdw.apps.miuisucks.notificationchannel.FixtureRepairTest net.extrawdw.apps.miuisucks.notificationchannel.test/androidx.test.runner.AndroidJUnitRunner
```

Device repair tests are opt-in and restricted to the fixture. They check restored Android alert fields, unchanged badges/lights/DND/group/conversation settings, effective required Xiaomi permissions, skipped-channel guards, and rejection of stale snapshots. `RepairReturnTest` checks return to the app with the correct result after success and failure. Unit tests cover channel policy and OEM policy parsing.
