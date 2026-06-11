package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class MediaProjectionService : Service() {

    private var mImageReader: ImageReader? = null
    private var mWidth: Int = 1080
    private var mHeight: Int = 1920
    private var mProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "CHANNEL_ID")
            .setContentTitle("螢幕擷取中")
            .setContentText("AI 正在使用全螢幕自動化視覺服務...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)

        mWidth = intent?.getIntExtra("WIDTH", 1080) ?: 1080
        mHeight = intent?.getIntExtra("HEIGHT", 1920) ?: 1920
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultData != null && resultCode != 0) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mProjection = mpManager.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
        }

        // 💡 修正點 1：將呼叫名稱改為 captureScreen()，對齊底下的宣告，消滅 Unresolved reference 紅字
        if (intent?.action == "ACTION_CAPTURE") {
            captureScreen()
        }

        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun setupVirtualDisplay() {
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)

        mVirtualDisplay = mProjection?.createVirtualDisplay(
            "MobileMind-ScreenCapture",
            mWidth, mHeight, 320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mImageReader?.surface, null, null
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "CHANNEL_ID",
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun captureScreen() {
        val image = mImageReader?.acquireLatestImage() ?: return
        val planes = image.planes
        val buffer = planes[0].buffer

        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * mWidth

        val paddedWidth = mWidth + rowPadding / pixelStride
        val rawBitmap = Bitmap.createBitmap(paddedWidth, mHeight, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        image.close() // 💡 拿完資料立刻關閉 Image，釋放硬體記憶體

        // 🌟 核心裁切：精準剪掉右邊多出來的 Padding 綠條
        val realBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, mWidth, mHeight)
        rawBitmap.recycle() // 釋放帶有 padding 的臨時點陣圖

        try {
            // 3. 儲存到手機相簿（供開發者檢查）
            saveBitmapToGallery(realBitmap)

            // 4. 壓縮並轉成 Base64 純文字
            val base64Image = bitmapToBase64(realBitmap)

            // 5. 發送廣播把截圖丟回給 MainActivity
            val broadcastIntent = Intent("COM_MOBILEMIND_SCREENSHOT_READY")
            broadcastIntent.putExtra("REAL_SCREENSHOT_BASE64", base64Image)
            sendBroadcast(broadcastIntent)

            Log.d("MediaProjection", "🎉 真實截圖已轉成 Base64 並發送廣播！")
        } catch (e: Exception) {
            Log.e("MediaProjection", "處理截圖時發生錯誤: ${e.message}")
        } finally {
            // 💡 修正點 2：確保所有操作完成後，再於最尾端安全回收 realBitmap，防止點陣圖操作崩潰
            realBitmap.recycle()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxSide = 720
        val scale = maxSide.toFloat() / Math.max(bitmap.width, bitmap.height).toFloat()
        val resizedBitmap = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()

        // 如果點陣圖有縮放，記得把縮放後的臨時 Bitmap 也回收掉
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "Screenshot_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MobileMind")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    Log.d("MediaProjection", "截圖已儲存: $filename")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mVirtualDisplay?.release()
        mProjection?.stop()
    }
}