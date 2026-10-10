package net.extrawdw.apps.miuisucks.notificationchannel

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed internal commands only. There is no arbitrary command endpoint in the Binder API. */
internal object Command {
    fun run(vararg args: String): String {
        val process=ProcessBuilder(*args).redirectErrorStream(true).start()
        val reader=Executors.newSingleThreadExecutor()
        val output=reader.submit<String> {
            process.inputStream.use { input ->
                val bytes=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val n=input.read(buffer)
                    if(n<0) break
                    check(bytes.size()+n<=8*1024*1024) { "System output exceeds 8 MiB" }
                    bytes.write(buffer,0,n)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }
        try {
            if(!process.waitFor(20,TimeUnit.SECONDS)) fail(R.string.error_command_timeout)
            val result=output.get(2,TimeUnit.SECONDS)
            check(process.exitValue()==0) { "System command failed: ${args.first()}" }
            return result
        } finally { process.destroy(); reader.shutdownNow() }
    }
}
