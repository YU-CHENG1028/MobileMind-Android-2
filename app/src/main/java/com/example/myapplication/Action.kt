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

        // findNodeByBounds 的容許誤差（像素）：座標落在節點邊界外多少 px 內仍算命中
        private const val BOUNDS_TOLERANCE_PX = 24
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
            val node = findNodeByContentDescription(action.contentDescription)
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

        // 1. 優先用「目前有輸入焦點」的節點（鍵盤正在對著它輸入，最準確）
        //    只有在 isEditable == true 時才採用，避免拿到非輸入框的節點
        var node: AccessibilityNodeInfo? = findFocusedEditableNode()
        val alreadyFocused = node != null

        // 2. 沒有焦點節點時，依 action 提供的線索找，但「只接受可編輯的節點」
        if (node == null) {
            node = findEditableTargetNode(action)
        }

        if (node == null) {
            return ActionResult.Failure(
                "set_text 找不到可編輯(isEditable=true)的輸入框，" +
                        "目標: ${action.resourceId ?: action.contentDescription ?: action.text ?: action.bounds}"
            )
        }

        Log.d(TAG, "set_text 目標 class=${node.className} id=${node.viewIdResourceName} " +
                "text=${node.text} editable=${node.isEditable} alreadyFocused=$alreadyFocused")

        // 3. 沒有焦點才需要 click + focus 取得輸入焦點；已經有焦點就不要再點一次
        if (!alreadyFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(200)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }

        // 4. 標準 ACTION_SET_TEXT
        val setTextArgs = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        Thread.sleep(200)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
        Log.d(TAG, "ACTION_SET_TEXT=$success")
        if (success) {
            node.recycle()
            return ActionResult.Success
        }

        Log.w(TAG, "ACTION_SET_TEXT 失敗，fallback 到剪貼簿貼上: ${action.resourceId}")

        // 5. fallback：剪貼簿貼上（此時 node 一定是可編輯節點，不會再貼到錯的地方）
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mobilemind_input", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.recycle()

        return if (pasted) ActionResult.Success
        else ActionResult.Failure("ACTION_SET_TEXT 與 PASTE 都失敗，目標: ${action.resourceId ?: action.bounds}")
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

    /** 目前擁有「輸入焦點」且可編輯的節點；找不到回傳 null。只有 set_text 會呼叫。 */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val windows = service.windows
        if (windows != null) {
            for (window in windows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable) return focused
                focused?.recycle()
            }
        }
        // 保險：有些情況 windows 拿不到，退回只查目前作用中的視窗
        val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        focused?.recycle()
        return null
    }

    /** 依 action 的線索找節點，但每一種線索都要求 isEditable == true */
    private fun findEditableTargetNode(action: Action): AccessibilityNodeInfo? {
        action.fullResourceId?.takeIf { it.isNotBlank() }?.let { id ->
            findNodeAcrossWindows { it.isEditable && it.viewIdResourceName == id }?.let { return it }
        }
        action.resourceId?.takeIf { it.isNotBlank() }?.let { id ->
            findNodeAcrossWindows {
                it.isEditable && (it.viewIdResourceName?.endsWith("/$id") == true)
            }?.let { return it }
        }
        action.contentDescription?.takeIf { it.isNotBlank() }?.let { d ->
            findNodeAcrossWindows { it.isEditable && it.contentDescription?.toString() == d }
                ?.let { return it }
        }
        // 空輸入框的 text 常常回傳 hint，所以 text 也拿來比對
        listOfNotNull(action.text, action.hintText).filter { it.isNotBlank() }.forEach { t ->
            findNodeAcrossWindows { it.isEditable && it.text?.toString() == t }?.let { return it }
        }
        action.bounds?.let { b ->
            findNodeByBounds(b, requireEditable = true)?.let { return it }
        }
        // 最後手段：整個畫面只有一個可編輯節點時，就是它
        val editables = mutableListOf<AccessibilityNodeInfo>()
        service.windows?.forEach { w ->
            w.root?.let { collectEditable(it, editables) }
        }
        return if (editables.size == 1) editables[0] else {
            editables.forEach { it.recycle() }
            null
        }
    }

    private fun collectEditable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable && node.isVisibleToUser) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditable(child, out)
            child.recycle()
        }
    }

    private fun findTargetNode(action: Action): AccessibilityNodeInfo? {
        action.fullResourceId?.let {
            findNodeByFullResourceId(it)?.let { return it }
        }

        action.resourceId?.let {
            findNodeByResourceId(it)?.let { return it }
        }

        action.contentDescription?.let {
            findNodeByContentDescription(it)?.let { return it }
        }
        /**
        action.hintText?.let {
            findNodeByHint(it)?.let { return it }
        }
        **/
        action.text?.let {
            findNodeByText(it)?.let { return it }
        }

        action.bounds?.let {
            findNodeByBounds(it)?.let { return it }
        }

        return null
        /*
        //第一優先：resource_id
        if (!action.resourceId.isNullOrBlank()) {
            val node = findNodeByResourceId(action.resourceId)
            if (node != null) return node
            Log.w(TAG, "resource_id 找不到節點: ${action.resourceId}")
        }
        //第二優先：contentDescription
        if (!action.contentDescription.isNullOrBlank()) {
            val node = findNodeByDescription(action.contentDescription)
            if (node != null) return node
            Log.w(TAG, "content_description 找不到節點: ${action.contentDescription}")
        }
        //第三優先：hintText
        if (!action.contentDescription.isNullOrBlank()) {
            val node = findnode
        }
        // bounds_x 的座標不能直接轉成節點，回傳 null 讓呼叫方 fallback 手勢
        return null

        */
    }

    //用完整ResourceId尋找
    private fun findNodeByFullResourceId(fullId: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow

        if (root != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
            if (!nodes.isNullOrEmpty()) {
                return nodes.first()
            }
        }
        return findNodeAcrossWindows {
            it.viewIdResourceName == fullId
        }
    }
    /**
     * 從目前可見的視圖樹搜尋 resource_id
     * resource_id 可能包含完整套件名（com.example:id/btn_search）或只有 id 部分
     */
    private fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
        return findNodeAcrossWindows {
            val id = it.viewIdResourceName ?: return@findNodeAcrossWindows false

            id.endsWith("/$resourceId")
        }
    }


    /*搜尋 Description*/
    private fun findNodeByContentDescription(description: String): AccessibilityNodeInfo? {
        return findNodeAcrossWindows {
            it.contentDescription?.toString() == description
        }
    }

    /*搜尋 hint*/
    /**
    Android (API26)以上才能用
    private fun findNodeByHint(hint: String): AccessibilityNodeInfo? {
        return findNodeAcrossWindows {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODE.O) {
                it.hintText?.toString() == hint
            } else {
                false
            }
        }

    }
     **/

    /*搜尋 text*/
    private fun findNodeByText(hint: String): AccessibilityNodeInfo? {
        return findNodeAcrossWindows {
            it.text?.toString() == hint
        }
    }

    /*搜尋 bounds：容許誤差版
     * 原本要求節點中心點與 LLM 給的座標「完全相等」，LLM 從截圖估出來的座標
     * 幾乎不可能剛好吻合，所以改成：
     *   1. 節點的 bounds 往外擴 BOUNDS_TOLERANCE_PX 之後，只要包含這個座標就算候選
     *   2. 候選中挑「面積最小」的（最貼近目標、最具體的那個元件，而不是外層大容器）
     *   3. 面積一樣時挑中心點離座標最近的
     * 沒被選中的候選節點會 recycle 掉。
     */
    private fun findNodeByBounds(bounds: BoundsXY, requireEditable: Boolean = false): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        val tol = BOUNDS_TOLERANCE_PX

        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var bestDist = Long.MAX_VALUE

        fun visit(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val hit = rect.width() > 0 && rect.height() > 0 &&
                    (!requireEditable || node.isEditable) &&
                    bounds.x >= rect.left - tol && bounds.x <= rect.right + tol &&
                    bounds.y >= rect.top - tol && bounds.y <= rect.bottom + tol

            if (hit) {
                val area = rect.width().toLong() * rect.height().toLong()
                val dx = (rect.centerX() - bounds.x).toLong()
                val dy = (rect.centerY() - bounds.y).toLong()
                val dist = dx * dx + dy * dy

                if (area < bestArea || (area == bestArea && dist < bestDist)) {
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(node)
                    bestArea = area
                    bestDist = dist
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child)
                child.recycle()
            }
        }

        for (window in windows) {
            val root = window.root ?: continue
            visit(root)
        }
        return best
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
