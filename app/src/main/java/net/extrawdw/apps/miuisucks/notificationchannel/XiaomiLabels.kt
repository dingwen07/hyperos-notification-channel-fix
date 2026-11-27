package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.ListFormatter
import android.icu.util.ULocale
import android.os.LocaleList
import androidx.core.os.LocaleListCompat
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

internal fun Resources.supportedSystemLanguageNames(): String {
    val displayLocale = ULocale.forLocale(configuration.locales[0])
    val names = getStringArray(R.array.matching_locale_tags).map { tag ->
        ULocale.forLanguageTag(tag).getDisplayNameWithDialect(displayLocale)
    }
    return ListFormatter.getInstance(displayLocale).format(names)
}

/** Android selects the resources; its language/script matcher rejects cross-language fallback. */
internal class XiaomiLabels(app: Context, systemLocale: Locale=Resources.getSystem().configuration.locales[0]) {
    private val localized=app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList(systemLocale)) })
    private val installed=mutableMapOf<String,String>()
    private val settingsResources: Resources
    init {
        val selected=localized.getString(R.string.matching_locale_tag)
        if(selected=="unsupported" || !LocaleListCompat.matchesLanguageAndScript(Locale.forLanguageTag(selected),systemLocale))
            fail(R.string.unsupported_locale,systemLocale.toLanguageTag())
        val settings=app.createPackageContext("com.android.settings",0)
        val vendor=settings.createConfigurationContext(Configuration(settings.resources.configuration).apply {
            setLocales(LocaleList(systemLocale))
        }).resources
        settingsResources = vendor
        // This belongs to the installed Xiaomi Settings APK; its resource ID cannot be known at build time.
        @SuppressLint("DiscouragedApi")
        val xmlId=vendor.getIdentifier("miui_channel_notification_settings","xml","com.android.settings")
        if(xmlId!=0) vendor.getXml(xmlId).use { xml ->
            while(xml.next()!=XmlPullParser.END_DOCUMENT) {
                if(xml.eventType!=XmlPullParser.START_TAG) continue
                val ns="http://schemas.android.com/apk/res/android"
                val key=xml.getAttributeValue(ns,"key")
                val title=xml.getAttributeResourceValue(ns,"title",0)
                if(key!=null && title!=0) installed[key]=normalize(vendor.getString(title))
            }
        }
    }
    @SuppressLint("DiscouragedApi")
    fun settingsString(name: String): String {
        val id = settingsResources.getIdentifier(name, "string", "com.android.settings")
        if (id == 0) fail(R.string.error_policy_format)
        return normalize(settingsResources.getString(id))
    }
    fun preference(key: String,fallback: Int)=installed[key]?:text(fallback)
    fun text(id: Int)=normalize(localized.getString(id))
    companion object {
        fun normalize(s: CharSequence?)=s?.toString()?.replace("\uFEFF","")?.replace("\u200B","")?.trim().orEmpty()
    }
}
