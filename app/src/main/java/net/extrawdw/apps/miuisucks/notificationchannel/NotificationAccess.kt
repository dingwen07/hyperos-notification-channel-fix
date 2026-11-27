package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Hidden framework interfaces stay in the shell UserService; public APIs cannot edit other apps' channels. */
@SuppressLint("PrivateApi")
internal class NotificationAccess(val context: Context) {
    init { check(android.os.Process.myUid() in setOf(0,2000)) { "Shizuku shell or root required" } }
    private val supported = RepairProfiles(context)
    val hostId get() = supported.hostId
    fun requireForeground() = supported.requireForeground()
    val profiles by lazy { supported.available() }
    fun profile(id: Int) = profiles.singleOrNull { it.id == id } ?: fail(R.string.error_profile_unavailable)
    private val type=Class.forName("android.app.INotificationManager")
    private val service: Any
    init {
        val binder=ShellFramework.binder("notification")
        service=Class.forName("android.app.INotificationManager\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,binder)!!
    }
    private fun call(name: String,vararg args: Any?): Any? {
        val method=type.methods.single { it.name==name && it.parameterCount==args.size }
        return method.invoke(service,*args)
    }
    fun enabled(pkg: String,uid: Int)=call("areNotificationsEnabledForPackage",pkg,uid) as Boolean
    @Suppress("UNCHECKED_CAST")
    fun channels(pkg: String,uid: Int): List<NotificationChannel> {
        val slice=call("getNotificationChannelsForPackage",pkg,uid,false)!!
        return slice.javaClass.getMethod("getList").invoke(slice) as List<NotificationChannel>
    }
    fun channel(pkg: String,id: String,userId: Int): NotificationChannel =
        call("getNotificationChannel","com.android.shell",userId,pkg,id) as? NotificationChannel ?: fail(R.string.error_channel_missing)
    private fun groupBlocked(pkg: String,uid: Int,c: NotificationChannel): Boolean {
        val group=c.group ?: return false
        return (call("getNotificationChannelGroupForPackage",group,pkg,uid) as? NotificationChannelGroup)?.isBlocked==true
    }
    fun policy(pkg: String,uid: Int,c: NotificationChannel)=ChannelPolicy(c.importance,c.sound!=null,c.shouldVibrate(),
        ChannelPolicy.resolveVisibility(c.lockscreenVisibility),
        enabled(pkg,uid),groupBlocked(pkg,uid,c),c.id)
    fun inspect(pkg: String, userId: Int = hostId): JSONObject {
        val profile = profile(userId)
        val install=profile.context.packageManager.getPackageInfo(pkg,0)
        val uid=checkNotNull(install.applicationInfo).uid
        val result=JSONObject().put("package",pkg).put("uid",uid).put("sdk",Build.VERSION.SDK_INT)
            .put("fingerprint",Build.FINGERPRINT).put("enabled",enabled(pkg,uid))
            .put("firstInstallTime",install.firstInstallTime).put("userId",userId).put("userSerial",profile.serial).put("hostUserId",hostId)
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
    // ChannelService clears the caller identity: shell/root is exempt from package visibility filtering.
    @SuppressLint("QueryPermissionsNeeded")
    fun apps(): JSONArray {
        val result=JSONArray()
        for (profile in profiles) {
            val pm = profile.context.packageManager
            pm.getInstalledApplications(0).forEach { app ->
                if(app.packageName==BuildConfig.APPLICATION_ID) return@forEach
                val count=call("getNumNotificationChannelsForPackage",app.packageName,app.uid,false) as Int
                if(count>0) result.put(JSONObject().put("package",app.packageName).put("name",app.loadLabel(pm))
                    .put("count",count).put("system",app.flags and ApplicationInfo.FLAG_SYSTEM!=0).put("enabled",enabled(app.packageName,app.uid)))
            }
        }
        return result
    }
    fun targetIds(pkg: String): JSONArray {
        val ids = JSONArray()
        for (profile in profiles) {
            val pm = profile.context.packageManager
            val info = try { pm.getApplicationInfo(pkg, 0) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { continue }
            if (call("getNumNotificationChannelsForPackage", pkg, info.uid, false) as Int > 0) ids.put(profile.id)
        }
        return ids
    }
    fun peerAlertNeeds(pkg: String, id: String, userId: Int, current: ChannelPolicy): Pair<Boolean, Boolean> {
        if (current.popup && current.vibrate) return false to false
        val policies = profiles.filter { it.id != userId }.mapNotNull { peer ->
            val uid = try { peer.context.packageManager.getApplicationInfo(pkg,0).uid }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { return@mapNotNull null }
            val channel = call("getNotificationChannel","com.android.shell",peer.id,pkg,id) as? NotificationChannel
                ?: return@mapNotNull null
            policy(pkg,uid,channel)
        }
        return policies.any { it.popup } to policies.any { it.vibrate }
    }
    fun validateSnapshot(s: JSONObject) {
        if(s.getInt("sdk")!=Build.VERSION.SDK_INT || s.getString("fingerprint")!=Build.FINGERPRINT) fail(R.string.error_snapshot_build)
        requireForeground()
        if (s.optInt("hostUserId", 0) != hostId) fail(R.string.error_foreground_user)
        val target = profile(s.optInt("userId", 0))
        if (target.id != 0 && !s.has("userSerial")) fail(R.string.error_package_changed)
        if (s.has("userSerial") && s.getLong("userSerial") != target.serial) fail(R.string.error_package_changed)
        val install=target.context.packageManager.getPackageInfo(s.getString("package"),0)
        if(install.applicationInfo?.uid!=s.getInt("uid") ||
            (s.has("firstInstallTime") && install.firstInstallTime!=s.getLong("firstInstallTime"))) fail(R.string.error_package_changed)
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
        val userId=s.optInt("userId",0)
        val current=channel(pkg,id,userId)
        current.importance=original.importance
        current.setSound(original.sound,original.audioAttributes)
        current.vibrationPattern=original.vibrationPattern
        if(Build.VERSION.SDK_INT>=35) current.vibrationEffect=original.vibrationEffect
        current.enableVibration(original.shouldVibrate())
        current.lockscreenVisibility=original.lockscreenVisibility
        validateSnapshot(s)
        call("updateNotificationChannelForPackage",pkg,s.getInt("uid"),current)
        if(!sameFields(original,channel(pkg,id,userId))) fail(R.string.error_restore)
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
