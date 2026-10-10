package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.core.os.LocaleListCompat
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** Android selects the resources; its language/script matcher rejects cross-language fallback. */
internal class XiaomiLabels(app: Context, systemLocale: Locale=Resources.getSystem().configuration.locales[0]) {
    private val localized=app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList(systemLocale)) })
    private val installed=mutableMapOf<String,String>()
    init {
        val selected=localized.getString(R.string.matching_locale_tag)
        if(selected=="unsupported" || !LocaleListCompat.matchesLanguageAndScript(Locale.forLanguageTag(selected),systemLocale))
            fail(R.string.unsupported_locale,systemLocale.toLanguageTag())
        val settings=app.createPackageContext("com.android.settings",0)
        val vendor=settings.createConfigurationContext(Configuration(settings.resources.configuration).apply {
            setLocales(LocaleList(systemLocale))
        }).resources
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
    fun preference(key: String,fallback: Int)=installed[key]?:text(fallback)
    fun text(id: Int)=normalize(localized.getString(id))
    companion object {
        fun normalize(s: CharSequence?)=s?.toString()?.replace("\uFEFF","")?.replace("\u200B","")?.trim().orEmpty()
    }
}
