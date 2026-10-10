package net.extrawdw.apps.miuisucks.notificationchannel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RepairPreconditionsTest {
    @Test fun contentVisiblePasses() {
        RepairPreconditions.requireGlobalLockscreen("1","1")
    }

    @Test fun hiddenContentDisabledNotificationsAndUnsetDefaultsStopRepair() {
        for(show in listOf("0","1","null"))for(content in listOf("0","1","null")) {
            if(show=="1" && content=="1")continue
            val failure=assertThrows(RepairFailure::class.java) {
                RepairPreconditions.requireGlobalLockscreen(show,content)
            }
            assertEquals(R.string.error_global_lockscreen,failure.messageId)
        }
    }

    @Test fun unexpectedReadResultIsNotTreatedAsUserPrivacyChoice() {
        for(values in listOf("permission denied" to "1","1" to "", "2" to "1")) {
            val failure=assertThrows(RepairFailure::class.java) {
                RepairPreconditions.requireGlobalLockscreen(values.first,values.second)
            }
            assertEquals(R.string.error_global_lockscreen_read,failure.messageId)
        }
    }
}
