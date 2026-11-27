package net.extrawdw.apps.miuisucks.notificationchannel

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device test; only operates this repository's disposable test-app package. */
class FixtureRepairTest {
    @Test fun repairsEachVisibleFixtureCopyUsingItsOwnChannel() {
        val args = InstrumentationRegistry.getArguments()
        val expected = args.getString("fixtureUsers")?.split(",")?.map(String::toInt)
        assumeTrue(args.getString("repairFixture") == "true" && expected != null)
        val pkg = BuildConfig.APPLICATION_ID + ".testapp"
        InstrumentationRegistry.getInstrumentation().runOnMainSync { RepairState.connect() }
        val deadline = SystemClock.uptimeMillis() + 15000
        while (RepairState.bridge == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val service = checkNotNull(RepairState.bridge)
        val users = RepairState.data(service.targetProfiles(pkg)) as org.json.JSONArray
        val ids = List(users.length()) { users.getInt(it) }
        assertEquals(expected, ids)
        fun snapshots() = ids.map { RepairState.data(service.inspectProfile(pkg, it)) as JSONObject }
        val id = if (ids.size == 1) "high_custom" else "profile_alerts"
        fun target(snapshot: JSONObject): JSONObject {
            val channels = snapshot.getJSONArray("channels")
            return (0 until channels.length()).map { channels.getJSONObject(it) }.single { it.getString("id") == id }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun runTask(action: () -> Unit) {
            instrumentation.runOnMainSync(action)
            RepairState.worker.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            assertEquals(RepairState.detail, "", RepairState.detail)
        }
        runTask { RepairState.scan() }
        val entry = RepairState.apps.single { it.pkg == pkg }
        runTask { RepairState.selected = setOf(pkg); RepairState.preview() }
        assertEquals(ids, RepairState.plans.map { it.userId })
        assertTrue(RepairState.skipFixed)
        assertEquals(entry.count, RepairState.plans.sumOf { it.channels.size })
        val before = RepairState.plans.map { JSONObject(it.snapshot) }
        assertEquals(ids.size, before.map { target(it).getString("name") }.distinct().size)
        val ordered = before.sortedByDescending { target(it).getBoolean("popup") }
        if (before.any { it.getInt("userId") != it.getInt("hostUserId") && it.getInt("userId") != 999 }) {
            assertEquals(setOf(false,true), before.map { target(it).getBoolean("popup") }.toSet())
            assertEquals(setOf(false,true), before.map { target(it).getBoolean("vibrate") }.toSet())
        }
        val repaired = mutableSetOf<Int>()
        for (snapshot in ordered) {
            val saved = snapshot.toString()
            Log.i("FixtureRepairTest", "Repair user " + snapshot.getInt("userId"))
            RepairState.data(service.repairApp(saved))
            RepairState.saveJournal(saved, id)
            try {
                assertEquals("verified", RepairState.data(service.repairChannel(saved, id)))
                RepairState.clearJournal()
            } catch (e: Exception) {
                RepairState.channelFailure(e)
                throw e
            }
            val after = snapshots()
            repaired += snapshot.getInt("userId")
            after.filter { it.getInt("userId") in repaired }.forEach {
                assertTrue(target(it).optBoolean("requiredGatesOpen",true))
            }
            for ((old, new) in before.zip(after)) {
                assertEquals(old.getInt("uid"), new.getInt("uid"))
                assertTrue(NotificationAccess.sameFields(NotificationAccess.decode(target(old).getString("parcel")),
                    NotificationAccess.decode(target(new).getString("parcel"))))
            }
        }
    }

    @Test fun repairsFixtureAndPreservesAndroidFields() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("repairFixture")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {RepairState.connect()}
        val deadline=SystemClock.uptimeMillis()+15000
        while(RepairState.bridge==null && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100)
        val service=checkNotNull(RepairState.bridge){"Grant Shizuku permission before this opt-in test"}
        val pkg=BuildConfig.APPLICATION_ID+".testapp"
        val snapshot=(RepairState.data(service.inspect(pkg)) as JSONObject).toString()
        RepairState.data(service.repairApp(snapshot))
        val before=JSONObject(snapshot).getJSONArray("channels")
        assertEquals("Launch the fixture app to create all channels before testing",13,before.length())
        // HyperOS forbids ordinary apps from creating mipush| IDs. Submit a reserved ID
        // directly to verify that the shell service skips it before any channel/UI access.
        assertEquals("skipped",RepairState.data(service.repairChannel(snapshot,"mipush|fixture|high")))
        val only=arguments.getString("channel")
        for(i in 0 until before.length()) {
            val c=before.getJSONObject(i)
            val id=c.getString("id")
            if(!c.getBoolean("eligible")) {
                // Exercise the service guard too, even if a caller submits a skipped channel.
                assertEquals("skipped",RepairState.data(service.repairChannel(snapshot,id)))
                continue
            }
            if(only!=null&&only!=id)continue
            Log.i("FixtureRepairTest","START $id")
            RepairState.saveJournal(snapshot,id)
            try {
                assertEquals("verified",RepairState.data(service.repairChannel(snapshot,id)))
                RepairState.clearJournal()
            } catch(e: Exception) {
                RepairState.channelFailure(e)
                throw e
            }
            Log.i("FixtureRepairTest","PASS $id")
        }
        val after=(RepairState.data(service.inspect(pkg)) as JSONObject).getJSONArray("channels")
        assertEquals(before.length(),after.length())
        for(i in 0 until before.length()) {
            val c=before.getJSONObject(i)
            val updated=(0 until after.length()).map {after.getJSONObject(it)}.single {it.getString("id")==c.getString("id")}
            for(key in listOf("importance","soundUri","visibility","badge","eligible","groupBlocked"))
                assertEquals("${c.getString("id")}: $key",c.get(key),updated.get(key))
            val old=NotificationAccess.decode(c.getString("parcel"))
            val current=NotificationAccess.decode(updated.getString("parcel"))
            assertTrue(c.getString("id"),NotificationAccess.sameFields(old,current))
            assertEquals(old.canShowBadge(),current.canShowBadge())
            assertEquals(old.shouldShowLights(),current.shouldShowLights())
            assertEquals(old.canBypassDnd(),current.canBypassDnd())
            assertEquals(old.group,current.group)
            assertEquals(old.conversationId,current.conversationId)
        }
    }

    @Test fun staleSnapshotDoesNotOverwriteCurrentChannel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairFixture")=="true")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {RepairState.connect()}
        val deadline=SystemClock.uptimeMillis()+15000
        while(RepairState.bridge==null && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100)
        val service=checkNotNull(RepairState.bridge)
        val pkg=BuildConfig.APPLICATION_ID+".testapp"
        val snapshot=RepairState.data(service.inspect(pkg)) as JSONObject
        val channels=snapshot.getJSONArray("channels")
        val c=(0 until channels.length()).map {channels.getJSONObject(it)}.single {it.getString("id")=="high_custom"}
        val original=NotificationAccess.decode(c.getString("parcel"))
        val stale=NotificationAccess.decode(c.getString("parcel")).apply {importance=2}
        c.put("parcel",NotificationAccess.encode(stale))
        RepairState.saveJournal(snapshot.toString(),"high_custom")
        try {
            RepairState.data(service.repairChannel(snapshot.toString(),"high_custom"))
            fail("Stale snapshot accepted")
        } catch(e: RemoteOperationException) {
            assertFalse(e.recoveryRequired)
            RepairState.channelFailure(e)
        }
        assertFalse(RepairState.hasJournal())
        val after=(RepairState.data(service.inspect(pkg)) as JSONObject).getJSONArray("channels")
        val current=(0 until after.length()).map {after.getJSONObject(it)}.single {it.getString("id")=="high_custom"}
        assertTrue(NotificationAccess.sameFields(original,NotificationAccess.decode(current.getString("parcel"))))
    }
}
