package com.example.myapplication // 請更換為你專案實際的 package 名稱

import androidx.core.app.NotificationCompat
import com.google.gson.annotations.SerializedName

/**
 * 共同協定：所有傳送與接收的 JSON 都包含 "sent_time" 欄位，型態為 str (String)
 */

// =========================================================================
//  【後端送至前端】資料模型 (Backend -> APP)
// =========================================================================

// 1. 初始化連線訊息 (Initial Message)
data class InitialMessage(
    @SerializedName("type") val type: String = "initial_message",
    @SerializedName("source") val source: String,
    @SerializedName("message") val message: String,
    @SerializedName("sent_time") val sentTime: String
)

// 2. 詢問使用者任務之缺少細節 (用途：詢問使用者任務規範)
data class AskUserMessage(
    @SerializedName("type") val type: String = "ask_user",
    @SerializedName("ask_messages") val askMessages: String,
    @SerializedName("sent_time") val sentTime: String
)

// 3. 通知 APP 任務開始 (用途：通知 APP 任務開始開始)
data class TaskStartMessage(
    @SerializedName("type") val type: String = "task_start",
    @SerializedName("sent_time") val sentTime: String
)

// 4. 告知 APP 讀取 UI 頁面與截圖 (用途：讀取 UI Tree)
data class ReadUiMessage(
    @SerializedName("type") val type: String = "read_ui",
    @SerializedName("sent_time") val sentTime: String
)

// 5. 通知使用者敏感操作需要確認 (用途：通知使用者需確認)
data class ActionCheckMessage(
    @SerializedName("type") val type: String = "action_check",
    @SerializedName("action_detail") val actionDetail: String, // (元件、類型、輸入內容)
    @SerializedName("sensitive_reason") val sensitiveReason: String, // 為敏感操作的原因
    @SerializedName("sent_time") val sentTime: String
)

// 6. 發送操作指令給 APP (用途：發送操作指令)
data class OperateCommandMessage(
    @SerializedName("type") val type: String = "operate_command",
    @SerializedName("current_action") val currentAction: Action, // 操作指令（亦可改為自訂的 Action Object）
    @SerializedName("sent_time") val sentTime: String
)

// 7. 關閉 APP 進程，告知使用者執行結果 (用途：結束工作)
data class TaskEndMessage(
    @SerializedName("type") val type: String = "task_end",
    @SerializedName("task_result") val taskResult: String, // 任務結果
    @SerializedName("task_process") val taskProcess: String, // 任務逐步步數/執行步數/失敗步數
    @SerializedName("error_reason") val errorReason: String?, // 失敗原因(若失敗)，允許為 null
    @SerializedName("sent_time") val sentTime: String
)


// =========================================================================
// 【前端送至後端】資料模型 (APP -> Backend)
// =========================================================================

// 1. 使用者回應缺少之細節
data class DetailResponsePayload(
    @SerializedName("type") val type: String = "detail_response",
    @SerializedName("detail_response") val detailResponse: String, // 使用者回復之具體細節
    @SerializedName("sent_time") val sentTime: String
)
data class UserConfirmPayload(
    @SerializedName("type") val type: String = "user_confirm_start",
    @SerializedName("user_confirm") val userconfirm: Boolean, // 使用者回復之具體細節
    @SerializedName("sent_time") val sentTime: String
)

// 2. 接收 UI 樹與畫面截圖回傳
data class UiScreenDataPayload(
    @SerializedName("type") val type: String = "ui_screen_data",
    @SerializedName("ui_tree") val uiTree: String, // UI 樹結構 (對應表格中的 dictory，前端可以傳轉好的 JSON 字串)
    @SerializedName("screen_shot") val screenShot: String, // 螢幕截圖 Base64
    @SerializedName("sent_time") val sentTime: String
)

// 3. 使用者確認敏感操作結果
data class SensitiveConfirmPayload(
    @SerializedName("type") val type: String = "sensitive_confirm",
    @SerializedName("request_response") val requestResponse: Boolean, // True / False
    @SerializedName("sent_time") val sentTime: String
)

// 4. APP 操作後回傳畫面截圖
data class OperateScreenShotPayload(
    @SerializedName("type") val type: String = "operate_screen_shot",
    @SerializedName("operate_screen_shot") val operateScreenShot: String, // 螢幕截圖 Base64
    @SerializedName("sent_time") val sentTime: String
)