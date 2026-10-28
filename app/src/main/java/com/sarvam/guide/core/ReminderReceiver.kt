package com.sarvam.guide.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sarvam.guide.service.GuideService

/** Fires at the medicine time: reschedules for tomorrow and speaks the reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Lang.init(context)
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name").orEmpty()
        val hour = intent.getIntExtra("hour", 0)
        val minute = intent.getIntExtra("minute", 0)
        if (id != 0) {
            ReminderScheduler.schedule(context, Medicine(id, name, hour, minute))
        }
        val svc = Intent(context, GuideService::class.java).apply {
            action = GuideService.ACTION_REMINDER
            putExtra(GuideService.EXTRA_MED_NAME, name)
        }
        runCatching { ContextCompat.startForegroundService(context, svc) }
    }
}

/** Re-arms every medicine alarm after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Lang.init(context)
        ReminderScheduler.rescheduleAll(context)
    }
}
