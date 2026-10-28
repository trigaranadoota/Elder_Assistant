package com.sarvam.guide.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Medicine(
    val id: Int,
    val name: String,
    val hour: Int,
    val minute: Int,
)

object MedStore {
    private const val PREFS = "guide"
    private const val KEY = "meds"

    fun load(context: Context): MutableList<Medicine> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Medicine>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Medicine(
                        id = o.getInt("id"),
                        name = o.getString("name"),
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                    ),
                )
            }
        }
        return out
    }

    private fun save(context: Context, meds: List<Medicine>) {
        val arr = JSONArray()
        for (m in meds) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("hour", m.hour)
                    .put("minute", m.minute),
            )
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, name: String, hour: Int, minute: Int): Medicine {
        val meds = load(context)
        val med = Medicine((System.currentTimeMillis() % 1_000_000_000L).toInt(), name, hour, minute)
        meds.add(med)
        save(context, meds)
        return med
    }

    fun remove(context: Context, id: Int) {
        val meds = load(context).filterNot { it.id == id }
        save(context, meds)
    }
}

object ReminderScheduler {
    private const val ACTION_REMIND = "com.sarvam.guide.REMIND"

    fun rescheduleAll(context: Context) {
        MedStore.load(context).forEach { schedule(context, it) }
    }

    fun schedule(context: Context, med: Medicine) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, med.hour)
            set(Calendar.MINUTE, med.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pendingIntent(context, med)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun cancel(context: Context, med: Medicine) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, med))
    }

    private fun pendingIntent(context: Context, med: Medicine): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra("id", med.id)
            putExtra("name", med.name)
            putExtra("hour", med.hour)
            putExtra("minute", med.minute)
        }
        return PendingIntent.getBroadcast(
            context,
            med.id,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
