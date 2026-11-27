package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream

/** Framework calls from the shell UserService, which has no registered application thread. */
@SuppressLint("PrivateApi")
internal object ShellFramework {
    fun binder(name: String) = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, name) as IBinder

    private val callerPackage get() = if (Process.myUid() == 0) "root" else "com.android.shell"

    fun startActivity(intent: Intent, userId: Int) {
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val start = Class.forName("android.app.IActivityTaskManager").methods.single { it.name == "startActivityAndWait" }
        val wait = start.invoke(manager, null, callerPackage, null, intent, null, null, null, 0, 0, null, null, userId)
        val result = wait.javaClass.getField("result").getInt(wait)
        check(!wait.javaClass.getField("timeout").getBoolean(wait) &&
            android.app.ActivityManager::class.java.getMethod("isStartResultSuccessful", Int::class.javaPrimitiveType)
                .invoke(null, result) == true) { "Activity launch failed (result=$result)" }
    }

    fun checkLockscreen(userId: Int) {
        val type = Class.forName("android.app.IActivityManager")
        val manager = android.app.ActivityManager::class.java.getMethod("getService").invoke(null)
        val token = Binder()
        val holder = type.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java).invoke(manager, "settings", userId, token, "ChannelRepair")
            ?: fail(R.string.error_global_lockscreen_read)
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val extras = Bundle().apply { putInt("_user", userId) }
            val calls = Class.forName("android.content.IContentProvider").methods.filter { it.name == "call" }
            fun read(key: String): String {
                val result = if (Build.VERSION.SDK_INT >= 31) {
                    val attribution = AttributionSource.Builder(Process.myUid()).setPackageName(callerPackage).build()
                    calls.single { it.parameterTypes.first() == AttributionSource::class.java }
                        .invoke(provider, attribution, "settings", "GET_secure", key, extras)
                } else {
                    calls.single { it.parameterCount == 6 }.invoke(provider, callerPackage, null, "settings", "GET_secure", key, extras)
                }
                return (result as? Bundle)?.getString("value") ?: "null"
            }
            RepairPreconditions.requireGlobalLockscreen(read("lock_screen_show_notifications"), read("lock_screen_allow_private_notifications"))
        } finally {
            type.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                Int::class.javaPrimitiveType).invoke(manager, "settings", token, userId)
        }
    }

    fun systemUiDump(): String {
        val (read, write) = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
            write.use { binder("activity").dumpAsync(it.fileDescriptor, arrayOf("service", "com.android.systemui/.SystemUIService")) }
            val poll = StructPollfd().apply { fd = read.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
                if (remaining <= 0 || Os.poll(arrayOf(poll), remaining) == 0) fail(R.string.error_command_timeout)
                val count = input.read(buffer)
                if (count < 0) return bytes.toString(Charsets.UTF_8.name())
                check(bytes.size() + count <= 8 * 1024 * 1024) { "SystemUI diagnostic exceeds 8 MiB" }
                bytes.write(buffer, 0, count)
            }
        }
    }
}
