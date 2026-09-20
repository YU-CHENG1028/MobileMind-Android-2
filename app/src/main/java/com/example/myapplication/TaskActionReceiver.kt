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

        // 敏感操作確認（action_check）用的 action 與通知 ID，跟任務開始的通知分開，
        // 避免兩種通知互相蓋掉或誤 cancel 到對方
        const val ACTION_SENSITIVE_CONFIRM = "COM_MOBILEMIND_SENSITIVE_CONFIRM"
        const val ACTION_SENSITIVE_CANCEL = "COM_MOBILEMIND_SENSITIVE_CANCEL"
        const val NOTIFICATION_ID_SENSITIVE = 1002
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        val currentTime = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault()
        ).format(Date())

        when (intent?.action) {
            ACTION_TASK_START -> {
                notificationManager.cancel(NOTIFICATION_ID)
                Log.d("TaskActionReceiver", "使用者點了【開始任務】")
                val payload = UserConfirmPayload(
                    userconfirm = true,
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }

            ACTION_TASK_CANCEL -> {
                notificationManager.cancel(NOTIFICATION_ID)
                Log.d("TaskActionReceiver", "使用者點了【取消任務】")
                val payload = UserConfirmPayload(
                    userconfirm = false,  // ← false
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }

            ACTION_SENSITIVE_CONFIRM -> {
                notificationManager.cancel(NOTIFICATION_ID_SENSITIVE)
                Log.d("TaskActionReceiver", "使用者點了【確認執行】(敏感操作)")
                val payload = SensitiveConfirmPayload(
                    requestResponse = true,
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }

            ACTION_SENSITIVE_CANCEL -> {
                notificationManager.cancel(NOTIFICATION_ID_SENSITIVE)
                Log.d("TaskActionReceiver", "使用者點了【取消任務】(敏感操作)")
                val payload = SensitiveConfirmPayload(
                    requestResponse = false,
                    sentTime = currentTime
                )
                ConnectionHolder.webSocket?.send(Gson().toJson(payload))
            }
        }
    }
}