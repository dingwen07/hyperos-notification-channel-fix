package net.extrawdw.apps.miuisucks.notificationchannel

import android.annotation.SuppressLint
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class RepairPlan(val packageName: String, val snapshot: String, val channels: List<PlannedChannel>)

internal class PlannedChannel(
    val data: JSONObject,
    val historyKey: String,
    val signature: String,
    val previouslyFixed: Boolean,
) {
    val id = data.getString("id")

    fun skipReason(skipFixed: Boolean): Int? = when {
        ChannelPolicy.isMiPush(id) -> R.string.skip_mipush
        !data.getBoolean("eligible") -> R.string.skip
        skipFixed && previouslyFixed -> R.string.skip_fixed
        else -> null
    }
}

@SuppressLint("UseKtx") // The KTX edit helper discards the disk commit result.
internal class RepairHistory(private val preferences: SharedPreferences) {
    fun plan(snapshot: JSONObject): RepairPlan {
        val pkg = snapshot.getString("package")
        val uid = snapshot.getInt("uid")
        val installed = snapshot.optLong("firstInstallTime")
        val channels = snapshot.getJSONArray("channels")
        val planned = List(channels.length()) { index ->
            val channel = channels.getJSONObject(index)
            val key = JSONArray().put(pkg).put(channel.getString("id")).toString()
            // The parcel includes Settings bookkeeping that can change during a successful repair.
            val signature = JSONArray().put(uid).put(installed).put(channel.getInt("importance"))
                .put(channel.get("soundUri")).put(channel.getBoolean("vibrate"))
                .put(channel.getInt("visibility")).toString()
            PlannedChannel(channel, key, signature, preferences.getString(key, null) == signature)
        }
        return RepairPlan(pkg, snapshot.toString(), planned)
    }

    fun clearPackages(packages: Set<String>) {
        val editor = preferences.edit()
        for (key in preferences.all.keys) {
            if (JSONArray(key).getString(0) in packages) editor.remove(key)
        }
        check(editor.commit()) { "Could not clear channel repair history" }
    }

    // A failed or interrupted retry must be offered again, even if it succeeded in the past.
    fun forgetBeforeAttempt(channel: PlannedChannel) {
        if (preferences.contains(channel.historyKey)) {
            check(preferences.edit().remove(channel.historyKey).commit()) { "Could not update channel repair history" }
        }
    }

    fun recordVerified(channel: PlannedChannel) {
        check(preferences.edit().putString(channel.historyKey, channel.signature).commit()) { "Could not save verified channel" }
    }
}
