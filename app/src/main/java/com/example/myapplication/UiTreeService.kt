package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.google.gson.GsonBuilder

class UiTreeService : AccessibilityService() {

    companion object {
        var instance: UiTreeService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DEBUG_FLOW", "=== UiTreeService onServiceConnected ===")

        // 註冊廣播，監聽 MainActivity 的 UI 刷新請求
        val filter = IntentFilter("COM_MOBILEMIND_REQUEST_REFRESH_UI")
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            uiRefreshReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        Log.d("DEBUG_FLOW", "=== UiTreeService 廣播接收器已註冊 ===")
    }

    // 收到請求 → 立刻抓 UI Tree → 廣播回 MainActivity
    private val uiRefreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "COM_MOBILEMIND_REQUEST_REFRESH_UI") {
                val uiTree = getCurrentUiTree()
                val json = com.google.gson.Gson().toJson(uiTree) ?: "{}"

                // ✅ Log 1: 確認 UI Tree 有沒有抓到內容
                Log.d("DEBUG_FLOW", "=== UI Tree 已抓取 ===")
                Log.d("DEBUG_FLOW", "UI Tree 長度: ${json.length} 字元")
                Log.d("DEBUG_FLOW", "UI Tree 前200字: ${json.take(200)}")

                val resultIntent = Intent("COM_MOBILEMIND_UI_UPDATED")
                resultIntent.putExtra("UI_JSON", json)
                sendBroadcast(resultIntent)

                android.util.Log.d("UiTreeService", "UI Tree 已更新並廣播出去")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(uiRefreshReceiver)  // 防記憶體洩漏
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 不主動處理事件
    }

    override fun onInterrupt() {}

    /* 供外部主動呼叫 */
    fun getCurrentUiTree(): UiNode? {

        val root = rootInActiveWindow ?: return null

        return try {
            parseAccessibilityNode(root)
        } finally {
            root.recycle()
        }
    }

    /* 判斷是否值得保留給 LLM */
    private fun isUsefulNode(
        node: AccessibilityNodeInfo
    ): Boolean {

        return !node.text.isNullOrBlank()
                || !node.contentDescription.isNullOrBlank()
                || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    !node.hintText.isNullOrBlank()
                    } else false
                || !node.viewIdResourceName.isNullOrBlank()
                || node.isClickable
                || node.isScrollable
                || node.isEditable
    }

    /* UI Compression DFS */
    private fun parseAccessibilityNode(
        node: AccessibilityNodeInfo
    ): UiNode? {

        val compressedChildren = mutableListOf<UiNode>()

        for (i in 0 until node.childCount) {

            val child = node.getChild(i)

            if (child != null) {

                parseAccessibilityNode(child)
                    ?.let { compressedChildren.add(it) }

                child.recycle()
            }
        }

        val useful = isUsefulNode(node)

        if (!useful) {

            return when (compressedChildren.size) {

                0 -> null

                1 -> compressedChildren[0]

                else -> UiNode(
                    type = "Group",

                    clickable = false,
                    enabled = true,
                    scrollable = false,
                    editable = false,
                    selected = false,

                    x = 0,
                    y = 0,
                    width = 0,
                    height = 0,

                    children = compressedChildren
                )
            }
        }

        return createUiNode(
            node,
            compressedChildren
        )
    }

    private fun createUiNode(
        node: AccessibilityNodeInfo,
        children: List<UiNode>
    ): UiNode {

        val rect = Rect()
        node.getBoundsInScreen(rect)

        return UiNode(

            text =
                node.text?.toString(),

            contentDescription =
                node.contentDescription?.toString(),

            hint =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()
                } else null,

            resourceId =
                simplifyResourceId(
                    node.viewIdResourceName
                ),

            type =
                simplifyClassName(
                    node.className
                ),

            clickable =
                node.isClickable,

            enabled =
                node.isEnabled,

            scrollable =
                node.isScrollable,

            editable =
                node.isEditable,

            selected =
                node.isSelected,

            x =
                rect.left,

            y =
                rect.top,

            width =
                rect.width(),

            height =
                rect.height(),

            children =
                children.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * com.foo:id/login_button
     * ->
     * login_button
     */
    private fun simplifyResourceId(
        resourceId: String?
    ): String? {

        return resourceId
            ?.substringAfterLast("/")
    }

    /**
     * android.widget.Button
     * ->
     * Button
     */
    private fun simplifyClassName(
        className: CharSequence?
    ): String {

        return className
            ?.toString()
            ?.substringAfterLast(".")
            ?: "Unknown"
    }
}

// 定義資料結構
data class UiNode(

    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val resourceId: String? = null,
    val type: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val selected: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val children: List<UiNode>? = null
)

//MainActivity 呼叫方式
/*
val uiTree =
    UiTreeService.instance
        ?.getCurrentUiTree()
 */

//轉 JSON
/*
val json =
   Gson().toJson(uiTree)
 */