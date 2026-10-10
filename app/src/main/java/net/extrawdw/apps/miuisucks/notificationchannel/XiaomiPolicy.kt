package net.extrawdw.apps.miuisucks.notificationchannel

/** Reads only policy lines. No complete system dump or notification content is persisted. */
internal class XiaomiPolicy {
    val values=mutableMapOf<String,String>()
    private val floating=mutableSetOf<String>()
    private val keyguard=mutableSetOf<String>()
    fun allowed(pkg: String,channel: String,suffix: String): Boolean {
        val app=if(suffix=="channel_flag")pkg else "${pkg}_$suffix"
        if(values[app] in setOf("false","1"))return false
        values["${pkg}_${channel}_$suffix"]?.let { return if(suffix=="channel_flag")it=="2" else it=="true" }
        return pkg in if(suffix=="keyguard")keyguard else floating
    }
    companion object {
        fun read(pkg: String): XiaomiPolicy {
            val dump=Command.run("/system/bin/dumpsys","activity","service","com.android.systemui/.SystemUIService")
            return parse(pkg,dump)
        }
        fun parse(pkg: String,dump: String): XiaomiPolicy {
            val result=XiaomiPolicy();var floatParsed=false;var keyguardParsed=false
            val packageRegex=Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
            dump.lineSequence().map(String::trim).forEach { s ->
                if(s.startsWith("mAllowFloatPackages:")) {result.floating+=packageRegex.findAll(s.substringAfter(':')).map {it.value};floatParsed=true}
                if(s.startsWith("mAllowKeyguardPackages:")) {result.keyguard+=packageRegex.findAll(s.substringAfter(':')).map {it.value};keyguardParsed=true}
                if(s.contains('=')&&(s.startsWith("${pkg}_")||s.startsWith("$pkg=")))result.values[s.substringBefore('=')]=s.substringAfter('=').trim()
            }
            if(!floatParsed||!keyguardParsed)fail(R.string.error_policy_format)
            return result
        }
    }
}
