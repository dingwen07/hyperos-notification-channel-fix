package net.extrawdw.apps.miuisucks.notificationchannel

/** Android intent before Xiaomi's additional gates; independent of Android classes. */
class ChannelPolicy(importance: Int, hasSound: Boolean, vibration: Boolean, val visibility: Int,
                    appEnabled: Boolean, groupBlocked: Boolean, channelId: String) {
    val eligible = appEnabled && !groupBlocked && channelId != "miscellaneous" && !isMiPush(channelId) && importance > 0
    val popup = eligible && importance >= 4
    val sound = eligible && importance >= 3 && hasSound
    val vibrate = eligible && importance >= 3 && vibration
    val lockscreen = eligible && visibility != -1
    companion object {
        fun isMiPush(channelId: String): Boolean = channelId.startsWith("mipush|")
        // No channel override means Xiaomi should allow content; system privacy remains authoritative.
        fun resolveVisibility(channelVisibility: Int): Int = if(channelVisibility == -1000) 1 else channelVisibility
    }
}
