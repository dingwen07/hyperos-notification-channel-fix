package net.extrawdw.apps.miuisucks.notificationchannel.testapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val manager=getSystemService(NotificationManager::class.java)
        val audio=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val custom=Uri.parse("android.resource://$packageName/raw/custom_chime")
        val default=Settings.System.DEFAULT_NOTIFICATION_URI
        fun channel(id: String,label: Int,importance: Int,sound: Uri?,vibration: Boolean,visibility: Int,badge: Boolean=true)=
            NotificationChannel(id,getString(label),importance).apply {
                setSound(sound,audio);setShowBadge(badge);lockscreenVisibility=visibility
                if(vibration)vibrationPattern=longArrayOf(0,80,70,160)
                enableVibration(vibration)
            }
        val channels=listOf(
            channel("high_custom",R.string.high_custom,4,custom,true,1),
            channel("high_default",R.string.high_default,4,default,false,1),
            channel("high_silent",R.string.high_silent,4,null,false,1),
            channel("default_custom",R.string.default_custom,3,custom,false,1),
            channel("default_vibration",R.string.default_vibration,3,null,true,0),
            channel("low_custom",R.string.low_custom,2,custom,true,1),
            channel("minimum",R.string.minimum,1,custom,false,-1000),
            channel("blocked",R.string.blocked,0,custom,true,1),
            channel("secret",R.string.secret,4,custom,true,-1),
            channel("private",R.string.private_channel,4,default,true,0),
            channel("unspecified",R.string.unspecified,3,default,false,-1000),
            channel("no_badge",R.string.no_badge,4,default,false,1,false))
        manager.createNotificationChannels(channels)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1)
        setContent {MaterialTheme {Scaffold {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineMedium);Text(stringResource(R.string.intro))}
                itemsIndexed(channels) {index,c->
                    Button(onClick={
                        val visibility=when(c.lockscreenVisibility) {
                            Notification.VISIBILITY_SECRET->Notification.VISIBILITY_SECRET
                            Notification.VISIBILITY_PUBLIC->Notification.VISIBILITY_PUBLIC
                            else->Notification.VISIBILITY_PRIVATE
                        }
                        manager.notify(index+100,Notification.Builder(this@MainActivity,c.id)
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(getString(R.string.notification_title,c.name))
                            .setContentText(getString(R.string.notification_text)).setVisibility(visibility).setAutoCancel(true).build())
                    },modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.post,c.name))}
                }
            }
        }}}
    }
}
