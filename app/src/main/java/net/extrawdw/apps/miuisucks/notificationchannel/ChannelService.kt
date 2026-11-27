package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.Context
import android.content.Intent
import android.os.Binder
import org.json.JSONArray
import org.json.JSONObject

/** Instantiated by Shizuku as a Binder in its shell process, never as an exported Android Service. */
class ChannelService(private val context: Context) : IChannelService.Stub() {
    private class RestorationFailure(cause: Exception) : Exception(cause)
    @Volatile private var cancelled=false
    private val operationLock=Any()
    // Capture the app caller before clearing Binder identity. Shizuku runs as shell/root
    // and its constructor context can belong to user 0 even for a secondary-user client.
    private fun execute(operation: (NotificationAccess)->Any): String = synchronized(operationLock) {
        val caller = Binder.getCallingUserHandle()
        val identity=Binder.clearCallingIdentity()
        try {
            JSONObject().put("ok",true).put("data",operation(NotificationAccess(context.packageContextForUser(BuildConfig.APPLICATION_ID, caller)))).toString()
        } catch(e: Exception) {
            val failure=(if(e is RestorationFailure)e.cause else e) as? RepairFailure
            JSONObject().put("ok",false).put("errorRes",failure?.messageId?:R.string.operation_error)
                .put("errorArgs",JSONArray(failure?.arguments?.toList()?:emptyList<String>()))
                .put("recoveryRequired",e is RestorationFailure)
                .put("detail",e.stackTraceToString().take(6000)).toString()
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun listApps()=execute {it.apps()}
    override fun inspect(pkg: String)=execute {XiaomiLabels(it.context);ShellFramework.checkLockscreen(it.hostId);it.inspect(pkg)}
    override fun targetProfiles(pkg: String)=execute { it.targetIds(pkg) }
    override fun inspectProfile(pkg: String,userId: Int)=execute {
        XiaomiLabels(it.context)
        ShellFramework.checkLockscreen(it.hostId)
        val snapshot = it.inspect(pkg, userId)
        // Only user 0 owns the published SystemUIService dump. Other full users are
        // verified from their own Settings after their Android fields have been restored.
        if (it.hostId != 0) return@execute snapshot
        val oem = XiaomiPolicy.read(pkg)
        val channels = snapshot.getJSONArray("channels")
        for (index in 0 until channels.length()) {
            val c = channels.getJSONObject(index)
            val id = c.getString("id")
            c.put("requiredGatesOpen", (!c.getBoolean("popup") || oem.allowed(pkg,id,"channel_flag")) &&
                (!c.getBoolean("sound") || oem.allowed(pkg,id,"sound")) &&
                (!c.getBoolean("vibrate") || oem.allowed(pkg,id,"vibrate")) &&
                (!c.getBoolean("lockscreen") || oem.allowed(pkg,id,"keyguard")))
        }
        snapshot
    }
    override fun cancel(){cancelled=true}
    override fun returnToApp() = synchronized(operationLock) {
        // This must work even when notification access or a repair prerequisite failed.
        // The fixed destination keeps the shell interface from becoming an arbitrary launcher.
        val caller = Binder.getCallingUserHandle()
        val identity=Binder.clearCallingIdentity()
        try {
            val host = RepairProfiles(context.packageContextForUser(BuildConfig.APPLICATION_ID, caller))
            host.requireForeground()
            val intent = Intent().setClassName(BuildConfig.APPLICATION_ID, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ShellFramework.startActivity(intent, host.hostId)
            Unit
        } finally {Binder.restoreCallingIdentity(identity)}
    }
    override fun destroy(){cancelled=true;kotlin.system.exitProcess(0)}
    override fun restoreChannel(snapshot: String,id: String)=execute {it.restore(JSONObject(snapshot),id);"restored"}

    override fun repairApp(snapshot: String)=execute { a ->
        cancelled=false
        ShellFramework.checkLockscreen(a.hostId)
        val s=JSONObject(snapshot);a.validateSnapshot(s)
        val pkg=s.getString("package");val uid=s.getInt("uid")
        if(!a.enabled(pkg,uid))return@execute "skipped"
        val policies=a.channels(pkg,uid).map {a.policy(pkg,uid,it)}
        if (a.hostId == 0) XiaomiPolicy.read(pkg)
        SettingsAutomation(a.context,a.hostId){a.requireForeground();cancelled}.use { ui ->
            val pm = a.profile(s.optInt("userId",0)).context.packageManager
            ui.open(pkg,null,pm.getApplicationInfo(pkg,0).loadLabel(pm).toString(),uid,s.optInt("userId",0))
            val l=ui.labels
            // Xiaomi's app-level launcher badge gate is always enabled; channel badges are untouched.
            ui.toggle(l.preference("setting_badge",R.string.setting_badge),true)
            if(policies.any {it.popup})ui.toggle(l.preference("allow_float",R.string.allow_float),true)
            if(policies.any {it.lockscreen})ui.toggle(l.preference("allow_keyguard",R.string.allow_keyguard),true)
            if(policies.any {it.sound})ui.toggle(l.text(R.string.allow_sound),true)
            if(policies.any {it.vibrate})ui.toggle(l.preference("vibrate",R.string.vibrate),true)
        }
        "app_gates_ready"
    }
    override fun repairChannel(snapshot: String,id: String)=execute { a ->
        if(ChannelPolicy.isMiPush(id))return@execute "skipped"
        ShellFramework.checkLockscreen(a.hostId)
        val s=JSONObject(snapshot);val original=a.original(s,id)
        val pkg=s.getString("package");val uid=s.getInt("uid")
        if(!NotificationAccess.sameFields(original,a.channel(pkg,id,s.optInt("userId",0))))fail(R.string.error_channel_changed)
        val p=a.policy(pkg,uid,original)
        if(!p.eligible)return@execute "skipped"
        val before=if (a.hostId == 0) XiaomiPolicy.read(pkg) else null
        var began=false
        try {
            SettingsAutomation(a.context,a.hostId){a.requireForeground();cancelled}.use { ui ->
                ui.open(pkg,id,original.name.toString(),uid,s.optInt("userId",0));began=true
                val l=ui.labels
                // Copies in this foreground user’s profile group share the Xiaomi gate.
                val peers = a.peerAlertNeeds(pkg,id,s.optInt("userId",0),p)
                if (p.popup || !peers.first) ui.toggle(l.preference("allow_float",R.string.allow_float),p.popup)
                if (p.vibrate || !peers.second) ui.toggle(l.preference("vibrate",R.string.vibrate),p.vibrate)
                // Allow content for an unspecified override; global system privacy still applies.
                // Restore the original -1000 after writing Xiaomi's channel gate.
                if(p.lockscreen && !(before?.allowed(pkg,id,"keyguard") ?: ui.lockscreenAllowed()))ui.lockscreen(p.visibility)
                if(p.sound && !(before?.allowed(pkg,id,"sound") ?: ui.soundAllowed()))ui.sound(true)
            }
        } finally {
            if(began)try { a.restore(s,id) } catch(e: Exception) { throw RestorationFailure(e) }
        }
        a.requireForeground()
        if (a.hostId == 0) {
            val after=XiaomiPolicy.read(pkg)
            if(p.popup&&!after.allowed(pkg,id,"channel_flag"))fail(R.string.error_verify_popup)
            if(p.sound&&!after.allowed(pkg,id,"sound"))fail(R.string.error_verify_sound)
            if(p.vibrate&&!after.allowed(pkg,id,"vibrate"))fail(R.string.error_verify_vibration)
            if(p.lockscreen&&!after.allowed(pkg,id,"keyguard"))fail(R.string.error_verify_lockscreen)
        } else SettingsAutomation(a.context,a.hostId){a.requireForeground();cancelled}.use { ui ->
            ui.open(pkg,id,original.name.toString(),uid,s.getInt("userId"))
            val l = ui.labels
            if (p.popup && !ui.checked(l.preference("allow_float",R.string.allow_float))) fail(R.string.error_verify_popup)
            if (p.vibrate && !ui.checked(l.preference("vibrate",R.string.vibrate))) fail(R.string.error_verify_vibration)
            if (p.lockscreen && !ui.lockscreenAllowed()) fail(R.string.error_verify_lockscreen)
            if (p.sound && !ui.soundAllowed()) fail(R.string.error_verify_sound)
        }
        "verified"
    }
}
