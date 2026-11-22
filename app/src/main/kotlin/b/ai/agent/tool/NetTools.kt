/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.tool

import android.util.Log
import b.ai.App
import b.ai.agent.flow.Flows
import b.ai.agent.memory.Memory
import b.ai.core.cfg.app.AgentMode
import b.ai.core.cfg.app.Cfg
import b.ai.core.cfg.secret.Secret
import b.ai.core.data.db.Repo
import b.ai.core.data.io.Layout
import b.ai.core.media.music.MusicCfg
import b.ai.core.misc.apk.Apk
import b.ai.core.model.chat.FnModel
import b.ai.core.model.chat.Route
import b.ai.core.model.chat.WorkMode
import b.ai.core.plugin.skill.Providers
import b.ai.core.plugin.skill.SkillFn
import b.ai.core.plugin.skill.SkillPacks
import b.ai.core.task.alarm.Schedules
import b.ai.device.fs.FileOps
import b.ai.device.fs.Workspace
import b.ai.device.shell.local.Linux
import b.ai.device.shell.local.Shell
import b.ai.device.shell.root.Root
import b.ai.device.shell.root.Shiz
import b.ai.device.shell.session.Term
import b.ai.device.sys.alarm.Alarms
import b.ai.device.sys.probe.Router
import b.ai.native.net.Net
import b.ai.native.script.Dsl
import b.ai.native.script.Js
import b.ai.net.api.a2a.A2A as A2aClient
import b.ai.net.api.a2a.A2aNode
import b.ai.net.api.a2a.A2aNodes
import b.ai.net.api.a2a.A2aRes
import b.ai.net.api.a2a.A2aSrv
import b.ai.net.api.llm.LlmClient
import b.ai.net.api.llm.text
import b.ai.net.lan.Lan
import b.ai.net.lan.LanCfg
import b.ai.net.lan.Team
import b.ai.net.mcp.Mcp
import b.ai.net.mi.MiAuth
import b.ai.net.mi.MiHome
import b.ai.net.mi.MiStore
import b.ai.net.music.NeteaseLogin
import b.ai.net.search.Bing
import b.ai.net.search.GoogleCse
import b.ai.net.search.Rail
import b.ai.net.search.Search
import b.ai.net.search.Tavily
import b.ai.ui.widget.tap
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 网络类工具：测延迟、抓正文、直连抓取、APK 信息 */
internal object NetTools {
    fun netPing(a: JSONObject): ToolOutcome {
        val host = a.optString("host").trim()
        if (host.isEmpty()) return ToolOutcome(false, "缺少 host", Route.SANDBOX)
        val port = a.optInt("port", 443).coerceIn(1, 65535)
        val count = a.optInt("count", 3).coerceIn(1, 10)
        val ms = a.optInt("timeout", 3000).coerceIn(500, 30_000)
        val r = Net.ping(host, port, count, ms)
        return ToolOutcome(!Net.failed(r), r, Route.SANDBOX)
    } //测延迟

    fun netCurl(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val method = a.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val ms = a.optInt("timeout", 15_000).coerceIn(1_000, 120_000)
        val max = a.optInt("max", 20_000).coerceIn(200, 1_000_000)
        val r = Net.curl(method, url, a.optString("headers"), a.optString("body"), ms, max)
        val code = r.removePrefix("HTTP ").substringBefore('\n').trim().toIntOrNull()
        return ToolOutcome(code != null && code < 400, r, Route.SANDBOX)
    } //发 curl

    fun netPage(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val ms = a.optInt("timeout", 15_000).coerceIn(1_000, 120_000)
        val max = a.optInt("max", 20_000).coerceIn(200, 200_000)
        val r = Net.page(url, ms, max)
        return ToolOutcome(!Net.failed(r), r, Route.SANDBOX)
    } //抓正文

    fun httpFetch(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val method = a.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val body = a.optString("body")
        val heads = ArrayList<Pair<String, String>>()
        a.optString("headers").lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) heads.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return runCatching {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            heads.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            val ctype = conn.contentType.orEmpty()
            conn.disconnect()
            val head = "HTTP $code" + if (ctype.isNotEmpty()) "（$ctype）" else ""
            val cut = if (text.length > 8000) text.take(8000) + "\n…已截断（共 ${text.length} 字符）" else text
            ToolOutcome(code in 200..299, "$head\n$cut", Route.SANDBOX)
        }.getOrElse {
            ToolOutcome(false, "请求失败：" + (it.message ?: it.javaClass.simpleName), Route.SANDBOX)
        }
    } //直发 HTTP

    fun httpRaw(text: String): ToolOutcome {
        val nl = text.indexOf('\n')
        val first = (if (nl < 0) text else text.substring(0, nl)).trim()
        val seg = first.split(" ", limit = 2)
        if (seg.size < 2) return ToolOutcome(false, "http 报文首行不合法", Route.SANDBOX)
        val method = seg[0].trim().uppercase()
        val url = seg[1].trim()
        val headers = ArrayList<Pair<String, String>>()
        var i = if (nl < 0) text.length else nl + 1
        var bodyStart = text.length
        while (i < text.length) {
            val e = text.indexOf('\n', i)
            val row = text.substring(i, if (e < 0) text.length else e).trim()
            if (row.isEmpty()) {
                bodyStart = if (e < 0) text.length else e + 1
                break
            }
            val c = row.indexOf(':')
            if (c > 0) headers.add(row.substring(0, c).trim() to row.substring(c + 1).trim())
            i = if (e < 0) text.length else e + 1
        }
        val body = if (bodyStart < text.length) text.substring(bodyStart) else ""
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val txt = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }
                .getOrDefault("")
            conn.disconnect()
            ToolOutcome(code in 200..299, "HTTP $code\n" + txt.take(6000), Route.SANDBOX)
        }.getOrElse { ToolOutcome(false, "请求失败：" + (it.message ?: it.javaClass.simpleName), Route.SANDBOX) }
    } //发 http 技能请求

    fun browse(a: JSONObject): ToolOutcome {
        val t = b.ai.device.web.Browser.run(
            a.optString("action", "open"), a.optString("url"), a.optString("text"),
            a.optString("target"), a.optString("script"), a.optInt("limit", 4000)
        )
        return ToolOutcome(!b.ai.device.web.Browser.failed(t), t, Route.SANDBOX)
    } //浏览网页

    fun apk(a: JSONObject): ToolOutcome {
        val r = Apk.run(
            a.optString("action", "info"), a.optString("path"),
            a.optString("keyword"), a.optInt("limit", 60)
        )
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //看安装包
}
