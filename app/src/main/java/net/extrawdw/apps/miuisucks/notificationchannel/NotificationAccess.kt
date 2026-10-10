package net.extrawdw.apps.miuisucks.notificationchannel

import android.app.*
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Hidden framework interfaces stay in the shell UserService. */
internal class NotificationAccess(val context: Context) {
    private val type=Class.forName("android.app.INotificationManager")
    private val service: Any
    init {
        check(android.os.Process.myUid() in setOf(0,2000)) { "Shizuku shell or root required" }
        if(ActivityManager::class.java.getMethod("getCurrentUser").invoke(null)!=0) fail(R.string.error_owner_user)
        val binder=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,"notification")
        service=Class.forName("android.app.INotificationManager\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,binder)!!
    }
    private fun call(name: String,vararg args: Any?): Any? {
        val method=type.methods.single { it.name==name && it.parameterCount==args.size }
        return method.invoke(service,*args)
    }
    fun enabled(pkg: String,uid: Int)=call("areNotificationsEnabledForPackage",pkg,uid) as Boolean
    fun uid(pkg: String)=context.packageManager.getApplicationInfo(pkg,0).uid
    @Suppress("UNCHECKED_CAST")
    fun channels(pkg: String,uid: Int): List<NotificationChannel> {
        val slice=call("getNotificationChannelsForPackage",pkg,uid,false)!!
        return slice.javaClass.getMethod("getList").invoke(slice) as List<NotificationChannel>
    }
    fun channel(pkg: String,id: String): NotificationChannel =
        call("getNotificationChannel","com.android.shell",0,pkg,id) as? NotificationChannel ?: fail(R.string.error_channel_missing)
    private fun update(pkg: String,uid: Int,c: NotificationChannel) {
        if(uid(pkg)!=uid) fail(R.string.error_package_changed)
        call("updateNotificationChannelForPackage",pkg,uid,c)
    }
    private fun groupBlocked(pkg: String,uid: Int,c: NotificationChannel): Boolean {
        val group=c.group ?: return false
        return (call("getNotificationChannelGroupForPackage",group,pkg,uid) as? NotificationChannelGroup)?.isBlocked==true
    }
    fun policy(pkg: String,uid: Int,c: NotificationChannel)=ChannelPolicy(c.importance,c.sound!=null,c.shouldVibrate(),
        ChannelPolicy.resolveVisibility(c.lockscreenVisibility),
        enabled(pkg,uid),groupBlocked(pkg,uid,c),c.id)
    fun inspect(pkg: String): JSONObject {
        val uid=uid(pkg)
        val result=JSONObject().put("package",pkg).put("uid",uid).put("sdk",Build.VERSION.SDK_INT)
            .put("fingerprint",Build.FINGERPRINT).put("enabled",enabled(pkg,uid))
        val array=JSONArray()
        channels(pkg,uid).forEach { c ->
            val p=policy(pkg,uid,c)
            array.put(JSONObject().put("id",c.id).put("name",c.name).put("parcel",encode(c))
                .put("importance",c.importance).put("soundUri",c.sound?.toString()?:JSONObject.NULL)
                .put("eligible",p.eligible).put("popup",p.popup).put("sound",p.sound).put("vibrate",p.vibrate)
                .put("lockscreen",p.lockscreen).put("visibility",c.lockscreenVisibility).put("resolvedVisibility",p.visibility)
                .put("badge",c.canShowBadge()).put("groupBlocked",groupBlocked(pkg,uid,c)))
        }
        result.put("channels",array)
        if(result.toString().length>300_000) fail(R.string.error_snapshot_size)
        return result
    }
    fun apps(): JSONArray {
        val result=JSONArray()
        context.packageManager.getInstalledApplications(0).forEach { app ->
            if(app.packageName==BuildConfig.APPLICATION_ID) return@forEach
            val count=call("getNumNotificationChannelsForPackage",app.packageName,app.uid,false) as Int
            if(count>0) result.put(JSONObject().put("package",app.packageName).put("name",app.loadLabel(context.packageManager))
                .put("count",count).put("system",app.flags and ApplicationInfo.FLAG_SYSTEM!=0).put("enabled",enabled(app.packageName,app.uid)))
        }
        return result
    }
    fun validateSnapshot(s: JSONObject) {
        if(s.getInt("sdk")!=Build.VERSION.SDK_INT || s.getString("fingerprint")!=Build.FINGERPRINT) fail(R.string.error_snapshot_build)
        if(uid(s.getString("package"))!=s.getInt("uid")) fail(R.string.error_package_changed)
    }
    fun original(s: JSONObject,id: String): NotificationChannel {
        validateSnapshot(s)
        val a=s.getJSONArray("channels")
        for(i in 0 until a.length()) {
            val c=a.getJSONObject(i)
            if(c.getString("id")==id) return decode(c.getString("parcel"))
        }
        fail(R.string.error_channel_missing)
    }
    /** Change only Settings-affected fields on the current object, retaining all others. */
    fun restore(s: JSONObject,id: String) {
        val original=original(s,id)
        val pkg=s.getString("package")
        val current=channel(pkg,id)
        current.importance=original.importance
        current.setSound(original.sound,original.audioAttributes)
        current.vibrationPattern=original.vibrationPattern
        if(Build.VERSION.SDK_INT>=35) current.vibrationEffect=original.vibrationEffect
        current.enableVibration(original.shouldVibrate())
        current.lockscreenVisibility=original.lockscreenVisibility
        update(pkg,s.getInt("uid"),current)
        if(!sameFields(original,channel(pkg,id))) fail(R.string.error_restore)
    }
    companion object {
        fun encode(c: NotificationChannel): String {
            val p=Parcel.obtain()
            return try { c.writeToParcel(p,0); Base64.encodeToString(p.marshall(),Base64.NO_WRAP) } finally { p.recycle() }
        }
        fun decode(value: String): NotificationChannel {
            val bytes=Base64.decode(value,Base64.NO_WRAP);val p=Parcel.obtain()
            return try { p.unmarshall(bytes,0,bytes.size);p.setDataPosition(0);NotificationChannel.CREATOR.createFromParcel(p) } finally { p.recycle() }
        }
        fun sameFields(a: NotificationChannel,b: NotificationChannel)=a.importance==b.importance && a.sound==b.sound && a.audioAttributes==b.audioAttributes &&
            a.shouldVibrate()==b.shouldVibrate() && a.vibrationPattern.contentEquals(b.vibrationPattern) &&
            (Build.VERSION.SDK_INT<35 || a.vibrationEffect==b.vibrationEffect) && a.lockscreenVisibility==b.lockscreenVisibility
    }
}
