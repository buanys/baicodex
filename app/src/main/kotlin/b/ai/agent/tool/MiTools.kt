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

/** miTools */
internal object MiTools {
    fun miDevices(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "list").trim().lowercase()
        if (act == "sync") {
            val r = MiHome.syncOut()
            return ToolOutcome(r.ok, r.text, Route.SANDBOX)
        }
        if (act == "add") {
            val did = a.optString("did").trim()
            val ip = a.optString("ip").trim()
            val tok = a.optString("token").trim()
            if (did.isEmpty() || ip.isEmpty() || tok.isEmpty()) {
                return ToolOutcome(false, "add 需要 did、ip、token 三个参数", Route.SANDBOX)
            }
            val err = MiHome.addManual(did, ip, tok, a.optString("name").trim())
            return ToolOutcome(err.isEmpty(), err.ifEmpty { "已加入台账：$did @ $ip" }, Route.SANDBOX)
        }
        val rows = MiStore.load()
        val sb = StringBuilder()
        sb.append("登录态：").append(if (MiAuth.logged()) "已登录" else "未登录（只能用手工补的带 token 设备）").append('\n')
        if (rows.isEmpty()) {
            sb.append("台账里没有设备。action=sync 拉云端，或 action=add 手工补一台。")
            return ToolOutcome(true, sb.toString(), Route.SANDBOX)
        }
        sb.append("共 ${rows.size} 台：\n")
        rows.forEach { r ->
            sb.append("- ").append(r.did).append(" · ").append(r.name.ifEmpty { "未命名" })
            if (r.model.isNotEmpty()) sb.append(" · ").append(r.model)
            sb.append(" · ").append(if (r.ip.isEmpty()) "IP 未知" else r.ip)
            sb.append(" · ").append(if (r.online) "在线" else "离线")
            sb.append(" · ").append(if (MiStore.token(r.did).isEmpty()) "无 token" else "有 token")
            if (r.roomName.isNotEmpty()) sb.append(" · ").append(r.roomName)
            sb.append('\n')
        }
        return ToolOutcome(true, sb.toString().trimEnd(), Route.SANDBOX)
    } //米家台账

    fun miScan(a: JSONObject): ToolOutcome {
        val ms = a.optInt("timeout", 2500).coerceIn(500, 10_000)
        if (MiStore.load().isEmpty()) {
            return ToolOutcome(false, "台账里没有设备，先 mijia_devices 的 sync / add", Route.SANDBOX)
        }
        val hit = MiHome.lanScan(ms)
        val t = if (hit == 0) {
            "扫了 ${ms}ms，没有设备应答（不在同一网段，或设备没开局域网协议）"
        } else {
            "扫到 $hit 台，IP 已回填台账"
        }
        return ToolOutcome(true, t, Route.SANDBOX)
    } //局域网扫描

    fun miRead(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val piid = a.optInt("piid", -1)
        if (siid < 0 || piid < 0) return ToolOutcome(false, "siid / piid 必须是非负整数", Route.SANDBOX)
        val v = MiHome.read(did, siid, piid)
            ?: return ToolOutcome(false, "读不到属性（设备离线、token 失效，或云端没登录）", Route.SANDBOX)
        return ToolOutcome(true, "did=$did siid=$siid piid=$piid → $v", Route.SANDBOX)
    } //读属性

    fun miWrite(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val piid = a.optInt("piid", -1)
        if (siid < 0 || piid < 0) return ToolOutcome(false, "siid / piid 必须是非负整数", Route.SANDBOX)
        val v = valueOf(a.optString("value"))
        val err = MiHome.write(did, siid, piid, v)
        return ToolOutcome(err == null, err ?: "已写入：did=$did siid=$siid piid=$piid = $v", Route.SANDBOX)
    } //写属性

    fun miAction(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val aiid = a.optInt("aiid", -1)
        if (siid < 0 || aiid < 0) return ToolOutcome(false, "siid / aiid 必须是非负整数", Route.SANDBOX)
        val err = MiHome.action(did, siid, aiid, argsOf(a.optString("args")))
        return ToolOutcome(err == null, err ?: "动作已下发：did=$did siid=$siid aiid=$aiid", Route.SANDBOX)
    } //调动作

    fun valueOf(raw: String): Any {
        val s = raw.trim()
        s.lowercase().toBooleanStrictOrNull()?.let { return it }
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        return s
    } //值转型

    fun argsOf(raw: String): List<Any> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        if (s.startsWith("[")) {
            val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).map { i ->
                when (val v = arr.opt(i)) {
                    is Number -> v
                    is Boolean -> v
                    null -> ""
                    else -> v.toString()
                }
            }
        }
        return s.split(',').map { valueOf(it) }
    } //动作参数表
}
