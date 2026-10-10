package net.extrawdw.apps.miuisucks.notificationchannel

import org.junit.Assert.*
import org.junit.Test

class ChannelPolicyTest {
    @Test fun unspecifiedVisibilityAllowsContentAndExplicitOverridesStayExact() {
        assertEquals(1,ChannelPolicy.resolveVisibility(-1000))
        for(value in listOf(-1,0,1))assertEquals(value,ChannelPolicy.resolveVisibility(value))
    }
    @Test fun importanceThresholdsPreserveSilentChannelsDespiteSavedAlertFields() {
        for(importance in 0..5) {
            val p=ChannelPolicy(importance,true,true,1,true,false,"test")
            assertEquals(importance>0,p.eligible)
            assertEquals(importance>=4,p.popup)
            assertEquals(importance>=3,p.sound)
            assertEquals(importance>=3,p.vibrate)
        }
    }
    @Test fun highImportanceDoesNotInventSoundVibrationOrLockscreenVisibility() {
        val p=ChannelPolicy(4,false,false,-1,true,false,"test")
        assertTrue(p.popup);assertFalse(p.sound);assertFalse(p.vibrate);assertFalse(p.lockscreen)
    }
    @Test fun blockedAppsGroupsAndLegacyChannelsAreSkipped() {
        assertFalse(ChannelPolicy(4,true,true,1,false,false,"test").eligible)
        assertFalse(ChannelPolicy(4,true,true,1,true,true,"test").eligible)
        assertFalse(ChannelPolicy(4,true,true,1,true,false,"miscellaneous").eligible)
    }
    @Test fun miPushPrefixSkipsAllChannelActionsWithoutMatchingUnrelatedIds() {
        for(id in listOf("mipush|","mipush|test.app|news")) {
            val p=ChannelPolicy(4,true,true,1,true,false,id)
            assertFalse(p.eligible);assertFalse(p.popup);assertFalse(p.sound)
            assertFalse(p.vibrate);assertFalse(p.lockscreen)
        }
        for(id in listOf("mipush","mipush_news","other|mipush|news"))
            assertTrue(ChannelPolicy(4,true,true,1,true,false,id).eligible)
    }
    @Test fun unspecifiedAndPrivateVisibilityAllowLockscreenWithoutChangingVisibility() {
        for(visibility in listOf(-1000,0)) {
            val p=ChannelPolicy(3,true,false,visibility,true,false,"test")
            assertTrue(p.lockscreen);assertEquals(visibility,p.visibility)
        }
    }
    @Test fun policyParserUsesExactPackageAndHonorsAppDenial() {
        val p=XiaomiPolicy.parse("test.app","""
            mAllowFloatPackages: [test.app, test.app]
            mAllowKeyguardPackages: []
            test.app_c_sound=true
            test.app_c_keyguard=true
            test.app_sound=false
            test.application_c_channel_flag=1
        """.trimIndent())
        assertTrue(p.allowed("test.app","c","channel_flag"))
        assertFalse(p.allowed("test.app","c","sound"))
        assertTrue(p.allowed("test.app","c","keyguard"))
        assertFalse(p.values.containsKey("test.application_c_channel_flag"))
    }
    @Test(expected=RepairFailure::class) fun missingPolicyListsAreNotAnEmptyAllowlist() {
        XiaomiPolicy.parse("test.app","AppNotificationSettings:\ntest.app_c_sound=true")
    }
}
