package com.knownothing20.convertmusic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 常用目录管理。
 *
 * 用 SAF(Storage Access Framework)选择目录，并通过 takePersistableUriPermission
 * 获得长期读取权限，URI 存进 SharedPreferences，重启 App 依然有效。
 *
 * 扫描时把命中的 .ncm 复制到私有目录（filesDir/scan），
 * 由 WebViewAssetLoader 映射成 https://appassets.androidplatform.net/scan/xxx
 * 供网页 fetch 读取 —— 这是 WebView 里访问外部文件的可靠方式。
 */
class DirManager(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("dirs", Context.MODE_PRIVATE)
    val scanDir: File = File(ctx.filesDir, "scan").apply { mkdirs() }

    private companion object {
        // 与网页端 SUPPORTED_RE 保持一致
        val SUPPORTED_EXT =
            Regex("""\.(ncm|kwm|qmc\d*|qmcflac|qmcogg|mflac\d*|mgg\d*|mggl|tkm)$""")
    }

    private fun raw(): JSONArray = try {
        JSONArray(prefs.getString("list", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    /** 返回给网页：[{id, name}] */
    fun list(): String {
        val out = JSONArray()
        val saved = raw()
        for (i in 0 until saved.length()) {
            val o = saved.optJSONObject(i) ?: continue
            out.put(JSONObject().apply {
                put("id", o.optString("id"))
                put("name", o.optString("name"))
            })
        }
        return out.toString()
    }

    fun add(uri: Uri, name: String): Boolean = try {
        val saved = raw()
        var dup = false
        for (i in 0 until saved.length()) {
            if (saved.optJSONObject(i)?.optString("uri") == uri.toString()) {
                dup = true
                break
            }
        }
        if (!dup) {
            saved.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString().take(8))
                put("uri", uri.toString())
                put("name", name)
            })
            prefs.edit().putString("list", saved.toString()).apply()
        }
        true
    } catch (e: Exception) {
        false
    }

    fun remove(id: String): Boolean = try {
        val saved = raw()
        val kept = JSONArray()
        for (i in 0 until saved.length()) {
            val o = saved.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        prefs.edit().putString("list", kept.toString()).apply()
        true
    } catch (e: Exception) {
        false
    }

    private fun uriOf(id: String): String? {
        val saved = raw()
        for (i in 0 until saved.length()) {
            val o = saved.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("uri")
        }
        return null
    }

    /** 扫描指定目录，复制命中的 .ncm 到私有目录，返回 [{name, size, url}] */
    fun scan(id: String): String {
        val uriStr = uriOf(id) ?: return "[]"
        val tree = try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        } ?: return "[]"

        val out = JSONArray()
        val limit = 600

        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 6 || out.length() >= limit) return
            val kids = try { dir.listFiles() } catch (e: Exception) { return }
            for (f in kids) {
                if (out.length() >= limit) return
                if (f.isDirectory) { walk(f, depth + 1); continue }
                val n = f.name ?: continue
                if (!SUPPORTED_EXT.containsMatchIn(n.lowercase())) continue

                // 不复制文件，直接把 content URI 交给网页，由原生桥分块读取。
                // 复制方案占空间，且第二次扫描会清掉第一批临时文件导致地址失效（404）。
                out.put(JSONObject().apply {
                    put("name", n)
                    put("size", f.length())
                    put("uri", f.uri.toString())
                    // 记录源文件所在目录，便于转换后原地保存
                    put("dir", dir.uri.toString())
                })
            }
        }

        walk(tree, 0)
        return out.toString()
    }

    fun cleanup() {
        scanDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /* ---------------- 指定保存目录 ---------------- */

    fun saveDirGet(): String {
        val uri = prefs.getString("saveDir", "") ?: ""
        if (uri.isEmpty()) return ""
        val name = prefs.getString("saveDirName", "") ?: ""
        return JSONObject().apply {
            put("uri", uri)
            put("name", name)
        }.toString()
    }

    fun saveDirSet(uri: Uri, name: String) {
        prefs.edit()
            .putString("saveDir", uri.toString())
            .putString("saveDirName", name)
            .apply()
    }

    fun saveDirClear() {
        prefs.edit().remove("saveDir").remove("saveDirName").apply()
    }
}
