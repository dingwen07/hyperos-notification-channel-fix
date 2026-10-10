package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.Context
import android.os.Binder
import org.json.JSONArray
import org.json.JSONObject

/** Instantiated by Shizuku as a Binder in its shell process, never as an exported Android Service. */
class ChannelService(private val context: Context) : IChannelService.Stub() {
    private class RestorationFailure(cause: Exception) : Exception(cause)
    @Volatile private var cancelled=false
    private val operationLock=Any()
    private fun execute(operation: (NotificationAccess)->Any): String = synchronized(operationLock) {
        val identity=Binder.clearCallingIdentity()
        try {
            JSONObject().put("ok",true).put("data",operation(NotificationAccess(context))).toString()
        } catch(e: Exception) {
            val failure=(if(e is RestorationFailure)e.cause else e) as? RepairFailure
            JSONObject().put("ok",false).put("errorRes",failure?.messageId?:R.string.operation_error)
                .put("errorArgs",JSONArray(failure?.arguments?.toList()?:emptyList<String>()))
                .put("recoveryRequired",e is RestorationFailure)
                .put("detail",e.stackTraceToString().take(6000)).toString()
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun listApps()=execute {it.apps()}
    override fun inspect(pkg: String)=execute {XiaomiLabels(context);RepairPreconditions.checkGlobalLockscreen();it.inspect(pkg)}
    override fun cancel(){cancelled=true}
    override fun returnToApp() = synchronized(operationLock) {
        // This must work even when notification access or a repair prerequisite failed.
        // The fixed destination keeps the shell interface from becoming an arbitrary launcher.
        val identity=Binder.clearCallingIdentity()
        try {
            Command.run("/system/bin/am","start","-W","--user","0","-f","0x34000000",
                "-n",BuildConfig.APPLICATION_ID+"/"+MainActivity::class.java.name)
            Unit
        } finally {Binder.restoreCallingIdentity(identity)}
    }
    override fun destroy(){cancelled=true;kotlin.system.exitProcess(0)}
    override fun restoreChannel(snapshot: String,id: String)=execute {it.restore(JSONObject(snapshot),id);"restored"}

    override fun repairApp(snapshot: String)=execute { a ->
        cancelled=false
        RepairPreconditions.checkGlobalLockscreen()
        val s=JSONObject(snapshot);a.validateSnapshot(s)
        val pkg=s.getString("package");val uid=s.getInt("uid")
        if(!a.enabled(pkg,uid))fail(R.string.disabled)
        val policies=a.channels(pkg,uid).map {a.policy(pkg,uid,it)}
        XiaomiPolicy.read(pkg)
        SettingsAutomation(context){cancelled}.use { ui ->
            ui.open(pkg,null,context.packageManager.getApplicationInfo(pkg,0).loadLabel(context.packageManager).toString())
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
        RepairPreconditions.checkGlobalLockscreen()
        val s=JSONObject(snapshot);val original=a.original(s,id)
        val pkg=s.getString("package");val uid=s.getInt("uid")
        if(!NotificationAccess.sameFields(original,a.channel(pkg,id)))fail(R.string.error_channel_changed)
        val p=a.policy(pkg,uid,original)
        if(!p.eligible)return@execute "skipped"
        val before=XiaomiPolicy.read(pkg)
        var began=false
        try {
            SettingsAutomation(context){cancelled}.use { ui ->
                ui.open(pkg,id,original.name.toString());began=true
                val l=ui.labels
                ui.toggle(l.preference("allow_float",R.string.allow_float),p.popup)
                ui.toggle(l.preference("vibrate",R.string.vibrate),p.vibrate)
                // Allow content for an unspecified override; global system privacy still applies.
                // Restore the original -1000 after writing Xiaomi's channel gate.
                if(p.lockscreen&&!before.allowed(pkg,id,"keyguard"))ui.lockscreen(p.visibility)
                if(p.sound&&!before.allowed(pkg,id,"sound"))ui.sound(true)
            }
        } finally {
            if(began)try { a.restore(s,id) } catch(e: Exception) { throw RestorationFailure(e) }
        }
        val after=XiaomiPolicy.read(pkg)
        if(p.popup&&!after.allowed(pkg,id,"channel_flag"))fail(R.string.error_verify_popup)
        if(p.sound&&!after.allowed(pkg,id,"sound"))fail(R.string.error_verify_sound)
        if(p.vibrate&&!after.allowed(pkg,id,"vibrate"))fail(R.string.error_verify_vibration)
        // SECRET remains blocked by its preserved Android visibility, regardless of an OEM allow flag.
        if(p.lockscreen&&!after.allowed(pkg,id,"keyguard"))fail(R.string.error_verify_lockscreen)
        "verified"
    }
}
