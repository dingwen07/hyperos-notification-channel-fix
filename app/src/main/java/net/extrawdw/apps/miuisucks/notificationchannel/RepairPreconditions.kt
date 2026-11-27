package net.extrawdw.apps.miuisucks.notificationchannel

internal object RepairPreconditions {
    fun requireGlobalLockscreen(showNotifications: String,allowContent: String) {
        val known=setOf("0","1","null")
        if(showNotifications !in known || allowContent !in known)fail(R.string.error_global_lockscreen_read)
        // Xiaomi removes channel choices according to these global flags. Stop before partial
        // repair when required controls are unavailable; never change global privacy here.
        if(showNotifications!="1" || allowContent!="1")fail(R.string.error_global_lockscreen)
    }
}
