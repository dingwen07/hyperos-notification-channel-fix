package net.extrawdw.apps.miuisucks.notificationchannel

import androidx.annotation.StringRes

/** Binder returns a resource ID and arguments; the app formats them in its own UI language. */
internal class RepairFailure(@param:StringRes val messageId: Int, vararg val arguments: String) : Exception()
internal fun fail(@StringRes messageId: Int, vararg args: String): Nothing = throw RepairFailure(messageId, *args)
