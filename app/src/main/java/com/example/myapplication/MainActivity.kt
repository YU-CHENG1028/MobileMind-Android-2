package com.example.myapplication

//System Services & Intent
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
//MediaProjection
import android.media.projection.MediaProjectionManager
//Threading & Logs
import android.os.Handler
import android.os.Looper
import android.util.Log
//Android Jetpack & OS
import android.os.Build
import android.os.Bundle
import android.util.Log.e
import androidx.annotation.RequiresApi
//Activity Results
import androidx.activity.result.contract.ActivityResultContracts
//UI Elements & ViewBinding
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import com.example.myapplication.databinding.ActivityMainBinding
//Data Parsing
import com.google.gson.Gson
import org.json.JSONObject
//OkHttp / WebSocket
import okhttp3.*

//import okhttp3.Response
//import okhttp3.WebSocket
//import okhttp3.WebSocketListener

class MainActivity : AppCompatActivity() {
    // ViewBinding
    private lateinit var binding: ActivityMainBinding

    //WebSocket(channel)
    private var webSocket: WebSocket? = null

    //OkHttpClient(factory)
    private val client = OkHttpClient()

    // MediaProjection
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // screenCaptureLauncher(handleScreenCaptureResult)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::handleScreenCaptureResult
    )

    // UiTreeJson(String)
    private var lastReceivedUiJson: String = "{}"
    //ScreenShot->Base64(String)
    private var latestScreenshotBase64: String = ""

    // 2. 螢幕截圖廣播接收器
    private val screenshotReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "COM_MOBILEMIND_SCREENSHOT_READY") {
                val base64 = intent.getStringExtra("REAL_SCREENSHOT_BASE64")
                if (!base64.isNullOrEmpty()) {
                    latestScreenshotBase64 = base64
                    sendUiScreenData() // 💡 呼叫底下的發送函式
                }
            }
        }
    }

    // 3. UI 更新廣播接收器
    private val uiUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "COM_MOBILEMIND_UI_UPDATED") {
                val json = intent.getStringExtra("UI_JSON")
                if (!json.isNullOrEmpty()) {
                    lastReceivedUiJson = json
                }
            }
        }
    }


    // 5. 🚀 核心傳送函式（確保獨立放在 class 內，不要嵌套在其他函式裡）
    private fun sendUiScreenData() {
        webSocket?.let { ws ->
            try {
                val currentTime = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                val payload = UiScreenDataPayload(
                    uiTree = lastReceivedUiJson,
                    screenShot = latestScreenshotBase64,
                    sentTime = currentTime
                )

                val jsonResponse = Gson().toJson(payload)
                ws.send(jsonResponse)
                Log.d("WebSocket", "成功推送到 Python！")
            } catch (e: Exception) {
                Log.e("WebSocket", "sendUiScreenData 失敗: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化 ViewBinding 並設定畫面
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化網路通訊元件 (Retrofit)
        //setupRetrofit()
        // 程式啟動時就建立 WebSocket 連線
        connectToWebSocket()
        //WebSocketManager()
        //初始化發送按鈕點擊
        setupClickListeners()

        // 初始化 Manager
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要開啟無障礙權限")
                .setMessage("為了讓 MobileMind 提供更完整的自動化服務，請在接下來的頁面找到本應用並開啟服務。")
                .setPositiveButton("前往設定") { _, _ ->
                    // 跳轉至系統無障礙設定頁面
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("稍後再說", null)
                .show()
        }

        // 2. 觸發系統授權視窗 (建議放在按鈕點擊事件，否則使用者會覺得莫名其妙)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)


        // 設定鍵盤「發送」鍵監聽 (讓使用者打完字按 Enter 也能發送)
        binding.etCommand.setOnEditorActionListener { v, _, _ ->
            val userInput = v.text.toString()
            if (userInput.isNotBlank()) {
                processCommand(userInput)
                v.text = ""
                true
            } else false
        }
    }// onCreate 結束


    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${MyAccessibilityService::class.java.canonicalName}"
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return settingValue?.contains(serviceName) ?: false
        }
        return false
    }

    private fun connectToWebSocket() {
        val request = Request.Builder()
            //Ngrok 隨機網址，且路由結尾為 /ws
            .url("wss://unannealed-controllingly-sarai.ngrok-free.dev/ws")
            //注入 Header 繞過 Ngrok 免費版的 200 OK 網頁攔截，確保 101 握手成功
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        //  "Interface"：透過 Listener 處理所有事件
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1. 立即將連線實例指派給全域變數，確保其他方法隨時可用
                this@MainActivity.webSocket = webSocket
                // 2. 優化：合併回主執行緒處理所有 UI 與權限相關任務
                runOnUiThread {
                    try {
                        showResult("WebSocket 已連線成功")
                        //  初始化：發送 Initial_messages，喚醒後端的 LangGraph 大腦
                        val initMsg = org.json.JSONObject().apply {
                            put("Initial_messages", "Hello MobileMind")
                        }
                        webSocket.send(initMsg.toString())
                        Log.d("WebSocket", "已成功發送訊號，啟動後端 Agent")
                        // 連線建立的瞬間，立刻在主執行緒發動螢幕截圖授權視窗
                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                        screenCaptureLauncher.launch(captureIntent)
                        Log.d("MobileMind", "連線成功，已主動要求螢幕截圖權限")
                    } catch (e: Exception) {
                        Log.e("WebSocket", "發送初始訊息失敗: ${e.message}")
                        Log.e("MobileMind", "啟動截圖授權失敗: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val jsonForType = JSONObject(text)
                    val type = jsonForType.optString("type", "unknown")

                    when (type) {
                        "initial_message" -> {
                            val data = Gson().fromJson(text, InitialMessage::class.java)
                            runOnUiThread { showResult("【系統連線】${data.message}") }
                        }

                        "ask_user" -> {
                            // 💡 對齊 AskUserMessage
                            val data = Gson().fromJson(text, AskUserMessage::class.java)
                            handleAskUser(data)
                        }

                        "task_start" -> {
                            // 💡 修正對齊 TaskStartMessage
                            val data = Gson().fromJson(text, TaskStartMessage::class.java)
                            handleTaskStart(data)
                        }

                        "read_ui" -> {
                            // 💡 修正對齊 ReadUiMessage
                            val data = Gson().fromJson(text, ReadUiMessage::class.java)
                            handleReadUI(data)
                        }

                        "action_check" -> {
                            // 💡 修正對齊 ActionCheckMessage
                            val data = Gson().fromJson(text, ActionCheckMessage::class.java)
                            handleActionCheck(data)
                        }

                        "operate_command" -> {
                            // 💡 修正對齊 OperateCommandMessage
                            val data = Gson().fromJson(text, OperateCommandMessage::class.java)
                            handleOperateCommand(data)
                        }

                        "task_end" -> {
                            // 💡 修正對齊 TaskEndMessage
                            val data = Gson().fromJson(text, TaskEndMessage::class.java)
                            handleTaskEnd(data)
                        }

                        else -> Log.w("WebSocket", "收到未定義的型態: $type")
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "解析失敗: ${e.message}")
                }
            }

            //將後端詢問使用者的必要問題顯示在APP上，並將使用者所說的細節(detail_response)傳給後端。
            // 1. 負責接收並呈現畫面的 Handler
            private fun handleAskUser(data: AskUserMessage) {
                try {
                    val askMessages = data.askMessages

                    runOnUiThread {
                        // 讓畫面上顯示 AI 的問題
                        showResult("AI 提問: $askMessages")

                        // 💡 關鍵：在這個時候，把傳送按鈕的點擊事件準備好！
                        binding.btnVoice.setOnClickListener {
                            val userInput = binding.etCommand.text.toString().trim()
                            if (userInput.isNotEmpty()) {
                                // 呼叫發送函式，把內容傳回給後端
                                sendDetailResponse(userInput)

                                // 清空輸入框
                                binding.etCommand.text.clear()
                            } else {
                                // 如果沒打字，彈出小提示（這裡要記得傳入 Activity 的 context，用 this@MainActivity 最安全）
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "請輸入回覆內容",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebSocket", "handleAskUser 解析失敗: ${e.message}")
                }
            }

            // 2. 負責把打包好的 JSON 丟回後端的函式（宣告為 private 獨立函式即可）
            private fun sendDetailResponse(userResponse: String) {
                if (webSocket != null) {
                    try {
                        val currentTime = java.text.SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date())

                        // 實例化 PythonModel.kt 裡的發送模型
                        val payload = DetailResponsePayload(
                            detailResponse = userResponse,
                            sentTime = currentTime
                        )

                        // 直接用 Gson 序列化為純文字，發送出去！
                        webSocket?.send(Gson().toJson(payload))
                        Log.d("WebSocket", "已發送細節回應: $userResponse")

                        runOnUiThread { showResult("我: $userResponse") }
                    } catch (e: Exception) {
                        Log.e("WebSocket", "發送失敗: ${e.message}")
                    }
                }
            }


            //通知APP任務開始(背景、通知、常駐)(顯示: 任務執行中...)任務初始化
            private fun handleTaskStart(data: TaskStartMessage) {
                runOnUiThread { showResult("系統通知: 任務已開始。") }
            }

            // 4. 🧠 收到 read_ui 時的處置（修正 image_3d6d5f.jpg 第 435 行的錯誤 apply 語法）
            private fun handleReadUI(data: ReadUiMessage) {
                runOnUiThread {
                    showResult("系統通知:AI正在遠端讀取螢幕結構與畫面...")
                }

                // 💡 修正：用最安全、乾淨的方式指派 ACTION，完全避開類型推導錯誤（Cannot infer type）
                val captureIntent = Intent(this, MediaProjectionService::class.java)
                captureIntent.action = "ACTION_CAPTURE"
                startService(captureIntent)

                val requestUiIntent = Intent("COM_MOBILEMIND_REQUEST_REFRESH_UI")
                sendBroadcast(requestUiIntent)

                Log.d("WebSocket", "收到 read_ui 指令，命令底層服務準備數據！")

            }

            private fun handleActionCheck(data: ActionCheckMessage) {
                val detail = data.actionDetail
                val reason = data.sensitiveReason
                runOnUiThread { showResult("敏感操作確認: $detail (原因: $reason)") }
            }

            private fun handleOperateCommand(data: OperateCommandMessage) {
                val actionStr = data.currentAction
                runOnUiThread { showResult("AI 執行指令: $actionStr") }
            }

            private fun handleTaskEnd(data: TaskEndMessage) {
                val result = data.taskResult
                val process = data.taskProcess
                runOnUiThread { showResult("🏁 任務結束！結果: $result (流程步數: $process)") }
            }


            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                // 連線失敗或中斷[cite: 1]
                runOnUiThread { showResult("連線失敗: ${t.message}") }
                // 斷線時將全域連線重設為 null，以利下次重新連線
                this@MainActivity.webSocket = null
            }
        }

        client.newWebSocket(request, listener)
    }

    private fun sendAction(messages: String) {
        // 檢查 WebSocket 是否初始化且連線
        if (webSocket != null) {
            try {
                val currentTime = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                // 💡 直接使用簡潔、無痛的 JSONObject 打包發送，避開 MyRequestData 找不到的問題！
                val replyJson = JSONObject()
                replyJson.put("type", "first_messages") // 根據你的後端邏輯調整型態名稱
                replyJson.put("first_messages", messages)
                replyJson.put("sent_time", currentTime)

                val jsonString = replyJson.toString()
                webSocket?.send(jsonString)
                Log.d("WebSocket", "已發送初始指令訊息: $jsonString")

            } catch (e: Exception) {
                Log.e("WebSocket", "發送失敗: ${e.message}")
            }
        } else {
            Toast.makeText(this, "WebSocket 未連線，無法發送", Toast.LENGTH_SHORT).show()
        }
    }


    // 發送按鈕點擊
    private fun setupClickListeners() {
        // 如果在 XML 寫 btnVoice (在你的佈局中它是 ImageButton 傳送圖標)
        binding.btnVoice.setOnClickListener {
            val userInput = binding.etCommand.text.toString().trim()

            if (userInput.isNotEmpty()) {
                // 💡 呼叫剛剛修好的發送方法，把主動指令推給 Python 大腦
                sendAction(userInput)

                // 顯示在畫面上並清空輸入框
                showResult("我 (主動指令): $userInput")
                binding.etCommand.text.clear()
            } else {
                Toast.makeText(this, "請輸入內容", Toast.LENGTH_SHORT).show()
            }
        }
    }

    //      處理最終指令，決定呼叫哪個 AI 大腦
    private fun processCommand(cmd: String) {
        // 1. 顯示 Toast 讓使用者知道 App 有在動
        Toast.makeText(this, "MobileMind 正在連線 Python 後端...", Toast.LENGTH_SHORT).show()

        /* 2. 直接呼叫 Python 對接函數，不再判斷 mode
        callPython(cmd)*/
        // 2. 改為呼叫 WebSocket 的發送函數
        sendAction(cmd)

        //發送完指令後，讓鍵盤自動收起來
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etCommand.windowToken, 0)
    }

    private fun showResult(message: String) {
        runOnUiThread {
            // 1. 將後端回傳的訊息設定給 XML 中的 TextView
            binding.tvAiResponse.text = message

            // 2. (選配) 如果你希望每次有新回覆時能自動滾動到最上方
            binding.nestedScrollView.smoothScrollTo(0, 0)

            // 原有的 Toast 可以保留，方便除錯
            Toast.makeText(this, "已更新回覆", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 🌟 方案 A 獨立出來的成員方法：專門處理螢幕截圖授權結果的回呼
     * （這段完美的邏輯方法直接取代了舊的 onActivityResult！）
     */
    private fun handleScreenCaptureResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data: Intent? = result.data

            // 啟動 MediaProjectionService 前台服務
            val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
                putExtra("data", data)
            }

            // 根據 Android 版本安全啟動服務
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("MobileMind", "螢幕截圖授權成功，前台服務已啟動！")
        } else {
            Log.w("MobileMind", "使用者拒絕了螢幕截圖授權")
        }
    }
}
