package net.extrawdw.apps.miuisucks.notificationchannel

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.util.AtomicFile
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

class RepairApplication : Application() {
    override fun onCreate() { super.onCreate(); RepairState.initialize(this) }
}

data class AppEntry(val pkg: String, val name: String, val count: Int, val system: Boolean, val enabled: Boolean)
class RemoteOperationException(val messageId: Int,val userMessage: String,detail: String,val recoveryRequired: Boolean):
    IllegalStateException(userMessage+"\n"+detail)

object RepairState {
    lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    val worker = Executors.newSingleThreadExecutor()
    var bridge: IChannelService? = null
        private set
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var running by mutableStateOf(false)
    var status by mutableStateOf("")
    var detail by mutableStateOf("")
    var errorRes by mutableStateOf<Int?>(null)
    var apps by mutableStateOf(emptyList<AppEntry>())
    var selected by mutableStateOf(emptySet<String>())
    var plans by mutableStateOf(emptyList<String>())
    var recovery by mutableStateOf(false)
    @Volatile var stopRequested = false
    private var binding = false
    private lateinit var args: Shizuku.UserServiceArgs
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = IChannelService.Stub.asInterface(binder)
            binding = false
            connected = true
            status = app.getString(if (recovery) R.string.pending_recovery else R.string.ready)
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
    }
    private fun disconnected() {
        bridge = null; connected = false; binding = false
        status = app.getString(R.string.not_connected)
    }
    fun initialize(application: Application) {
        app = application
        recovery = journal().baseFile.exists() || File(journal().baseFile.path + ".bak").exists()
        status = app.getString(if (recovery) R.string.pending_recovery else R.string.not_connected)
        args = Shizuku.UserServiceArgs(ComponentName(app, ChannelService::class.java))
            .daemon(false).processNameSuffix("repair_shell").debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE).tag("channel_repair")
        Shizuku.addBinderReceivedListenerSticky { main.post { if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect() } }
        Shizuku.addBinderDeadListener { main.post { disconnected() } }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else status = app.getString(R.string.not_connected)
        }
    }
    fun connect() {
        try {
            if (!Shizuku.pingBinder()) { status = app.getString(R.string.not_connected); return }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(7); return }
            if (connected || binding) return
            binding = true
            Shizuku.bindUserService(args, connection)
            main.postDelayed({ if(binding) { binding=false; detail="Shizuku UserService connection timed out"; status=app.getString(R.string.operation_error) } },15_000)
        } catch (e: Exception) { binding=false; error(e) }
    }
    fun data(response: String): Any {
        val result = JSONObject(response)
        if(!result.getBoolean("ok")) {
            val args=result.optJSONArray("errorArgs")?:JSONArray()
            val messageId=result.optInt("errorRes",R.string.operation_error)
            val message=app.getString(messageId,*(0 until args.length()).map {args.getString(it)}.toTypedArray())
            throw RemoteOperationException(messageId,message,result.optString("detail"),result.optBoolean("recoveryRequired",true))
        }
        return result.get("data")
    }
    fun post(block: () -> Unit) { main.post(block) }
    fun error(e: Exception) { post {
        detail=e.message.orEmpty()
        errorRes=(e as? RemoteOperationException)?.messageId
        status=(e as? RemoteOperationException)?.userMessage?:app.getString(R.string.operation_error)
    } }
    private fun task(work: (IChannelService) -> Unit) {
        if (busy || running) return
        val service = bridge ?: return
        busy=true; detail=""; errorRes=null
        worker.execute {
            try { work(service) } catch(e: Exception) { error(e) }
            finally { post { busy=false } }
        }
    }
    fun scan() = task { service ->
        val array = data(service.listApps()) as JSONArray
        val found = (0 until array.length()).map { array.getJSONObject(it) }.map {
            AppEntry(it.getString("package"),it.getString("name"),it.getInt("count"),it.getBoolean("system"),it.getBoolean("enabled"))
        }.sortedWith(compareBy<AppEntry> { !it.pkg.endsWith(".testapp") }.thenBy { it.name.lowercase() })
        post { apps=found; plans=emptyList(); status=app.getString(R.string.ready) }
    }
    fun preview() {
        val packages=selected.toList()
        task { service ->
            val inspected=packages.map { (data(service.inspect(it)) as JSONObject).toString() }
            post { plans=inspected }
        }
    }
    fun stop() {
        stopRequested=true
        // A second Binder thread must be able to cancel the long repair call.
        Thread { runCatching { bridge?.cancel() } }.start()
    }
    private fun journal() = AtomicFile(File(app.filesDir,"pending-channel.json"))
    fun hasJournal()=journal().baseFile.exists() || File(journal().baseFile.path+".bak").exists()
    fun channelFailure(e: Exception) {
        // Known failures either made no change or verified restoration. An unknown Binder outcome
        // retains the journal for explicit recovery, never overwrites a concurrent user edit.
        if(e is RemoteOperationException && !e.recoveryRequired)clearJournal()
    }
    fun saveJournal(snapshot: String, id: String) {
        val file=journal(); val stream=file.startWrite()
        try {
            stream.write(JSONObject().put("snapshot",snapshot).put("id",id).toString().toByteArray())
            file.finishWrite(stream); post { recovery=true }
        } catch(e: Exception) { file.failWrite(stream); throw e }
    }
    fun clearJournal() { journal().delete(); post { recovery=false } }
    fun recover() = task { service ->
        val pending=JSONObject(journal().readFully().toString(Charsets.UTF_8))
        data(service.restoreChannel(pending.getString("snapshot"),pending.getString("id")))
        clearJournal()
        post { plans=emptyList(); status=app.getString(R.string.recovered) }
    }
}
