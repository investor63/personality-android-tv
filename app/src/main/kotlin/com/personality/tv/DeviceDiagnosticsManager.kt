package com.personality.tv

import android.content.Context
import android.util.Log

object DeviceDiagnosticsManager {
    @Synchronized
    fun record(context: Context, event: String) {
        Log.i("personality", event)
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val previous = prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        val entries = (previous + "${System.currentTimeMillis()} $event").takeLast(80)
        prefs.edit().putString("events", entries.joinToString("\n")).apply()
    }

    fun events(context: Context): String =
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getString("events", "").orEmpty()
}
