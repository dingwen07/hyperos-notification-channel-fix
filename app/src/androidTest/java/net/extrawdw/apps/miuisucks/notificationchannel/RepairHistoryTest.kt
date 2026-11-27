package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RepairHistoryTest {
    @Test fun remembersEachUserSeparatelyAndRejectsARecreatedProfileOrClosedGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "profile_repair_history_test"
        try {
            val history = RepairHistory(context.getSharedPreferences(file, Context.MODE_PRIVATE))
            fun snapshot(id: Int, serial: Long) = JSONObject().put("package","test.app")
                .put("userId",id).put("userSerial",serial).put("uid",id * 100000 + 10001)
                .put("channels",JSONArray().put(JSONObject().put("id","shared").put("importance",4)
                    .put("soundUri",JSONObject.NULL).put("vibrate",true).put("visibility",-1000)))
            val owner = snapshot(0,0)
            val work = snapshot(16,50)
            val clone = snapshot(999,20)
            fun current(s: JSONObject) = history.plan(s).channels.single()
            history.recordVerified(current(owner))
            assertFalse(current(work).previouslyFixed)
            history.recordVerified(current(work))
            assertFalse(current(clone).previouslyFixed)
            history.recordVerified(current(clone))
            assertTrue(listOf(owner,work,clone).all { current(it).previouslyFixed })
            assertFalse(current(snapshot(16,51)).previouslyFixed)
            owner.getJSONArray("channels").getJSONObject(0).put("requiredGatesOpen",false)
            assertFalse(current(owner).previouslyFixed)
            assertTrue(current(work).previouslyFixed)
            history.clearPackages(setOf("test.app"))
            assertFalse(current(work).previouslyFixed)
            assertFalse(current(clone).previouslyFixed)
        } finally {
            context.deleteSharedPreferences(file)
        }
    }

    @Test fun fullRepairClearsSelectedPackagesAndRebuildsHistoryFromVerifiedChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("full_repair_history_test", Context.MODE_PRIVATE)
        try {
            val history = RepairHistory(preferences)
            fun plan(pkg: String, vararg ids: String): RepairPlan {
                val channels = JSONArray()
                for (id in ids) {
                    channels.put(JSONObject().put("id", id).put("importance", 4)
                        .put("soundUri", JSONObject.NULL).put("vibrate", true).put("visibility", -1000))
                }
                return history.plan(JSONObject().put("package", pkg).put("uid", 10001)
                    .put("firstInstallTime", 123).put("channels", channels))
            }

            val retired = "old:\"channel\""
            val packages = listOf("test.app", "test.second", "test.app.other")
            for (pkg in packages) {
                plan(pkg, "first", "pending", retired).channels.forEach(history::recordVerified)
            }
            val selected = listOf(plan("test.app", "first", "pending"), plan("test.second"))
            history.clearPackages(selected.map { it.packageName }.toSet())
            history.recordVerified(selected.first().channels.first())

            for ((pkg, expected) in listOf(
                "test.app" to listOf(true, false, false),
                "test.second" to listOf(false, false, false),
                "test.app.other" to listOf(true, true, true),
            )) {
                assertEquals(pkg, expected, plan(pkg, "first", "pending", retired).channels.map { it.previouslyFixed })
            }
        } finally {
            context.deleteSharedPreferences("full_repair_history_test")
        }
    }

    @Test fun remembersOnlyVerifiedChannelsForTheSameInstallAndSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("repair_history_test", Context.MODE_PRIVATE)
        try {
            val history = RepairHistory(preferences)
            val first = JSONObject().put("id", "messages").put("importance", 4)
                .put("soundUri", JSONObject.NULL).put("vibrate", true).put("visibility", -1000).put("eligible", true)
            val second = JSONObject(first.toString()).put("id", "other")
            val snapshot = JSONObject().put("package", "test.app").put("uid", 10001).put("firstInstallTime", 123)
                .put("channels", JSONArray().put(first).put(second))
            val plan = history.plan(snapshot)
            assertTrue(plan.channels.all { it.skipReason(true) == null })
            history.recordVerified(plan.channels.first())

            val reopened = RepairHistory(preferences)
            fun reasons() = reopened.plan(snapshot).channels.map { it.skipReason(true) }
            assertEquals(listOf(R.string.skip_fixed, null), reasons())
            assertNull(reopened.plan(snapshot).channels.first().skipReason(false))
            first.put("eligible", false)
            assertEquals(R.string.skip, reopened.plan(snapshot).channels.first().skipReason(false))
            first.put("eligible", true).put("name", "Renamed")
            assertEquals(listOf(R.string.skip_fixed, null), reasons())
            first.put("importance", 3)
            assertEquals(listOf(null, null), reasons())
            first.put("importance", 4)
            snapshot.put("package", "other.app")
            assertEquals(listOf(null, null), reasons())
            snapshot.put("package", "test.app").put("firstInstallTime", 456)
            assertEquals(listOf(null, null), reasons())

            snapshot.put("firstInstallTime", 123)
            reopened.forgetBeforeAttempt(reopened.plan(snapshot).channels.first())
            assertEquals(listOf(null, null), reasons())
        } finally {
            context.deleteSharedPreferences("repair_history_test")
        }
    }
}
