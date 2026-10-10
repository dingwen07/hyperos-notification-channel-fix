package net.extrawdw.apps.miuisucks.notificationchannel

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs the real foreground runner against one disposable fixture channel, without UiAutomation. */
class RepairReturnTest {
    @Test fun completionReturnsToAppWithResult()=runRepair(stale=false)
    @Test fun channelErrorReturnsToAppWithError()=runRepair(stale=true)

    private fun runRepair(stale: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairFixture")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        run {
            instrumentation.runOnMainSync {RepairState.connect()}
            val connectDeadline=SystemClock.uptimeMillis()+15000
            while(RepairState.bridge==null && SystemClock.uptimeMillis()<connectDeadline)SystemClock.sleep(100)
            val service=checkNotNull(RepairState.bridge){"Grant Shizuku access before running fixture tests"}
            // HyperOS blocks the instrumentation UID's ActivityScenario launch from the background.
            // Use the same fixed shell launcher as repair completion, without acquiring UiAutomation.
            service.returnToApp()
            val resumedDeadline=SystemClock.uptimeMillis()+10000
            var resumed=false
            while(!resumed && SystemClock.uptimeMillis()<resumedDeadline) {
                instrumentation.runOnMainSync {
                    resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any {it is MainActivity}
                }
                if(!resumed)SystemClock.sleep(100)
            }
            assertTrue("Could not launch the repair app for the test",resumed)
            val pkg=BuildConfig.APPLICATION_ID+".testapp"
            val snapshot=RepairState.data(service.inspect(pkg)) as JSONObject
            val channels=snapshot.getJSONArray("channels")
            val channel=(0 until channels.length()).map {channels.getJSONObject(it)}.single {it.getString("id")=="high_custom"}
            val original=NotificationAccess.decode(channel.getString("parcel"))
            if(stale)channel.put("parcel",NotificationAccess.encode(
                NotificationAccess.decode(channel.getString("parcel")).apply {importance=2}))
            snapshot.put("channels",JSONArray().put(channel))
            instrumentation.runOnMainSync {
                assertFalse(RepairState.hasJournal())
                RepairState.plans=listOf(snapshot.toString())
                RepairState.app.startForegroundService(Intent(RepairState.app,RepairRunner::class.java))
            }
            val deadline=SystemClock.uptimeMillis()+90000
            var done=false
            while(!done && SystemClock.uptimeMillis()<deadline) {
                instrumentation.runOnMainSync {done=!RepairState.running && RepairState.plans.isEmpty()}
                if(!done)SystemClock.sleep(100)
            }
            assertTrue("Foreground repair timed out: ${RepairState.status}",done)
            instrumentation.runOnMainSync {
                assertTrue("App was not brought back to the foreground",
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any {it is MainActivity})
                if(stale) {
                    assertEquals(R.string.error_channel_changed,RepairState.errorRes)
                    assertEquals(RepairState.app.getString(R.string.error_channel_changed),RepairState.status)
                } else {
                    assertNull(RepairState.errorRes)
                    assertEquals(RepairState.app.getString(R.string.completed,1),RepairState.status)
                }
            }
            assertFalse(RepairState.hasJournal())
            val after=(RepairState.data(service.inspect(pkg)) as JSONObject).getJSONArray("channels")
            val saved=(0 until after.length()).map {after.getJSONObject(it)}.single {it.getString("id")=="high_custom"}
            assertTrue(NotificationAccess.sameFields(original,NotificationAccess.decode(saved.getString("parcel"))))
        }
    }
}
