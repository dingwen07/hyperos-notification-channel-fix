package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.os.IBinder

/** Debug-only shell entry point. Seeds privacy cases that an ordinary app cannot create. */
object FixtureShell {
    // Runs as shell only; seeding another app's channel privacy requires the hidden notification Binder.
    @SuppressLint("PrivateApi")
    @JvmStatic fun main(args: Array<String>) {
        check(android.os.Process.myUid()==2000)
        check(args.contentEquals(arrayOf("seed-privacy")))
        val pkg=BuildConfig.APPLICATION_ID+".testapp"
        val binder=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,"notification")
        val service=Class.forName("android.app.INotificationManager\$Stub").getMethod("asInterface",IBinder::class.java).invoke(null,binder)
        val type=Class.forName("android.app.INotificationManager")
        val get=type.methods.single {it.name=="getNotificationChannel"&&it.parameterCount==4}
        val update=type.methods.single {it.name=="updateNotificationChannelForPackage"&&it.parameterCount==3}
        val uid=Regex("uid:(\\d+)").find(Command.run("/system/bin/pm","list","packages","-U",pkg))!!.groupValues[1].toInt()
        for((id,visibility) in mapOf("high_custom" to 1,"private" to 0,"secret" to -1,"unspecified" to -1000)) {
            val channel=get.invoke(service,"com.android.shell",0,pkg,id) as NotificationChannel
            channel.lockscreenVisibility=visibility
            update.invoke(service,pkg,uid,channel)
            val saved=get.invoke(service,"com.android.shell",0,pkg,id) as NotificationChannel
            // NotificationManager normalizes PUBLIC to NO_OVERRIDE on this build.
            check(saved.lockscreenVisibility==visibility || visibility==1&&saved.lockscreenVisibility == -1000) {
                "$id requested=$visibility saved=${saved.lockscreenVisibility}"
            }
            println("$id visibility=${saved.lockscreenVisibility} verified")
        }
    }
}
