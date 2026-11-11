package com.personality.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SleepTimer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            restore(context)
            return
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val expected = intent.getLongExtra("deadline", -1)
        if (expected != prefs.getLong("timer", 0)) return
        val action = prefs.getString("timer_action", null) ?: return
        cancel(context)
        ActionRegistry.run(context, action)
    }

    companion object {
        private fun alarm(context: Context, deadline: Long = 0): PendingIntent =
            PendingIntent.getBroadcast(context, 0,
                Intent(context, SleepTimer::class.java).putExtra("deadline", deadline),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun start(context: Context, minutes: Int, action: String) {
            require(minutes > 0)
            val deadline = System.currentTimeMillis() + minutes * 60_000L
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putLong("timer", deadline).putString("timer_action", action)
                .putInt("timer_minutes", minutes).putString("last_timer_action", action).apply()
            schedule(context, deadline)
        }

        private fun schedule(context: Context, deadline: Long) {
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, alarm(context, deadline))
        }

        fun restore(context: Context) {
            val deadline = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("timer", 0)
            if (deadline > System.currentTimeMillis()) schedule(context, deadline)
            else if (deadline > 0) cancel(context)
        }

        fun cancel(context: Context) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .remove("timer").remove("timer_action").apply()
            context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
        }
    }
}
