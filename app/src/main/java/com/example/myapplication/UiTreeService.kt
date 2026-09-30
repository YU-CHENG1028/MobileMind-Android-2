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
            Log.d("DEBUG_FLOW", "=== uiRefreshReceiver 收到廣播 ===")
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
        try {
            unregisterReceiver(uiRefreshReceiver)  // 防記憶體洩漏
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Receiver 未註冊: ${e.message}")
        }

    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 不主動處理事件
    }

    override fun onInterrupt() {}

    /* 供外部主動呼叫：回傳 Tree 本體 + 覆蓋率資訊，而不是單純的 UiNode */
    fun getCurrentUiTree(): UiTreeResult? {

        val root = rootInActiveWindow ?: return null

        val screenRect = Rect()
        root.getBoundsInScreen(screenRect)

        return try {
            val tree = parseAccessibilityNode(root)

            val leafBounds = mutableListOf<BoundsRect>()
            collectRealBounds(tree, leafBounds)

            val coverage = computeCoverage(
                bounds = leafBounds,
                screenWidth = screenRect.width(),
                screenHeight = screenRect.height()
            )

            UiTreeResult(
                screenWidth = screenRect.width(),
                screenHeight = screenRect.height(),
                coverageRatio = coverage.ratio,
                uncoveredRegions = coverage.uncoveredRegions,
                root = tree
            )
        } finally {
            root.recycle()
        }
    }

    /* 收集所有「有真實 bounds」的節點座標（寬高都 > 0），供覆蓋率計算用。
       聚合 Group 因為改成聯集座標，也會被算進來，這是刻意的：
       它代表底下確實有東西，不算「沒被感知到」。*/
    private fun collectRealBounds(node: UiNode?, out: MutableList<BoundsRect>) {
        if (node == null) return
        if (node.width > 0 && node.height > 0) {
            out.add(BoundsRect(node.x, node.y, node.width, node.height))
        }
        node.children?.forEach { collectRealBounds(it, out) }
    }

    private data class CoverageInfo(
        val ratio: Double,
        val uncoveredRegions: List<BoundsRect>
    )

    /* 用網格近似法算「畫面上有多少比例被 UI Tree 節點覆蓋」，
       並找出面積較大、完全沒有節點覆蓋的區域（例如 WebView 廣告、自繪 Canvas 等
       無障礙服務抓不到內容的區塊），把這些區域明確標記出來交給 LLM，
       避免 LLM 誤以為「Tree 裡沒有 = 畫面上沒有東西」。*/
    private fun computeCoverage(
        bounds: List<BoundsRect>,
        screenWidth: Int,
        screenHeight: Int
    ): CoverageInfo {

        if (screenWidth <= 0 || screenHeight <= 0) {
            return CoverageInfo(ratio = 1.0, uncoveredRegions = emptyList())
        }

        val cellSize = 24 // px，網格越小越精準，但這裡精準度需求不高，24px 已足夠
        val cols = (screenWidth + cellSize - 1) / cellSize
        val rows = (screenHeight + cellSize - 1) / cellSize
        val covered = Array(rows) { BooleanArray(cols) }

        for (b in bounds) {
            val cx1 = (b.x / cellSize).coerceIn(0, cols - 1)
            val cy1 = (b.y / cellSize).coerceIn(0, rows - 1)
            val cx2 = ((b.x + b.width) / cellSize).coerceIn(0, cols - 1)
            val cy2 = ((b.y + b.height) / cellSize).coerceIn(0, rows - 1)
            for (r in cy1..cy2) {
                for (c in cx1..cx2) {
                    covered[r][c] = true
                }
            }
        }

        var coveredCount = 0
        for (r in 0 until rows) for (c in 0 until cols) if (covered[r][c]) coveredCount++
        val totalCount = rows * cols
        val ratio = if (totalCount == 0) 1.0 else coveredCount.toDouble() / totalCount.toDouble()

        // BFS 找未覆蓋格子的連通區塊，只回報面積 >= 螢幕 3% 的區塊（避免小縫隙洗版）
        val visited = Array(rows) { BooleanArray(cols) }
        val minAreaCells = (totalCount * 0.03).toInt().coerceAtLeast(4)
        val regions = mutableListOf<BoundsRect>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (covered[r][c] || visited[r][c]) continue

                var minR = r; var maxR = r; var minC = c; var maxC = c
                var cellCount = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    cellCount++
                    minR = minOf(minR, cr); maxR = maxOf(maxR, cr)
                    minC = minOf(minC, cc); maxC = maxOf(maxC, cc)

                    val neighbors = listOf(cr - 1 to cc, cr + 1 to cc, cr to cc - 1, cr to cc + 1)
                    for ((nr, nc) in neighbors) {
                        if (nr in 0 until rows && nc in 0 until cols &&
                            !covered[nr][nc] && !visited[nr][nc]
                        ) {
                            visited[nr][nc] = true
                            queue.add(nr to nc)
                        }
                    }
                }

                if (cellCount >= minAreaCells) {
                    regions.add(
                        BoundsRect(
                            x = minC * cellSize,
                            y = minR * cellSize,
                            width = (maxC - minC + 1) * cellSize,
                            height = (maxR - minR + 1) * cellSize
                        )
                    )
                }
            }
        }

        // 只留面積最大的前 3 塊，避免 prompt 被一堆瑣碎區域塞爆
        val topRegions = regions.sortedByDescending { it.width.toLong() * it.height.toLong() }
            .take(3)

        return CoverageInfo(ratio = ratio, uncoveredRegions = topRegions)
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

                else -> {
                    // 原本這裡座標寫死 0,0,0,0，會讓 LLM 誤判「這個 Group 沒有位置資訊」，
                    // 甚至可能被拿去當 bounds fallback 點擊座標（點在螢幕左上角）。
                    // 改成用所有子節點的聯集邊界框，至少能反映這群元件實際落在畫面的哪個區域。
                    val unionBounds = unionOf(compressedChildren)

                    UiNode(
                        type = "Group",

                        clickable = false,
                        enabled = true,
                        scrollable = false,
                        editable = false,
                        selected = false,

                        x = unionBounds.x,
                        y = unionBounds.y,
                        width = unionBounds.width,
                        height = unionBounds.height,

                        children = compressedChildren
                    )
                }
            }
        }

        return createUiNode(
            node,
            compressedChildren
        )
    }

    /* 計算一群子節點的聯集邊界框；只考慮寬高 > 0 的節點，避免零座標污染聯集結果 */
    private fun unionOf(nodes: List<UiNode>): BoundsRect {
        val real = nodes.filter { it.width > 0 && it.height > 0 }
        if (real.isEmpty()) return BoundsRect(0, 0, 0, 0)

        val left = real.minOf { it.x }
        val top = real.minOf { it.y }
        val right = real.maxOf { it.x + it.width }
        val bottom = real.maxOf { it.y + it.height }

        return BoundsRect(x = left, y = top, width = right - left, height = bottom - top)
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

            fullResourceId =
                node.viewIdResourceName,

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
    val fullResourceId: String? = null,
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

/* 一個矩形區域，用於 Group 的聯集邊界框，以及未覆蓋區域的回報 */
data class BoundsRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/* getCurrentUiTree() 的回傳結構：
   除了 Tree 本體(root)之外，額外附上螢幕尺寸與覆蓋率資訊，
   讓後端／LLM 能明確知道「畫面上有多少比例是 UI Tree 完全沒感知到的」，
   而不是誤以為 Tree 裡沒出現的東西＝畫面上不存在。 */
data class UiTreeResult(
    val screenWidth: Int,
    val screenHeight: Int,
    val coverageRatio: Double,         // 0.0~1.0，Tree 節點覆蓋畫面的比例
    val uncoveredRegions: List<BoundsRect>, // 面積較大、完全沒有節點覆蓋的區域（依面積排序，最多3個）
    val root: UiNode?
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