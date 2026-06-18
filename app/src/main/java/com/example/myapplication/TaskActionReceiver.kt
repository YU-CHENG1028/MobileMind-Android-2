package com.example.myapplication

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TASK_START = "COM_MOBILEMIND_TASK_START"
        const val ACTION_TASK_CANCEL = "COM_MOBILEMIND_TASK_CANCEL"
        const val NOTIFICATION_ID = 1001
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        // 點完按鈕先把通知關掉
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        val currentTime = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault()
        ).format(Date())

        when (intent?.action) {
            ACTION_TASK_START -> {
                Log.d("TaskActionReceiver", "使用者點了【開始任務】")
                val payload = UserConfirmPayload(
                    userconfirm = true,
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }

            ACTION_TASK_CANCEL -> {
                Log.d("TaskActionReceiver", "使用者點了【取消任務】")
                val payload = UserConfirmPayload(
                    userconfirm = false,  // ← false
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }
        }
    }
}