package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LocaleResourcesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun androidSelectsAllThreeUiLanguages() {
        for((tag,title) in mapOf("en-US" to "Channel Repair","zh-CN" to "通知渠道修复","zh-TW" to "通知管道修復")) {
            val localized=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocales(LocaleList.forLanguageTags(tag))})
            assertEquals(title,localized.getString(R.string.app_name))
        }
    }
    @Test fun supportedMatchingIsIndependentOfAppLanguage() {
        val english=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en-US"))})
        for((tag,label) in mapOf("zh-CN" to "悬浮通知","zh-TW" to "懸浮通知","zh-HK" to "懸浮通知","en-US" to "Floating notifications","en-GB" to "Floating notifications")) {
            val matching=XiaomiLabels(english,Locale.forLanguageTag(tag))
            assertEquals(label,matching.text(R.string.allow_float))
            assertEquals(label,matching.preference("allow_float",R.string.allow_float))
        }
    }
    @Test fun unsupportedSystemLanguagesDoNotFallBackToAnotherLanguage() {
        for(tag in listOf("bo-CN","ug-CN","fr-FR","de-DE")) {
            try {XiaomiLabels(context,Locale.forLanguageTag(tag));fail("Unexpected matching support for $tag")}
            catch(e: RepairFailure) {assertEquals(R.string.unsupported_locale,e.messageId)}
        }
    }
    @Test fun matchingStringsAgreeWithInstalledXiaomiPackages() {
        val sources=mapOf(
            "com.android.settings" to mapOf(
                R.string.allow_float to "allow_float_title",R.string.ringtone to "sound_title",
                R.string.vibrate to "vibrate_title",R.string.visibility_override to "visibility_override_title",
                R.string.allow_keyguard to "allow_keyguard_title",R.string.allow_sound to "allow_sound_title",
                R.string.setting_badge to "badge_title"),
            "com.miui.notification" to mapOf(
                R.string.lockscreen_show to "channel_keyguard_show_content",
                R.string.lockscreen_hide to "channel_keyguard_hide_content",
                R.string.lockscreen_none to "channel_keyguard_show_nothing"),
            "com.android.thememanager" to mapOf(
                R.string.sound_default_title to "resource_notification_default",
                R.string.sound_none to "resource_mute",R.string.sound_apply to "resource_apply"),
        )
        for(tag in listOf("zh-CN","zh-TW","zh-HK","en-US","en-GB")) {
            val labels=XiaomiLabels(context,Locale.forLanguageTag(tag))
            for((pkg,strings) in sources) {
                val vendor=context.createPackageContext(pkg,0)
                val resources=vendor.createConfigurationContext(Configuration(vendor.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }).resources
                for((localId,name) in strings) {
                    val vendorId=resources.getIdentifier(name,"string",pkg)
                    assertNotEquals("$pkg:$name is missing",0,vendorId)
                    assertEquals("$tag $pkg:$name",XiaomiLabels.normalize(resources.getString(vendorId)),labels.text(localId))
                }
                if(pkg=="com.android.thememanager") {
                    val formatted=resources.getString(resources.getIdentifier("resource_notification_default_with_brackets","string",pkg),"Test tone")
                    assertTrue("$tag default sound: $formatted",XiaomiLabels.normalize(formatted).startsWith(labels.text(R.string.sound_default_prefix)))
                }
            }
        }
    }
}
