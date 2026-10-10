package net.extrawdw.apps.miuisucks.notificationchannel

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.os.HandlerThread
import android.os.Looper

/** Owns both the shell connection and the two callback threads, including failed construction. */
// Shell/root needs the hidden connection constructor; there is no public equivalent outside instrumentation.
@SuppressLint("PrivateApi")
internal class ShellUiAutomation : AutoCloseable {
    init { check(android.os.Process.myUid() in setOf(0,2000)) { "Shell UiAutomation requires shell or root" } }
    // Resolve the cleanup APIs before starting any threads. Hidden APIs stay in the shell process.
    private val connectionType=Class.forName("android.app.UiAutomationConnection")
    private val connection=connectionType.getConstructor().newInstance()
    private val disconnectConnection=connectionType.getMethod("disconnect")
    private val disconnectUi=UiAutomation::class.java.getMethod("disconnect")
    // Shizuku's shell runtime is exempt from app non-SDK restrictions; the UID guard above
    // prevents this from being used in the regular app process. There is no public thread accessor.
    @SuppressLint("SoonBlockedPrivateApi")
    private val callbackThreadField=UiAutomation::class.java.getDeclaredField("mRemoteCallbackThread").apply {
        isAccessible=true
    }
    private val thread=HandlerThread("channel-ui")
    private var closed=false
    val ui: UiAutomation

    init {
        var created: UiAutomation?=null
        thread.start()
        try {
            created=UiAutomation::class.java.getConstructor(Looper::class.java,
                Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.looper,connection)
            UiAutomation::class.java.getMethod("connect",Int::class.javaPrimitiveType)
                .invoke(created,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            created.serviceInfo=created.serviceInfo.apply {
                flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            ui=created
        } catch(failure: Throwable) {
            try { release(created) } catch(cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
    }

    private fun release(automation: UiAutomation?) {
        var callbackThread: HandlerThread?=null
        try {
            if(automation!=null) {
                callbackThread=callbackThreadField.get(automation) as? HandlerThread
                try { disconnectUi.invoke(automation) } catch(failure: Throwable) {
                    // A failed connect can leave UiAutomation CONNECTING, where disconnect refuses
                    // cleanup. Its connection can still unregister a partially established session.
                    try { disconnectConnection.invoke(connection) }
                    catch(cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
                    throw failure
                }
            }
        } finally {
            // UiAutomation creates this thread before connecting; failure does not quit it.
            // Discard late initialization callbacks after the connection has been torn down.
            callbackThread?.quit()
            thread.quitSafely()
        }
    }

    override fun close() {
        if(closed)return
        closed=true
        release(ui)
    }
}
