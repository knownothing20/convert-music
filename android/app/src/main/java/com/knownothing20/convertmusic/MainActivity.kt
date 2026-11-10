package com.knownothing20.convertmusic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.view.View
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.OutputStream

/**
 * 单 Activity + WebView 壳。
 *
 * 网页从 https://appassets.androidplatform.net/assets/index.html 加载（而非 file://），
 * 这样 /scan/ 下由原生复制出来的临时文件可以被网页同源 fetch 读取，
 * 避开 file:// 的跨源限制和 content:// 无法 fetch 的问题。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var dirs: DirManager
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var safeTop = 0
    private var safeBottom = 0
    private val ui = Handler(Looper.getMainLooper())

    private companion object {
        const val FILE_REQ = 1001
        const val DIR_REQ = 1002
        const val SAVE_DIR_REQ = 1003
        const val HOST = "https://appassets.androidplatform.net"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dirs = DirManager(this)

        // 沉浸式：页面延伸到状态栏/导航栏下方，状态栏透明，背景（含渐变光斑）直接透上去
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 部分 ROM 会给状态栏/导航栏加半透明遮罩，关掉才能完全透出页面背景
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

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

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // InternalStoragePathHandler 按扩展名推 MIME，.ncm 这类不在类型表里会返回 null。
            // 临时文件统一用 .mp3 后缀（系统必定认识），内容仍是原始字节。
            .addPathHandler("/scan/", WebViewAssetLoader.InternalStoragePathHandler(this, dirs.scanDir))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                applySafeArea()
            }
        }

        // 监听系统栏尺寸，供页面做安全区留白
        webView.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                safeTop = bars.top
                safeBottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                safeTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                safeBottom = insets.systemWindowInsetBottom
            }
            insets
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

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

        webView.loadUrl("$HOST/assets/index.html")
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

        if (requestCode == DIR_REQ) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { /* 某些 provider 不支持持久化 */ }

                val name = try {
                    DocumentFile.fromTreeUri(this, uri)?.name
                } catch (e: Exception) {
                    null
                } ?: "目录"

                dirs.add(uri, name)
                webView.evaluateJavascript(
                    "window.__onDirsChanged && window.__onDirsChanged()", null
                )
            }
            return
        }

        if (requestCode == SAVE_DIR_REQ) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { /* 忽略 */ }

                val name = try {
                    DocumentFile.fromTreeUri(this, uri)?.name
                } catch (e: Exception) {
                    null
                } ?: "目录"

                dirs.saveDirSet(uri, name)
                webView.evaluateJavascript(
                    "window.__onSaveDirChanged && window.__onSaveDirChanged()", null
                )
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

    /** 把状态栏/导航栏高度写给页面，CSS 用它做留白 */
    private fun applySafeArea() {
        if (safeTop <= 0 && safeBottom <= 0) return
        webView.evaluateJavascript(
            "document.documentElement.style.setProperty('--app-safe-top','" + safeTop + "px');" +
                "document.documentElement.style.setProperty('--app-safe-bottom','" + safeBottom + "px');",
            null
        )
    }

    inner class Bridge {

        // ---------------- 文件写入（转换结果） ----------------
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
                val resolver = contentResolver
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

        /**
         * 在指定目录下新建文件 —— 用于「源文件在哪就存哪」。
         * dirUri 是扫描时记录的父目录 document URI，权限随 tree 授权继承。
         */
        @JavascriptInterface
        fun beginFileInDir(dirUri: String, fileName: String): Boolean {
            discardPending()
            val safe = sanitize(fileName)
            return try {
                val uri = DocumentsContract.createDocument(
                    contentResolver,
                    Uri.parse(dirUri),
                    mimeOf(safe),
                    safe
                ) ?: return false
                pendingUri = uri

                val os = contentResolver.openOutputStream(uri)
                if (os == null) {
                    contentResolver.delete(uri, null, null)
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

        /* ---- 分块读取源文件（取代复制到 appassets 的旧方案）---- */
        private var readStream: java.io.InputStream? = null
        private val readBuf = ByteArray(2 * 1024 * 1024)

        @JavascriptInterface
        fun openFile(uriStr: String): Boolean {
            closeReadFile()
            return try {
                readStream = contentResolver.openInputStream(Uri.parse(uriStr))
                readStream != null
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun readChunk(): String? {
            val s = readStream ?: return null
            return try {
                val n = s.read(readBuf)
                if (n <= 0) {
                    closeReadFile()
                    null
                } else {
                    Base64.encodeToString(readBuf, 0, n, Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                closeReadFile()
                null
            }
        }

        @JavascriptInterface
        fun closeReadFile() {
            try { readStream?.close() } catch (e: Exception) {}
            readStream = null
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
                    contentResolver.update(uri, done, null, null)
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
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
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

        // ---------------- 常用目录 ----------------

        @JavascriptInterface
        fun dirsList(): String = dirs.list()

        @JavascriptInterface
        fun dirsRemove(id: String): Boolean = dirs.remove(id)

        @JavascriptInterface
        fun dirsAdd() {
            ui.post {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    startActivityForResult(i, DIR_REQ)
                } catch (e: Exception) {
                    webView.evaluateJavascript(
                        "window.__onDirError && window.__onDirError('无法打开目录选择器')", null
                    )
                }
            }
        }

        @JavascriptInterface
        fun saveDirGet(): String = dirs.saveDirGet()

        @JavascriptInterface
        fun saveDirClear() { dirs.saveDirClear() }

        @JavascriptInterface
        fun saveDirPick() {
            ui.post {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    startActivityForResult(i, SAVE_DIR_REQ)
                } catch (e: Exception) {
                    webView.evaluateJavascript(
                        "window.__onSaveDirError && window.__onSaveDirError('无法打开目录选择器')", null
                    )
                }
            }
        }

        /** 扫描可能耗时，放到后台线程，完成后把 JSON 丢回网页 */
        @JavascriptInterface
        fun dirsScan(id: String, cbId: String) {
            Thread {
                val json = try {
                    dirs.scan(id)
                } catch (e: Exception) {
                    "[]"
                }
                ui.post {
                    val quoted = JSONObject.quote(json)
                    webView.evaluateJavascript(
                        "window.__onScan && window.__onScan('$cbId', $quoted)", null
                    )
                }
            }.start()
        }
    }
}
