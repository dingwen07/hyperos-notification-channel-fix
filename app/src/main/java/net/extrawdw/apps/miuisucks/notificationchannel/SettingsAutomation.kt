package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.*
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Bounded, package-scoped node actions. No fixed coordinates or blind taps. */
internal class SettingsAutomation(private val context: Context,private val hostId: Int,private val cancelled: () -> Boolean) : AutoCloseable {
    val labels=XiaomiLabels(context)
    private val session=ShellUiAutomation()
    private val ui=session.ui
    private var expectedPackage="com.android.settings"
    private var settingsTitle=""
    private fun checkCancelled() { if(cancelled()) fail(R.string.stopped) }
    private fun root(): AccessibilityNodeInfo {
        checkCancelled()
        val root=ui.rootInActiveWindow
        if(root==null || root.packageName?.toString()!=expectedPackage) fail(R.string.error_unexpected_page)
        return root
    }
    private fun awaitPackage(pkg: String) {
        expectedPackage=pkg
        val end=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<end) {
            checkCancelled()
            if(ui.rootInActiveWindow?.packageName?.toString()==pkg) { SystemClock.sleep(200);return }
            SystemClock.sleep(100)
        }
        fail(R.string.error_page_timeout,pkg)
    }
    fun open(pkg: String,id: String?,title: String,uid: Int,userId: Int) {
        checkCancelled()
        // The foreground full user owns the effective Xiaomi gates. app_uid selects the Android copy.
        val intent = Intent(if (id == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .putExtra("app_uid", uid)
        if (userId == 999) intent.putExtra("userId", userId)
        if (id != null) intent.setClassName("com.android.settings", "com.android.settings.notification.app.ChannelPanelActivity")
            .putExtra(Settings.EXTRA_CHANNEL_ID, id)
        ShellFramework.startActivity(intent, hostId)
        awaitPackage("com.android.settings")
        settingsTitle=title
        awaitSettingsTitle()
    }
    private fun awaitSettingsTitle() {
        val end=SystemClock.uptimeMillis()+5000
        while(SystemClock.uptimeMillis()<end) {
            checkCancelled()
            val page=ui.rootInActiveWindow
            if(page?.packageName?.toString()=="com.android.settings") {
                val found=listOf("com.android.settings:id/action_bar_title_expand","com.android.settings:id/action_bar_title",
                    // HyperOS 4 uses a medium-sized action-bar heading.
                    "com.android.settings:id/action_bar_title_medium")
                    .flatMap { page.findAccessibilityNodeInfosByViewId(it) }.any { XiaomiLabels.normalize(it.text)==settingsTitle }
                if(found)return
            }
            SystemClock.sleep(100)
        }
        fail(R.string.error_page_title)
    }
    private fun titles(text: String,prefix: Boolean)=root().findAccessibilityNodeInfosByViewId("android:id/title").filter {
        val actual=XiaomiLabels.normalize(it.text)
        it.isVisibleToUser && if(prefix)actual.startsWith(text) else actual==text
    }
    private fun title(text: String,prefix: Boolean=false,alternative: String?=null): AccessibilityNodeInfo {
        repeat(7) { attempt ->
            val found=(titles(text,prefix)+(alternative?.let {titles(it,false)}?:emptyList())).distinct()
            if(found.size==1)return found.single()
            if(found.size>1)fail(R.string.error_ambiguous_control,text)
            if(attempt<3)SystemClock.sleep(180) else if(!scroll(root()))fail(R.string.error_missing_control,text)
        }
        fail(R.string.error_missing_control,text)
    }
    private fun scroll(n: AccessibilityNodeInfo): Boolean {
        if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { SystemClock.sleep(250);return true }
        return (0 until n.childCount).any { n.getChild(it)?.let(::scroll)==true }
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo?=node
        repeat(6) {
            val current=n ?: fail(R.string.error_disabled_control)
            if(current.isClickable&&current.isEnabled)return current
            n=current.parent
        }
        fail(R.string.error_disabled_control)
    }
    private fun click(n: AccessibilityNodeInfo) {
        root()
        if(!n.refresh() || !n.isVisibleToUser || !n.isEnabled)fail(R.string.error_stale_control)
        if(!clickable(n).performAction(AccessibilityNodeInfo.ACTION_CLICK))fail(R.string.error_click)
        SystemClock.sleep(250)
    }
    private fun checkable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if(n.isCheckable)return n
        for(i in 0 until n.childCount) n.getChild(i)?.let { checkable(it)?.let { found ->return found } }
        return null
    }
    private fun optionState(title: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo?=title
        repeat(5) {
            val current=node?:fail(R.string.error_lock_option)
            checkable(current)?.let {return it}
            node=current.parent
        }
        fail(R.string.error_lock_option)
    }
    @Suppress("DEPRECATION") // isChecked is needed only on Android 11-15.
    private fun AccessibilityNodeInfo.matchesCheckedState(desired: Boolean): Boolean =
        if(Build.VERSION.SDK_INT>=36) {
            // A partially checked node does not satisfy either binary setting.
            checked==if(desired)AccessibilityNodeInfo.CHECKED_STATE_TRUE else AccessibilityNodeInfo.CHECKED_STATE_FALSE
        } else isChecked==desired
    // The secondary user's Settings queries its own SystemUI provider when this page opens.
    fun checked(label: String): Boolean =
        checkable(clickable(title(label)))?.matchesCheckedState(true) ?: fail(R.string.error_not_switch,label)

    private fun rowValue(label: String, viewId: String): String {
        val row = clickable(title(label))
        repeat(10) {
            val values = row.findAccessibilityNodeInfosByViewId(viewId).filter { it.isVisibleToUser }
            if (values.size == 1) {
                val text = XiaomiLabels.normalize(values.single().text)
                if (text.isNotBlank()) return text
            }
            SystemClock.sleep(100)
            row.refresh()
        }
        fail(R.string.error_policy_format)
    }

    fun lockscreenAllowed(): Boolean {
        val value = rowValue(labels.preference("visibility_override", R.string.visibility_override), "com.android.settings:id/text_right")
        return when (value) {
            labels.settingsString("lock_screen_notifications_summary_show"),
            labels.settingsString("lock_screen_notifications_summary_hide") -> true
            labels.settingsString("lock_screen_notifications_summary_disable") -> false
            else -> fail(R.string.error_policy_format)
        }
    }

    @android.annotation.SuppressLint("PrivateApi") // Use the same vendor formatter as the installed Settings.
    fun soundAllowed(): Boolean {
        val title = rowValue(labels.preference("ringtone", R.string.ringtone), "android:id/summary")
        // MiuiNotificationSoundPreference renders ExtraRingtone.getRingtoneTitle(null)
        // when the OEM gate is closed, independently of the Android channel's saved URI.
        val silent = Class.forName("android.media.ExtraRingtone").getMethod("getRingtoneTitle",
            Context::class.java, android.net.Uri::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, context, null, false) as String
        return title != XiaomiLabels.normalize(silent)
    }

    fun toggle(label: String,desired: Boolean) {
        val row=clickable(title(label));val box=checkable(row)?:fail(R.string.error_not_switch,label)
        if(box.matchesCheckedState(desired))return
        click(row)
        if(checkable(clickable(title(label)))?.matchesCheckedState(desired)!=true)fail(R.string.error_switch_not_saved,label)
    }
    fun lockscreen(visibility: Int) {
        val label=when(visibility) { -1->R.string.lockscreen_none;0->R.string.lockscreen_hide;else->R.string.lockscreen_show }
        click(title(labels.preference("visibility_override",R.string.visibility_override)))
        awaitPackage("com.miui.notification")
        val option=title(labels.text(label))
        if(!optionState(option).matchesCheckedState(true))click(option)
        // HyperOS intentionally makes the selected radio row non-clickable.
        if(!optionState(title(labels.text(label))).matchesCheckedState(true))fail(R.string.error_lock_not_saved)
        back();awaitPackage("com.android.settings");awaitSettingsTitle()
    }
    fun sound(enabled: Boolean) {
        click(title(labels.preference("ringtone",R.string.ringtone)))
        awaitPackage("com.android.thememanager")
        click(title(labels.text(if(enabled)R.string.sound_default_prefix else R.string.sound_none),enabled,
            if(enabled)labels.text(R.string.sound_default_title) else null))
        SystemClock.sleep(400)
        if(ui.rootInActiveWindow?.packageName?.toString()=="com.android.settings") {
            awaitPackage("com.android.settings");awaitSettingsTitle();return
        }
        val apply=root().findAccessibilityNodeInfosByViewId("com.android.thememanager:id/apply").filter { it.isVisibleToUser }
        if(apply.size==1)click(apply.single()) else {
            val choices=root().findAccessibilityNodeInfosByText(labels.text(R.string.sound_apply)).filter {
                XiaomiLabels.normalize(it.text)==labels.text(R.string.sound_apply)&&it.isVisibleToUser
            }
            if(choices.size==1)click(choices.single())
        }
        // Xiaomi finishes the picker after Apply. Wait for that transition without sending Back.
        awaitPackage("com.android.settings");awaitSettingsTitle()
    }
    private fun back() {
        root();val now=SystemClock.uptimeMillis()
        if(!ui.injectInputEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true)||
            !ui.injectInputEvent(KeyEvent(now,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true))fail(R.string.error_back)
        SystemClock.sleep(250)
    }
    override fun close()=session.close()
}
