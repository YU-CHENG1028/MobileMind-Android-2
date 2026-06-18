package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class MyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Accessibility", " MobileMind 無障礙服務已連接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 目前不處理事件，保留給未來執行動作用
    }

    // 💡 修正原本巢狀包錯的位置，讓中斷生命周期正常運作
    override fun onInterrupt() {
        Log.e("Accessibility", " 無障礙服務被系統中斷！")
    }

    override fun onDestroy() {
        super.onDestroy()

    }
}
