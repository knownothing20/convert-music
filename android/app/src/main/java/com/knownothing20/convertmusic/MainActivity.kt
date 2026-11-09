package com.knownothing20.convertmusic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.OutputStream

/**
 * 单 Activity + WebView 壳。
 * 网页本体放在 assets/index.html，与 GitHub Pages 部署的是同一个文件。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private companion object {
        const val FILE_REQ = 1001
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }

        // 网页里的「另存为」走这条通道，因为 WebView 不支持 blob: 下载
        webView.addJavascriptInterface(FileBridge(this), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // 没有这个回调，点击「选择文件」在 WebView 里毫无反应
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params!!.createIntent(), FILE_REQ)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQ) {
            val cb = fileCallback
            fileCallback = null
            if (cb != null) {
                val result = if (resultCode == RESULT_OK && data != null) {
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                } else {
                    null
                }
                cb.onReceiveValue(result)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 给网页用的文件写入桥。
     * 分块传输，避免把上百 MB 的文件一次性塞成一个巨大的 base64 字符串。
     */
    inner class FileBridge(private val activity: Activity) {

        private var stream: OutputStream? = null
        private var pendingUri: Uri? = null

        @JavascriptInterface
        fun beginFile(fileName: String): Boolean {
            discardPending()
            val safe = sanitize(fileName)
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(safe))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/音乐转换")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                pendingUri = uri

                val os = resolver.openOutputStream(uri)
                if (os == null) {
                    resolver.delete(uri, null, null)
                    pendingUri = null
                    return false
                }
                stream = os
                true
            } catch (e: Exception) {
                discardPending()
                false
            }
        }

        @JavascriptInterface
        fun appendChunk(base64: String): Boolean {
            val os = stream ?: return false
            return try {
                os.write(Base64.decode(base64, Base64.DEFAULT))
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun endFile(): String {
            return try {
                stream?.flush()
                stream?.close()
                stream = null

                val uri = pendingUri
                if (uri != null) {
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    activity.contentResolver.update(uri, done, null, null)
                    pendingUri = null
                }
                "OK"
            } catch (e: Exception) {
                discardPending()
                "ERR" + (e.message ?: "保存失败")
            }
        }

        private fun discardPending() {
            try { stream?.close() } catch (_: Exception) { }
            stream = null
            val uri = pendingUri
            pendingUri = null
            if (uri != null) {
                try { activity.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        }

        private fun sanitize(name: String): String {
            var s = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (s.isEmpty()) s = "audio.mp3"
            return s
        }

        private fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "wma" -> "audio/x-ms-wma"
                "ape" -> "audio/x-ape"
                else -> "audio/mpeg"
            }
        }
    }
}
