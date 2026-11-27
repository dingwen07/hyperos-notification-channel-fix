package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.view.Display

@SuppressLint("PrivateApi")
internal fun Context.packageContextForUser(name: String, user: UserHandle) =
    Context::class.java.getMethod("createPackageContextAsUser",
        String::class.java, Int::class.javaPrimitiveType, UserHandle::class.java).invoke(this, name, 0, user) as Context

internal data class RepairProfile(val id: Int, val serial: Long, val context: Context)

/** Only profiles sharing the foreground full user's UI can be operated in the same repair run. */
@SuppressLint("PrivateApi")
internal class RepairProfiles(private val host: Context) {
    private val users = host.getSystemService(UserManager::class.java)
    val hostId = Context::class.java.getMethod("getUserId").invoke(host) as Int
    private val userHandle = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
    private val managed = UserManager::class.java.getMethod("isManagedProfile", Int::class.javaPrimitiveType)
    fun requireForeground() {
        if (ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) != hostId)
            fail(R.string.error_foreground_user)
    }
    // KeyguardManager also initializes WindowManager, which requires an application runtime
    // on Android 17. Query the same trust service from the Shizuku shell process.
    private val trust by lazy {
        val binder = ShellFramework.binder("trust")
        Class.forName("android.app.trust.ITrustManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }
    private val deviceLocked by lazy { Class.forName("android.app.trust.ITrustManager").methods.single { it.name == "isDeviceLocked" } }
    private fun isLocked(id: Int): Boolean = if (deviceLocked.parameterCount == 1)
        deviceLocked.invoke(trust, id) as Boolean else deviceLocked.invoke(trust, id, Display.DEFAULT_DISPLAY) as Boolean

    fun available(): List<RepairProfile> {
        requireForeground()
        // Explicit parent ID also works on Android versions where getUserProfiles() uses
        // the shell process's user instead of the context's user.
        val ids = UserManager::class.java.getMethod("getProfileIds", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).invoke(users, hostId, true) as IntArray
        val visible = if (Build.VERSION.SDK_INT >= 34)
            UserManager::class.java.getMethod("getVisibleUsers").invoke(users) as Set<*> else null
        return ids.sorted().mapNotNull { id ->
            val user = userHandle.invoke(null, id) as UserHandle
            if (visible != null && user !in visible) return@mapNotNull null
            if (id != hostId) {
                if (id != 999 && managed.invoke(users, id) != true) return@mapNotNull null
                if (!users.isUserUnlocked(user) || users.isQuietModeEnabled(user) || isLocked(id)) return@mapNotNull null
            }
            val serial = users.getSerialNumberForUser(user)
            if (serial < 0) return@mapNotNull null
            // The repair APK need only be installed in the foreground full user.
            val context = if (id == hostId) host else host.packageContextForUser("android", user)
            RepairProfile(id, serial, context)
        }
    }
}
