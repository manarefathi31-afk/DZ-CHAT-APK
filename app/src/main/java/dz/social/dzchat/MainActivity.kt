package dz.social.dzchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingNotificationPayload: String? = null

    companion object {
        private const val CH_MSG = "dz_msg_channel"
        private const val CH_LIVE = "dz_live_channel"
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        if (cb == null) return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
    }

    private val mediaPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req == null) return@registerForActivityResult
        if (results.values.all { it }) req.grant(req.resources) else req.deny()
    }

    private val notifyPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    inner class DZAndroidBridge {
        @JavascriptInterface
        fun notify(title: String, body: String, kind: String, payloadJson: String) {
            runOnUiThread {
                showAndroidNotification(title, body, kind, payloadJson)
            }
        }

        @JavascriptInterface
        fun setLiveAudio(active: Boolean) {
            runOnUiThread {
                configureLiveAudioRouting(active)
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val msgChannel = NotificationChannel(
            CH_MSG,
            "إشعارات الرسائل",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "تنبيهات الرسائل الجديدة في DZ-CHAT"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 80, 180)
            setSound(soundUri, audioAttr)
        }

        val liveChannel = NotificationChannel(
            CH_LIVE,
            "إشعارات البث المباشر",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "تنبيهات بدء البث المباشر للأصدقاء"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 100, 250, 100, 350)
            setSound(soundUri, audioAttr)
        }

        nm.createNotificationChannel(msgChannel)
        nm.createNotificationChannel(liveChannel)
    }

    private fun showAndroidNotification(title: String, body: String, kind: String, payloadJson: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val channelId = if (kind == "live") CH_LIVE else CH_MSG
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("dz_payload", payloadJson)
        }
        val reqCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val pendingIntent = PendingIntent.getActivity(
            this, reqCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(reqCode, builder.build())
    }

    private fun configureLiveAudioRouting(active: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (active) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasExternal = am.availableCommunicationDevices.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    if (!hasExternal) {
                        am.availableCommunicationDevices
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?.let { am.setCommunicationDevice(it) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (!am.isBluetoothScoOn && !am.isWiredHeadsetOn) {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) { }
    }

    private fun dispatchPayloadToWeb(payloadJson: String?) {
        if (payloadJson.isNullOrBlank()) return
        val quoted = JSONObject.quote(payloadJson)
        webView.evaluateJavascript(
            "window.__dzHandleAppNotification && window.__dzHandleAppNotification($quoted);",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        pendingNotificationPayload = intent?.getStringExtra("dz_payload")

        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(DZAndroidBridge(), "DZAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return if (request.url.host == "appassets.androidplatform.net") {
                    assetLoader.shouldInterceptRequest(request.url)
                } else {
                    null
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pendingNotificationPayload?.let {
                    dispatchPayloadToWeb(it)
                    pendingNotificationPayload = null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        needed.add(Manifest.permission.CAMERA)
                    }
                    if (needed.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val missing = needed.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermissionRequest?.deny()
                        pendingWebPermissionRequest = request
                        mediaPermsLauncher.launch(missing.toTypedArray())
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchPayloadToWeb(intent.getStringExtra("dz_payload"))
    }

    override fun onDestroy() {
        configureLiveAudioRouting(false)
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        webView.destroy()
        super.onDestroy()
    }
}
