package com.example.myapplication

//import android.content.Context.INPUT_METHOD_SERVICE
//import android.R.attr.data
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
//import android.util.Log.e
//import android.util.Log.e
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
//import androidx.core.content.ContextCompat.getSystemService
//import androidx.core.content.ContextCompat.startForegroundService
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.gson.Gson
//import retrofit2.Call
//import retrofit2.Callback
//import retrofit2.Response
//import retrofit2.Retrofit
//import retrofit2.converter.gson.GsonConverterFactory
import kotlin.jvm.java
import okhttp3.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
//import okio.ByteString
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    // 使用 ViewBinding 可以避免 findViewById，程式碼更簡潔且安全
    private lateinit var binding: ActivityMainBinding

    // 定義 後端 服務
    //private lateinit var pythonApiService: PythonApiService
    private lateinit var webSocket: WebSocket
    private val client = OkHttpClient()


    // 宣告 MediaProjection 管理器
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val REQUEST_CODE = 1000 // 自定義一個請求代碼

    // 新增：註冊螢幕擷取的回傳處理
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 使用者授權後，啟動前台服務進行截圖
            val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
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

    //Retrofit 需要 BaseURL 與 Converter (JSON 轉物件)
    /*private fun setupRetrofit() {

        // 後端
        pythonApiService = Retrofit.Builder()
            .baseUrl("wss://unannealed-controllingly-sarai.ngrok-free.dev/ws")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PythonApiService::class.java)

    }*/
    //  建立連線方法
    /*class WebSocketManager {
        private val client = OkHttpClient()
        private var webSocket: WebSocket? = null

        fun connect() {
            val request = Request.Builder()
                .url("wss://unannealed-controllingly-sarai.ngrok-free.dev/ws")
                .addHeader("ngrok-skip-browser-warning", "true")  // 重要！跳過 ngrok 警告頁
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // 連線成功後，馬上送出第一條訊息（對應你後端的 initial_data）
                    val initMsg = JSONObject()
                    initMsg.put("first_messages", "使用者的初始指令")
                    webSocket.send(initMsg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 收到後端訊息
                    val json = JSONObject(text)
                    println("收到訊息: $json")
                    // 根據 type 欄位判斷要做什麼
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    println("連線失敗: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    println("連線關閉: $reason")
                }
            })
        }

        // 送訊息給後端（對應你的 handle_user_response）
        fun sendMessage(data: JSONObject) {
            webSocket?.send(data.toString())
        }

        fun disconnect() {
            webSocket?.close(1000, "使用者關閉")
        }
    }*/
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
                // 連線成功！
                this@MainActivity.webSocket = webSocket
                runOnUiThread { showResult("WebSocket 已連線成功") }
                // 初始化: 發送 Initial_messages，喚醒後端的 LangGraph 大腦
                try {
                    val initMsg = org.json.JSONObject()
                    initMsg.put("Initial_messages", "Hello MobileMind")
                    webSocket.send(initMsg.toString())
                    Log.d("WebSocket", "已成功發送訊號，啟動後端 Agent")
                } catch (e: Exception) {
                    Log.e("WebSocket", "發送初始訊息失敗: ${e.message}")
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
                                android.widget.Toast.makeText(this@MainActivity, "請輸入回覆內容", android.widget.Toast.LENGTH_SHORT).show()
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
                        val currentTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.getDefault()).format(java.util.Date())

                        // 實例化 PythonModel.kt 裡的發送模型
                        val payload = DetailResponsePayload(
                            detailResponse = userResponse,
                            sentTime = currentTime
                        )

                        // 直接用 Gson 序列化為純文字，發送出去！
                        webSocket.send(Gson().toJson(payload))
                        Log.d("WebSocket", "已發送細節回應: $userResponse")

                        runOnUiThread { showResult("我: $userResponse") }
                    } catch (e: Exception) {
                        Log.e("WebSocket", "發送失敗: ${e.message}")
                    }
                }
            }



            //通知APP任務開始(背景、通知、常駐)(顯示: 任務執行中...)任務初始化
            private fun handleTaskStart(data: TaskStartMessage){
                runOnUiThread { showResult("系統通知: 任務已開始。")}
            }

            private fun handleReadUI(data: ReadUiMessage){
                runOnUiThread { showResult("系統通知: 後端大腦要求讀取當前螢幕 UI Tree...") }
            }

            private fun handleActionCheck(data: ActionCheckMessage){
                val detail = data.actionDetail
                val reason = data.sensitiveReason
                runOnUiThread { showResult("敏感操作確認: $detail (原因: $reason)") }
            }

            private fun handleOperateCommand(data: OperateCommandMessage){
                val actionStr = data.currentAction
                runOnUiThread { showResult("AI 執行指令: $actionStr") }
            }

            private fun handleTaskEnd(data: TaskEndMessage){
                val result = data.taskResult
                val process = data.taskProcess
                runOnUiThread { showResult("🏁 任務結束！結果: $result (流程步數: $process)") }
            }


            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 連線失敗或中斷[cite: 1]
                runOnUiThread { showResult("連線失敗: ${t.message}") }
            }
        }

        client.newWebSocket(request, listener)
    }
    private fun sendAction(messages: String) {
        // 檢查 WebSocket 是否初始化且連線
        if (webSocket != null) {
            try {
                val currentTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.getDefault()).format(java.util.Date())

                // 💡 直接使用簡潔、無痛的 JSONObject 打包發送，避開 MyRequestData 找不到的問題！
                val replyJson = JSONObject()
                replyJson.put("type", "first_messages") // 根據你的後端邏輯調整型態名稱
                replyJson.put("first_messages", messages)
                replyJson.put("sent_time", currentTime)

                val jsonString = replyJson.toString()
                webSocket.send(jsonString)
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

        //    注意：404 錯誤通常發生在 ApiService 的 @POST 路徑拼錯，請檢查那邊

        /*private fun callPython(userPrompt: String) {
            // 1. 準備發送給 Python 的資料
            val request = MyRequestData(prompt = userPrompt)
            // 2. 使用 pythonService 發送請求
            pythonApiService.sendTestData(request).enqueue(object : Callback<MyResponseData> {
                override fun onResponse(
                    call: Call<MyResponseData>,
                    response: Response<MyResponseData>
                ) {
                    if (response.isSuccessful) {
                        val botReply = response.body()?.reply ?: "Python 回傳空值"
                        showResult(botReply) // 顯示在介面上
                    } else {
                        Log.e("DEBUG_STEP", "錯誤碼: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<MyResponseData>, t: Throwable) {
                    Log.e("DEBUG_STEP", "連線失敗: ${t.message}")
                }
            })
        }*/

        /*統一顯示結果的函數
        包含 runOnUiThread 確保在非同步請求後安全地更新 UI*/
//    private fun showResult(message: String) {
//        runOnUiThread {
//            // 做法 D：將後端回傳訊息加入 RecyclerView
//            taskAdapter.addTask(TaskItem(content = "AI 回覆：$message"))
//
//            // 原有的 Toast 保留作為偵錯用
//            Toast.makeText(this@MainActivity, "收到回覆", Toast.LENGTH_SHORT).show()
//
//            // 讓列表自動捲動到最上方
//            binding.rvRecentTasks.scrollToPosition(0)
//        }
//    }
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

        // 在 onActivityResult 接收結果
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data) // 資工系好習慣：呼叫父類別方法

            if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
                // 使用者點擊「立即開始」後，啟動你剛才在 Manifest 宣告的前台服務
                val serviceIntent = Intent(this, MediaProjectionService::class.java)
                serviceIntent.putExtra("data", data)

                // 根據 Android 版本啟動服務 (Android 8.0+ 建議用 startForegroundService)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
    }