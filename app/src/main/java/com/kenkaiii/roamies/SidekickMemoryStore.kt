package com.kenkaiii.roamies

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class MemoryItem(
    val id: String,
    val fact: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ReminderItem(
    val id: String,
    val text: String,
    val triggerTimeMs: Long
)

class SidekickMemoryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("roamies_memory", Context.MODE_PRIVATE)

    fun getMemories(): List<MemoryItem> {
        val raw = prefs.getString("memories", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<MemoryItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                MemoryItem(
                    id = obj.getString("id"),
                    fact = obj.getString("fact"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    fun addMemory(fact: String) {
        val list = getMemories().toMutableList()
        list.add(MemoryItem(id = System.currentTimeMillis().toString(), fact = fact))
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("fact", it.fact)
                put("createdAt", it.createdAt)
            })
        }
        prefs.edit().putString("memories", arr.toString()).apply()
    }

    fun clearMemories() {
        prefs.edit().remove("memories").apply()
    }

    fun scheduleReminder(text: String, delaySeconds: Long) {
        val triggerAt = System.currentTimeMillis() + (delaySeconds * 1000)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.kenkaiii.roamies.REMINDER"
            putExtra("reminder_text", text)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }
}
