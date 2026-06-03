package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.rdapps.gamepad.button.AxisEnum
import com.rdapps.gamepad.button.ButtonEnum
import com.rdapps.gamepad.button.ButtonState
import com.rdapps.gamepad.protocol.ControllerType
import com.rdapps.gamepad.protocol.JoyController
import com.rdapps.gamepad.service.BluetoothControllerService
import com.rdapps.gamepad.util.MacUtils
import com.rdapps.gamepad.util.PreferenceUtils
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MhxxSnipe"
        private const val FILE_CHOOSER_RC = 1001
        private val RUNTIME_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else emptyArray()
    }

    private lateinit var webView: WebView

    // ── Bluetooth HID サービス ──────────────────────────
    private var bluetoothControllerService: BluetoothControllerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? BluetoothControllerService.BluetoothControllerServiceBinder
            bluetoothControllerService = b?.service
            isBound = true
            Log.d(TAG, "BluetoothControllerService connected")
            runOnUiThread {
                webView.evaluateJavascript("if(typeof onSwitchServiceReady==='function')onSwitchServiceReady();", null)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothControllerService = null
            isBound = false
        }
    }

    // ── ファイル選択コールバック ────────────────────────
    private var fileChooserCallback: ValueCallback<Array<android.net.Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled       = true
                domStorageEnabled       = true
                allowFileAccess         = true
                allowContentAccess      = true
                mixedContentMode        = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName = "UTF-8"
                useWideViewPort         = true
                loadWithOverviewMode    = true
                setSupportZoom(true)
                builtInZoomControls     = true
                displayZoomControls     = false
            }

            // JavaScript ブリッジ登録
            addJavascriptInterface(MlKitBridge(), "Android")
            addJavascriptInterface(SwitchBridge(), "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<android.net.Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback = callback
                    val intent = params.createIntent()
                    runCatching {
                        startActivityForResult(intent, FILE_CHOOSER_RC)
                    }.onFailure { callback.onReceiveValue(null) }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page loaded: $url")
                }
            }

            loadUrl("file:///android_asset/snipe_modified.html")
        }

        setContentView(webView)

        // BluetoothControllerServiceをバインド（Pro Controllerとして起動）
        startAndBindBtService(ControllerType.PRO_CONTROLLER)
    }

    private fun startAndBindBtService(type: ControllerType) {
        val intent = Intent(this, BluetoothControllerService::class.java).apply {
            putExtra(BluetoothControllerService.DEVICE_TYPE, type)
        }
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = RUNTIME_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }
    }

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_RC) {
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileChooserCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // =========================================================
    // ML Kit ブリッジ（OCR）
    // =========================================================
    inner class MlKitBridge {

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes  = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")
                val image  = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build())
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        sendMlKitResult(JSONObject().apply { put("text", result.text) }.toString())
                    }
                    .addOnFailureListener { e ->
                        returnError(e.localizedMessage ?: "ML Kit 認識失敗")
                    }
            } catch (e: Exception) {
                returnError(e.localizedMessage ?: "不明なエラー")
            }
        }

        private fun returnError(msg: String) {
            sendMlKitResult(JSONObject().apply { put("error", msg) }.toString())
        }

        private fun sendMlKitResult(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);
                    var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    receiveMlKitResult(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================
    // Switch Bluetooth HID ブリッジ
    // HTML側の _parentAb() → AndroidBridge.xxx() を受け取る
    // =========================================================
    inner class SwitchBridge {

        private fun getDevice(): JoyController? = bluetoothControllerService?.device

        /** ボタン1つをduration[ms]間押す */
        @JavascriptInterface
        fun pressButton(buttonName: String, duration: Int) {
            val btn = nameToButtonEnum(buttonName) ?: return
            val svc = bluetoothControllerService ?: return
            val dev = getDevice() ?: return
            dev.setButton(btn, ButtonState.BUTTON_DOWN)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                dev.setButton(btn, ButtonState.BUTTON_UP)
            }, duration.toLong())
            Log.d(TAG, "pressButton: $buttonName ${duration}ms")
        }

        /** 複数ボタンを同時にduration[ms]間押す（JSON配列文字列） */
        @JavascriptInterface
        fun pressButtons(buttonsJson: String, duration: Int) {
            val dev = getDevice() ?: return
            try {
                val arr = JSONArray(buttonsJson)
                val enums = (0 until arr.length())
                    .mapNotNull { nameToButtonEnum(arr.getString(it)) }
                enums.forEach { dev.setButton(it, ButtonState.BUTTON_DOWN) }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    enums.forEach { dev.setButton(it, ButtonState.BUTTON_UP) }
                }, duration.toLong())
                Log.d(TAG, "pressButtons: $buttonsJson ${duration}ms")
            } catch (e: Exception) {
                Log.e(TAG, "pressButtons parse error", e)
            }
        }

        /**
         * スティックを指定方向に傾ける（-1.0〜1.0）
         * duration後に中立に戻す。duration=0なら戻さない
         */
        @JavascriptInterface
        fun tiltStick(side: String, x: Double, y: Double, duration: Int) {
            val dev = getDevice() ?: return
            val (xAxis, yAxis) = when (side.uppercase()) {
                "L", "LEFT"  -> Pair(AxisEnum.LEFT_STICK_X,  AxisEnum.LEFT_STICK_Y)
                "R", "RIGHT" -> Pair(AxisEnum.RIGHT_STICK_X, AxisEnum.RIGHT_STICK_Y)
                else -> return
            }
            val xVal = (x * ButtonState.STICK_POSITIVE).toInt().coerceIn(-100, 100)
            val yVal = (y * ButtonState.STICK_POSITIVE).toInt().coerceIn(-100, 100)
            dev.setAxis(xAxis, xVal)
            dev.setAxis(yAxis, yVal)
            if (duration > 0) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dev.setAxis(xAxis, 0)
                    dev.setAxis(yAxis, 0)
                }, duration.toLong())
            }
            Log.d(TAG, "tiltStick: $side ($x,$y) ${duration}ms")
        }

        /** スティックを即座にセット（duration指定なし）*/
        @JavascriptInterface
        fun setStick(side: String, x: Double, y: Double) {
            tiltStick(side, x, y, 0)
        }

        /**
         * SwitchのMACアドレスを保存してサービスを（再）接続
         * JS側: _parentAb('connectSwitch', macAddress)
         */
        @JavascriptInterface
        fun connectSwitch(macAddress: String) {
            Log.d(TAG, "connectSwitch: $macAddress")
            try {
                MacUtils.parseMacAddress(macAddress)  // 形式チェック
                PreferenceUtils.setBluetoothAddress(applicationContext, macAddress)
                // サービスを再起動して新しいMACで接続
                val intent = Intent(this@MainActivity, BluetoothControllerService::class.java).apply {
                    putExtra(BluetoothControllerService.DEVICE_TYPE, ControllerType.PRO_CONTROLLER)
                }
                startForegroundService(intent)
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Switch MAC: $macAddress を設定しました", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectSwitch error", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "MACアドレスの形式が正しくありません: $macAddress", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 接続を解除 */
        @JavascriptInterface
        fun disconnectSwitch() {
            Log.d(TAG, "disconnectSwitch")
            stopService(Intent(this@MainActivity, BluetoothControllerService::class.java))
        }

        /** 接続状態を返す */
        @JavascriptInterface
        fun isConnected(): Boolean = bluetoothControllerService?.isConnected == true

        /** 現在設定されているSwitchのMACアドレスを返す */
        @JavascriptInterface
        fun getSwitchMac(): String =
            PreferenceUtils.getBluetoothAddress(applicationContext) ?: ""

        /** 接続状態をJSに通知 */
        @JavascriptInterface
        fun getConnectionStatus(): String {
            val connected = bluetoothControllerService?.isConnected == true
            return JSONObject().apply {
                put("connected", connected)
                put("mac", PreferenceUtils.getBluetoothAddress(applicationContext) ?: "")
            }.toString()
        }

        // ── ボタン名変換 ──────────────────────────────────
        private fun nameToButtonEnum(name: String): ButtonEnum? = when (name.uppercase()) {
            "A"                     -> ButtonEnum.A
            "B"                     -> ButtonEnum.B
            "X"                     -> ButtonEnum.X
            "Y"                     -> ButtonEnum.Y
            "L"                     -> ButtonEnum.L
            "R"                     -> ButtonEnum.R
            "ZL"                    -> ButtonEnum.ZL
            "ZR"                    -> ButtonEnum.ZR
            "PLUS", "+"             -> ButtonEnum.PLUS
            "MINUS", "-"            -> ButtonEnum.MINUS
            "HOME"                  -> ButtonEnum.HOME
            "CAPTURE"               -> ButtonEnum.CAPTURE
            "UP", "DPAD_UP"         -> ButtonEnum.UP
            "DOWN", "DPAD_DOWN"     -> ButtonEnum.DOWN
            "LEFT", "DPAD_LEFT"     -> ButtonEnum.LEFT
            "RIGHT", "DPAD_RIGHT"   -> ButtonEnum.RIGHT
            "LS", "LEFT_STICK"      -> ButtonEnum.LEFT_STICK_BUTTON
            "RS", "RIGHT_STICK"     -> ButtonEnum.RIGHT_STICK_BUTTON
            else -> {
                Log.w(TAG, "Unknown button: $name")
                null
            }
        }
    }
}
