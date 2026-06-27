package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.myapplication.ActionExecutor
import com.example.myapplication.ActionResult
import com.example.myapplication.Action

/**
 * 負責「執行」後端操作指令的無障礙服務。
 * 跟 UiTreeService（負責「讀取」UI Tree）是兩個獨立的 AccessibilityService，
 * 各自在 manifest 註冊、各自有自己的 config xml，職責切開。
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
            private set
    }

    // 用獨立的 CoroutineScope，因為 AccessibilityService 沒有內建 lifecycleScope
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var executor: ActionExecutor

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        executor = ActionExecutor(this)
        Log.d("Accessibility", "MobileMind 無障礙服務已連接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 目前不主動處理事件，保留給未來判斷動態 UI 變化（例如彈出廣告視窗）用
    }

    override fun onInterrupt() {
        Log.e("Accessibility", "無障礙服務被系統中斷！")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceJob.cancel()
    }

    /**
     * 提供外部（MainActivity 收到 operate_command 時）呼叫，執行後端送來的操作指令。
     * onResult 會在背景執行緒回呼，不論成功或失敗都會回呼一次；
     * 呼叫端不論成功失敗都應該接著觸發截圖、回傳給後端的 LLM 判斷這一步是否成功
     * （對應「手機截圖後回傳 / 判斷成功與否」節點，成功與否由後端 LLM 比對截圖判斷，
     * 前端只負責「執行」與「回傳畫面」）。
     */
    fun runAction(action: Action, onResult: (ActionResult) -> Unit) {
        serviceScope.launch {
            val result = try {
                executor.execute(action)
            } catch (e: Exception) {
                Log.e("Accessibility", "執行 Action 發生例外: ${e.message}")
                ActionResult.Failure("執行例外: ${e.message}")
            }
            onResult(result)
        }
    }
}