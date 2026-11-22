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

/** 协作与局域网：接活服务、设备发现、对端信息 */
internal object CollabTools {
    fun a2a(args: JSONObject): ToolOutcome {
        val act = args.optString("action").lowercase().trim().ifBlank { "nodes" }
        if (act == "nodes") {
            val all = A2aNodes.list
            if (all.isEmpty()) {
                return ToolOutcome(
                    true,
                    "还没有保存过 A2A 对端。设置 → 扩展 → A2A 里加一条，或者直接在 node 里写 http(s):// 地址。",
                    Route.SANDBOX
                )
            }
            val sb = StringBuilder("共 ").append(all.size).append(" 个对端：\n")
            all.forEach {
                sb.append("- ").append(it.name).append("  ").append(it.url)
                if (it.token.isNotBlank()) sb.append("（带 token）")
                sb.append('\n')
            }
            return ToolOutcome(true, sb.toString().trim(), Route.SANDBOX)
        }
        val node = a2aNode(args.optString("node"))
            ?: return ToolOutcome(
                false,
                "找不到对端「" + args.optString("node") + "」。先调 action=nodes 看已保存的，或直接写 http(s):// 地址。",
                Route.SANDBOX
            )
        val r: A2aRes = when (act) {
            "card" -> A2aClient.card(node)
            "send" -> A2aClient.send(node, args.optString("text"))
            "get" -> A2aClient.getTask(node, args.optString("task"))
            "cancel" -> A2aClient.cancelTask(node, args.optString("task"))
            "tasks" -> A2aClient.listTasks(node, args.optString("context"))
            else -> A2aRes(false, "不认识的 action：" + act)
        }
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //A2A 调用

    fun a2aNode(key: String): A2aNode? {
        val k = key.trim()
        if (k.isEmpty()) return null
        A2aNodes.find(k)?.let { return it }
        if (!k.startsWith("http://") && !k.startsWith("https://")) return null
        return A2aNode(id = "", name = k, url = A2aNodes.norm(k))
    } //对端解析

    fun lanTool(a: JSONObject): ToolOutcome {
        val act = a.optString("action").lowercase().trim().ifBlank { "status" }
        when (act) {
            "status", "peers" -> {
                val sb = StringBuilder(Team.self()).append('\n')
                val list = LanCfg.peers()
                if (list.isEmpty()) {
                    sb.append("还没配对过设备：先 action=scan 找设备，再 action=pair 输对方配对码。")
                } else {
                    sb.append("已配对 ").append(list.size).append(" 台：\n")
                    list.forEach { sb.append(peerLine(it)).append('\n') }
                }
                return ToolOutcome(true, sb.toString().trim(), Route.SANDBOX)
            } //状态与台账
            "serve" -> {
                if (!a.optBoolean("on", true)) {
                    Cfg.a2aOn = false
                    A2aSrv.stop()
                    return ToolOutcome(true, "本机已停止接活：对面再也派不进来了", Route.SANDBOX)
                }
                val port = a.optInt("port", 0).takeIf { it in 1024..65535 } ?: Cfg.a2aPort
                Cfg.a2aPort = port
                Cfg.a2aOn = true
                val msg = A2aSrv.start(port)
                //成败问状态：服务真在监听才算开成功，返回的文案只当说明用
                if (!A2aSrv.running) {
                    Cfg.a2aOn = false
                    return ToolOutcome(false, "开不了接活服务：" + msg, Route.SANDBOX)
                }
                return ToolOutcome(true, "本机已在 " + A2aSrv.baseUrl() + " 接活；对面配对一次就能派活过来", Route.SANDBOX)
            } //开/关本机接活服务
            "scan" -> {
                val found = Lan.scan(a.optInt("ms", 2500))
                if (found.isEmpty()) {
                    return ToolOutcome(
                        false,
                        "没扫到设备：确认两台在同一 Wi-Fi（或同一热点）、对方「允许被发现」开着；系统挡了局域网权限也会这样。",
                        Route.SANDBOX
                    )
                }
                val paired = LanCfg.peers().map { it.id }.toSet()
                val sb = StringBuilder("扫到 ").append(found.size).append(" 台：\n")
                found.forEach {
                    sb.append("- ").append(it.name.ifBlank { it.id }).append(' ').append(it.ip).append(':').append(it.port)
                    sb.append(if (it.a2aPort > 0) " 可接活（协作端口 ${it.a2aPort}）" else " 没开协作")
                    if (it.id in paired) sb.append(" 已配对")
                    sb.append('\n')
                }
                sb.append("要配对就 action=pair peer=<名字或设备号> code=<对方 6 位码>")
                return ToolOutcome(true, sb.toString().trim(), Route.SANDBOX)
            } //扫同一 Wi-Fi
            "pair" -> {
                val code = a.optString("code").trim()
                if (code.isEmpty()) return ToolOutcome(false, "要对方 6 位配对码（在它「面对面团队协作」页上）", Route.SANDBOX)
                val hit = findPeer(a.optString("peer"))
                    ?: return ToolOutcome(false, "扫不到「" + a.optString("peer") + "」：先 action=scan 看设备名", Route.SANDBOX)
                val err = Lan.pair(hit, code)
                if (err.isNotEmpty()) return ToolOutcome(false, err, Route.SANDBOX)
                Team.ensureServe()
                val p = LanCfg.peers().firstOrNull { it.id == hit.id } ?: hit
                return ToolOutcome(true, "配对成功：" + peerLine(p), Route.SANDBOX)
            } //配对：对端校验通过才算
            "unpair" -> {
                val p = Team.find(a.optString("peer"))
                    ?: return ToolOutcome(false, "台账里没这台设备", Route.SANDBOX)
                LanCfg.removePeer(p.id)
                return ToolOutcome(true, "已解除配对：" + p.name.ifBlank { p.ip }, Route.SANDBOX)
            }
            "card" -> {
                val p = findPeer(a.optString("peer")) ?: return ToolOutcome(false, "找不到这台设备：先 action=scan", Route.SANDBOX)
                val r = Team.card(p)
                return ToolOutcome(r.ok, r.text, Route.SANDBOX)
            } //看对面能力
            "assign" -> {
                val task = a.optString("task").trim()
                if (task.isEmpty()) return ToolOutcome(false, "task 要写清楚让对面做什么，别只写「帮我看一下」", Route.SANDBOX)
                val p = findPeer(a.optString("peer")) ?: return ToolOutcome(false, "找不到这台设备：先 action=scan", Route.SANDBOX)
                Team.ensureServe()
                val r = Team.assign(p, task, a.optLong("wait", 120_000L).coerceIn(10_000L, 600_000L))
                return ToolOutcome(r.ok, r.text, Route.SANDBOX)
            } //派活并等对面大模型跑完
            "tasks" -> {
                val p = Team.find(a.optString("peer")) ?: return ToolOutcome(false, "台账里没这台设备", Route.SANDBOX)
                val r = Team.tasks(p)
                return ToolOutcome(r.ok, r.text, Route.SANDBOX)
            }
            "cancel" -> {
                val p = Team.find(a.optString("peer")) ?: return ToolOutcome(false, "台账里没这台设备", Route.SANDBOX)
                val r = Team.cancel(p, a.optString("task"))
                return ToolOutcome(r.ok, r.text, Route.SANDBOX)
            }
            else -> return ToolOutcome(
                false,
                "不认识的 action：$act（status / serve / scan / pair / peers / unpair / card / assign / tasks / cancel）",
                Route.SANDBOX
            )
        }
    } //局域网协作

    fun peerLine(p: LanCfg.Peer): String {
        val ago = p.lastSeen.takeIf { it > 0 }?.let {
            val m = (System.currentTimeMillis() - it) / 60_000
            if (m <= 0) "刚刚" else m.toString() + " 分钟前"
        } ?: "没探活过"
        val a2a = if (p.a2aPort > 0) "协作端口 " + p.a2aPort else "没开协作"
        return "- " + p.name.ifBlank { p.ip } + " " + p.ip + ":" + p.port + "，" + a2a + "，上次 " + ago + "（设备号 " + p.id + "）"
    } //一行一台设备，给模型看也给人看

    fun findPeer(key: String): LanCfg.Peer? {
        Team.find(key)?.let { return it }
        val k = key.trim()
        if (k.isEmpty()) return null
        return Lan.scan(2000).firstOrNull {
            it.id.equals(k, true) || it.name.equals(k, true) || it.ip == k || it.name.contains(k, true)
        }
    } //先查台账，再现场扫
}
