package net.extrawdw.apps.miuisucks.notificationchannel

import android.app.*
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject

class RepairRunner : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { RepairState.stop(); return START_NOT_STICKY }
        if (RepairState.running) return START_NOT_STICKY
        val bridge=RepairState.bridge ?: run { stopSelf(); return START_NOT_STICKY }
        val plans=RepairState.plans.toList()
        if(plans.isEmpty() || RepairState.recovery) { stopSelf(); return START_NOT_STICKY }
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("repair",getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,1,Intent(this,RepairRunner::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val returnIntent=Intent(this,MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open=PendingIntent.getActivity(this,2,returnIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"repair").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_text))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,getString(R.string.cancel),stop).build()).build()
        startForeground(1,notification)
        RepairState.running=true; RepairState.stopRequested=false
        RepairState.status=getString(R.string.working); RepairState.detail=""; RepairState.errorRes=null
        RepairState.worker.execute {
            var completed=0
            try {
                for(snapshot in plans) {
                    if(RepairState.stopRequested) break
                    val s=JSONObject(snapshot)
                    val channels=s.getJSONArray("channels")
                    val eligible=(0 until channels.length()).map { channels.getJSONObject(it) }.filter { it.getBoolean("eligible") }
                    RepairState.post { RepairState.status=getString(R.string.app_gates) }
                    RepairState.data(bridge.repairApp(snapshot))
                    for(c in eligible) {
                        if(RepairState.stopRequested) break
                        val id=c.getString("id")
                        RepairState.saveJournal(snapshot,id)
                        RepairState.post { RepairState.status=getString(R.string.running_channel,c.getString("name"),eligible.indexOf(c)+1,eligible.size) }
                        try {
                            val result=RepairState.data(bridge.repairChannel(snapshot,id))
                            RepairState.clearJournal()
                            if(result=="verified")completed++
                        } catch(e: Exception) {
                            RepairState.channelFailure(e)
                            throw e
                        }
                    }
                }
                val count=completed
                RepairState.post { RepairState.status=if(RepairState.stopRequested)getString(R.string.stopped) else getString(R.string.completed,count) }
            } catch(e: Exception) {
                if(RepairState.stopRequested) RepairState.post {
                    RepairState.status=getString(if(RepairState.hasJournal())R.string.pending_recovery else R.string.stopped)
                }
                else RepairState.error(e)
            } finally {
                // ChannelService has already disconnected UiAutomation and attempted restoration.
                // Use shell launch privileges to bring the existing task forward, preserving results.
                val returned=runCatching {bridge.returnToApp()}.isSuccess
                RepairState.post {
                    RepairState.running=false; RepairState.plans=emptyList()
                    if(returned)stopForeground(STOP_FOREGROUND_REMOVE)
                    else {
                        // If Shizuku died, try the normal launch and retain a tappable result notification
                        // because Android may reject a background launch without shell privileges.
                        runCatching {startActivity(returnIntent)}
                        stopForeground(STOP_FOREGROUND_DETACH)
                        nm.notify(1,Notification.Builder(this,"repair").setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_result))
                            .setContentIntent(open).setAutoCancel(true).build())
                    }
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
}
