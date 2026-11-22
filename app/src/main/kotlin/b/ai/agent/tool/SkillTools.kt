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

/** 技能、长期记忆与 MCP 调用 */
internal object SkillTools {
    fun skillRead(key: String): ToolOutcome {
        val p = SkillPacks.pack(key) ?: return ToolOutcome(false, "没有这个技能：$key", Route.SANDBOX)
        val fns = p.funcs.joinToString("\n") { f ->
            " - ${f.name}(${f.params.keys.joinToString(", ")})" +
                if (f.bad.isEmpty()) "" else " ←包有问题：${f.bad}"
        }
        val sb = StringBuilder()
        sb.append("# ").append(p.name.ifBlank { p.id }).append("（").append(p.id).append("）\n")
        if (p.desc.isNotBlank()) sb.append(p.desc).append('\n')
        if (fns.isNotBlank()) sb.append("函数：\n").append(fns).append('\n')
        if (p.dir.isNotBlank()) sb.append("目录：").append(p.dir).append('\n')
        sb.append('\n').append(p.readme.take(6000))
        return ToolOutcome(true, sb.toString().trim(), Route.SANDBOX)
    } //返回技能用法

    fun skillExec(f: SkillFn, args: JSONObject): ToolOutcome {
        if (f.bad.isNotEmpty()) return ToolOutcome(false, "技能包校验没过：${f.bad}", Route.SANDBOX)
        val vals = LinkedHashMap<String, String>()
        f.params.forEach { (k, _) ->
            val v = args.optString(k, "")
            if (v.isEmpty()) return ToolOutcome(false, "缺少参数 $k（${f.name}）", Route.SANDBOX)
            vals[k] = v
        }
        val (ok, out) = Dsl.render(f.body, f.kind, f.params, Secret.all(), vals)
        if (!ok) return ToolOutcome(false, "技能模板校验失败：$out", Route.SANDBOX)
        if (f.kind == "http") return NetTools.httpRaw(out)
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ToolOutcome(false, "插值后没有可执行命令", Route.SANDBOX)
        val sb = StringBuilder()
        var route = Route.SANDBOX
        for (l in lines) {
            val o = ShellTools.execShell(l)
            route = o.route
            sb.append(o.text).append('\n')
            if (!o.ok) return ToolOutcome(false, sb.toString().trim(), route)
        }
        return ToolOutcome(true, sb.toString().trim(), route)
    } //执行技能函数

    fun memorySave(args: JSONObject): ToolOutcome {
        val t = args.optString("text").trim()
        if (t.isEmpty()) return ToolOutcome(false, "memory_save 需要 text 参数", Route.SANDBOX)
        val ok = Memory.add(t, "")
        return if (ok) ToolOutcome(true, "已记住：$t", Route.SANDBOX)
        else ToolOutcome(true, "这条已经记过了：$t", Route.SANDBOX)
    } //存记忆

    fun memorySearch(args: JSONObject): ToolOutcome {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolOutcome(false, "memory_search 需要 query 参数", Route.SANDBOX)
        val total = Memory.rows.size
        if (total == 0) return ToolOutcome(true, "永久记忆是空的，没有可查的内容。", Route.SANDBOX)
        val limit = args.optInt("limit", 20)
        val hit = Memory.search(q, limit)
        if (hit.isEmpty()) {
            return ToolOutcome(
                true,
                "没有匹配「$q」的记忆（共 $total 条）。换个说法或拆成更短的关键词再试；确实没记过就说明该问用户了。",
                Route.SANDBOX
            )
        }
        val sb = StringBuilder("命中 ${hit.size} 条（共 $total 条记忆，按相关度排序）：\n")
        hit.forEach { sb.append("[id=").append(it.id).append("] ").append(it.text).append('\n') }
        return ToolOutcome(true, sb.toString().trimEnd(), Route.SANDBOX)
    } //搜记忆

    fun memoryDelete(args: JSONObject): ToolOutcome {
        val id = args.optLong("id", 0L)
        if (id > 0L) {
            val hit = Memory.rows.firstOrNull { it.id == id }
                ?: return ToolOutcome(false, "没有 id=$id 的记忆，先用 memory_search 查一下 id", Route.SANDBOX)
            Memory.delete(id)
            return ToolOutcome(true, "已删除 [id=$id] ${hit.text}（还剩 ${Memory.rows.size} 条）", Route.SANDBOX)
        }
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolOutcome(false, "memory_delete 需要 id 或 query 参数", Route.SANDBOX)
        val total = Memory.rows.size
        if (total == 0) return ToolOutcome(true, "永久记忆是空的，没有可删的内容。", Route.SANDBOX)
        val hit = Memory.search(q, 50)
        if (hit.isEmpty()) return ToolOutcome(true, "没有匹配「$q」的记忆（共 $total 条），无需删除。", Route.SANDBOX)
        if (hit.size > 1) {
            val sb = StringBuilder("「$q」命中 ${hit.size} 条，没有直接删，避免误删。确认删哪几条后按 id 逐条调用：\n")
            hit.forEach { sb.append("[id=").append(it.id).append("] ").append(it.text).append('\n') }
            return ToolOutcome(true, sb.toString().trimEnd(), Route.SANDBOX)
        }
        Memory.delete(hit[0].id)
        return ToolOutcome(true, "已删除 [id=${hit[0].id}] ${hit[0].text}（还剩 ${Memory.rows.size} 条）", Route.SANDBOX)
    } //删记忆

    fun mcpCall(full: String, args: JSONObject): ToolOutcome {
        if (!full.startsWith(Tools.MCP)) return ToolOutcome(false, "未知工具: $full", Route.SANDBOX)
        val t = Mcp.findTool(full)
            ?: return ToolOutcome(
                false,
                "MCP 能力 $full 不在本地缓存里：请到 设置 → 扩展 → MCP 服务 里点一次「刷新能力」。",
                Route.SANDBOX
            )
        val s = Mcp.serverOf(t) ?: return ToolOutcome(false, "MCP 服务已被删除：$full", Route.SANDBOX)
        if (!s.on) return ToolOutcome(false, "MCP 服务「${s.name}」已停用，本次调用没有发出去。", Route.SANDBOX)
        val text = Mcp.callTool(s, t.name, args)
        val err = Mcp.lastError
        if (err.isNotEmpty()) return ToolOutcome(false, "MCP 调用失败：$err", Route.SANDBOX)
        if (text.isBlank()) return ToolOutcome(false, "MCP 服务「${s.name}」没有返回内容", Route.SANDBOX)
        return ToolOutcome(true, text, Route.SANDBOX)
    } //调用 MCP 能力
}
