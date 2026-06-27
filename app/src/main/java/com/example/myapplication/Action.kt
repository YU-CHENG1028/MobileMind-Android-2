package com.example.myapplication

import android.R.attr.action
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// ─────────────────────────────────────────────
// 主執行器
// Action / BoundsXY / ActionResult 定義在 ActionModels.kt
// ─────────────────────────────────────────────

class ActionExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ActionExecutor"

        // scroll 的滑動距離（像素），可依裝置解析度調整
        private const val SCROLL_DISTANCE_PX = 500
        // scroll 動畫時長（毫秒）
        private const val SCROLL_DURATION_MS = 300L
    }

    /**
     * 統一入口：根據 action_type 分派到對應的執行方法
     */
    suspend fun execute(action: Action): ActionResult {
        Log.d(TAG, "執行操作: $action")

        return when (action.actionType.lowercase()) {
            "click"       -> executeClick(action)
            "set_text"    -> executeSetText(action)
            "scroll"      -> executeScroll(action)
            "global_back" -> executeGlobalBack()
            else          -> ActionResult.Failure("未知的 action_type: ${action.actionType}")
        }
    }

    // ─────────────────────────────────────────
    // Click
    // ─────────────────────────────────────────

    private suspend fun executeClick(action: Action): ActionResult {
        // 優先：用 resource_id 找到節點，呼叫節點的 performAction
        if (!action.resourceId.isNullOrBlank()) {
            val node = findNodeByResourceId(action.resourceId)
            if (node != null) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return if (result) ActionResult.Success
                else ActionResult.Failure("resource_id 找到節點，但 performAction(CLICK) 失敗: ${action.resourceId}")
            }
            Log.w(TAG, "resource_id 找不到節點，fallback 到 bounds: ${action.resourceId}")
        }

        // content_description 次之
        if (!action.contentDescription.isNullOrBlank()) {
            val node = findNodeByDescription(action.contentDescription)
            if (node != null) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return if (result) ActionResult.Success
                else ActionResult.Failure("content_description 找到節點，但 CLICK 失敗: ${action.contentDescription}")
            }
            Log.w(TAG, "content_description 找不到節點: ${action.contentDescription}")
        }
        // Fallback：用座標模擬點擊手勢
        val coords = action.bounds
            ?: return ActionResult.Failure("三種識別方式接失敗，無法執行 click")

        return performTap(coords.x.toFloat(), coords.y.toFloat())
    }

    // ─────────────────────────────────────────
    // Set Text
    // ─────────────────────────────────────────

    private fun executeSetText(action: Action): ActionResult {
        val text = action.inputText
            ?: return ActionResult.Failure("set_text 操作缺少 input_text")

        // 先找到節點（resource_id 優先）
        val node = findTargetNode(action)
            ?: return ActionResult.Failure("set_text 找不到目標節點")

        // 1. 先 click 再 focus —— 很多自訂輸入框只用 ACTION_FOCUS 抓不到真正的輸入焦點
        // 先 focus，再設定文字
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // 2. 嘗試標準的 ACTION_SET_TEXT
        val setTextArgs = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            node.recycle()
            return ActionResult.Success
        }
        Log.w(TAG, "ACTION_SET_TEXT 失敗，fallback 到剪貼簿貼上: ${action.resourceId}")

        // 3. fallback：剪貼簿貼上 —— WebView / 自訂搜尋框常常只接受 ACTION_PASTE
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mobilemind_input", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.recycle()

        return if (pasted) ActionResult.Success
        else ActionResult.Failure("ACTION_SET_TEXT 執行失敗，目標: ${action.resourceId ?: action.bounds}")
    }

    // ─────────────────────────────────────────
    // Scroll
    // ─────────────────────────────────────────

    private suspend fun executeScroll(action: Action): ActionResult {
        val direction = action.scrollDirection?.lowercase()
            ?: return ActionResult.Failure("scroll 操作缺少 scroll_direction")

        // 優先：嘗試用 AccessibilityNodeInfo 的 scroll action（效率更高）
        val node = findTargetNode(action)
        if (node != null) {
            val scrollAction = when (direction) {
                "up"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "down"  -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "left"  -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else    -> null
            }
            if (scrollAction != null) {
                val result = node.performAction(scrollAction)
                node.recycle()
                if (result) return ActionResult.Success
                // node scroll 失敗時，繼續 fallback 到手勢
                Log.w(TAG, "節點 scroll 失敗，fallback 到手勢模擬")
            }
        }

        // Fallback：用滑動手勢模擬 scroll
        // 取得螢幕尺寸，以螢幕中心為起點
        val displayMetrics = service.resources.displayMetrics
        val screenCenterX = displayMetrics.widthPixels / 2f
        val screenCenterY = displayMetrics.heightPixels / 2f

        val (startX, startY, endX, endY) = when (direction) {
            "up"    -> ScrollCoords(screenCenterX, screenCenterY + SCROLL_DISTANCE_PX, screenCenterX, screenCenterY - SCROLL_DISTANCE_PX)
            "down"  -> ScrollCoords(screenCenterX, screenCenterY - SCROLL_DISTANCE_PX, screenCenterX, screenCenterY + SCROLL_DISTANCE_PX)
            "left"  -> ScrollCoords(screenCenterX + SCROLL_DISTANCE_PX, screenCenterY, screenCenterX - SCROLL_DISTANCE_PX, screenCenterY)
            "right" -> ScrollCoords(screenCenterX - SCROLL_DISTANCE_PX, screenCenterY, screenCenterX + SCROLL_DISTANCE_PX, screenCenterY)
            else    -> return ActionResult.Failure("未知的 scroll_direction: $direction")
        }

        return performSwipe(startX, startY, endX, endY, SCROLL_DURATION_MS)
    }

    // ─────────────────────────────────────────
    // Global Back
    // ─────────────────────────────────────────

    private fun executeGlobalBack(): ActionResult {
        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return if (result) ActionResult.Success
        else ActionResult.Failure("GLOBAL_ACTION_BACK 執行失敗")
    }

    // ─────────────────────────────────────────
    // 手勢工具方法
    // ─────────────────────────────────────────

    /**
     * 點擊指定座標
     */
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return performGesture(gesture, "tap($x, $y)")
    }

    /**
     * 滑動（swipe）手勢
     */
    private suspend fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): ActionResult {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return performGesture(gesture, "swipe($startX,$startY → $endX,$endY)")
    }

    /**
     * 統一的手勢派發（suspend，等待回調後繼續）
     */
    private suspend fun performGesture(
        gesture: GestureDescription,
        label: String
    ): ActionResult = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "手勢完成: $label")
                cont.resume(ActionResult.Success)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "手勢取消: $label")
                cont.resume(ActionResult.Failure("手勢被取消: $label"))
            }
        }
        service.dispatchGesture(gesture, callback, null)
    }

    // ─────────────────────────────────────────
    // 節點查找工具方法
    // ─────────────────────────────────────────

    /**
     * 依 resource_id 優先、bounds_x 次之找到目標節點
     */

    /** 跨所有目前顯示的視窗（包含彈出的建議清單）找節點 */
    private fun findNodeAcrossWindows(matcher: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val found = searchNodeRecursive(root, matcher)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeRecursive(
        node: AccessibilityNodeInfo,
        matcher: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (matcher(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursive(child, matcher)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findTargetNode(action: Action): AccessibilityNodeInfo? {
        //第一優先：resource_id
        if (!action.resourceId.isNullOrBlank()) {
            val node = findNodeByResourceId(action.resourceId)
            if (node != null) return node
            Log.w(TAG, "resource_id 找不到節點: ${action.resourceId}")
        }

        //第二優先：resource_id
        if (!action.contentDescription.isNullOrBlank()) {
            val node = findNodeByDescription(action.contentDescription)
            if (node != null) return node
            Log.w(TAG, "content_description 找不到節點: ${action.contentDescription}")
        }
        // bounds_x 的座標不能直接轉成節點，回傳 null 讓呼叫方 fallback 手勢
        return null
    }

    /**
     * 從目前可見的視圖樹搜尋 resource_id
     * resource_id 可能包含完整套件名（com.example:id/btn_search）或只有 id 部分
     */
    private fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        if (root != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            root.recycle()
            nodes?.firstOrNull()?.let { return it }
        }
        // rootInActiveWindow 找不到時，可能是彈出的建議清單視窗，跨視窗找
        return findNodeAcrossWindows { it.viewIdResourceName == resourceId }
    }


    /*搜尋 resource_id*/
    private fun findNodeByDescription(description: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        if (root != null) {
            val nodes = root.findAccessibilityNodeInfosByText(description)
            root.recycle()
            nodes?.firstOrNull {
                it.contentDescription?.toString() == description || it.text?.toString() == description
            }?.let { return it }
        }
        return findNodeAcrossWindows {
            it.contentDescription?.toString() == description || it.text?.toString() == description
        }
    }

    // ─────────────────────────────────────────
    // 內部資料類
    // ─────────────────────────────────────────

    private data class ScrollCoords(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float
    )
}
