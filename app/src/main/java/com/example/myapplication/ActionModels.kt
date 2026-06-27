package com.example.myapplication

import com.google.gson.annotations.SerializedName


// ─────────────────────────────────────────────
// 與後端對應的資料類別（對應 Python Pydantic Model）
// ─────────────────────────────────────────────

data class Action(
    @SerializedName("action_type") val actionType: String,
    @SerializedName("resource_id") val resourceId: String? = null,
    @SerializedName("content_description") val contentDescription: String? = null,
    @SerializedName("bounds") val bounds: BoundsXY? = null,
    @SerializedName("input_text") val inputText: String? = null,
    @SerializedName("scroll_direction") val scrollDirection: String? = null
)

data class BoundsXY(
    @SerializedName("x") val x: Int,
    @SerializedName("y") val y: Int
)

// ─────────────────────────────────────────────
// 執行結果封裝
// ─────────────────────────────────────────────

sealed class ActionResult {
    object Success : ActionResult()
    data class Failure(val reason: String) : ActionResult()
}
/*
ActionModels.kt        → 定義 Action / BoundsXY / ActionResult
ActionExecutor.kt       → 使用 Action / ActionResult，不重複定義
MyAccessibilityService.kt → 使用 ActionExecutor + Action / ActionResult
PythonModel.kt          → OperateCommandMessage.currentAction 用 Action 型態
 */