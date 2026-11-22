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

/** 搜索与车票查询：结果成败由各 provider 自己判 */
internal object SearchTools {
    fun search(args: JSONObject): ToolOutcome {
        val provider = Search.selected
            ?: return ToolOutcome(
                false,
                "未启用联网搜索：请到 设置 → 扩展 → 搜索平台 里选一个平台并填好 Key。",
                Route.SANDBOX
            )
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolOutcome(false, "搜索词为空", Route.SANDBOX)
        val n = args.optInt("max_results", 5).coerceIn(1, 10)
        val r = when (provider.id) {
            "tavily" -> Tavily.search(
                key = Cfg.searchCfg("tavily", "key"),
                query = query,
                maxResults = n,
                depth = args.optString("search_depth", "basic"),
                topic = args.optString("topic", "general"),
                days = args.optInt("days", 0).takeIf { it > 0 }
            )
            "bing" -> Bing.search(query, n)
            "google" -> GoogleCse.search(
                key = Cfg.searchCfg("google", "key"),
                cx = Cfg.searchCfg("google", "cx"),
                query = query,
                maxResults = n
            )
            //新增平台在这里加分支，同时在 core/Search.kt 的 providers 里登记
            else -> "搜索失败：平台 ${provider.id} 还没接入"
        }
        return ToolOutcome(!Search.failed(r), r, Route.SANDBOX)
    } //联网搜索

    fun rail(a: JSONObject): ToolOutcome {
        val text = runCatching {
            Rail.run(
                action = a.optString("action", "tickets"),
                from = a.optString("from"),
                to = a.optString("to"),
                date = a.optString("date"),
                middle = a.optString("middle").takeIf { it.isNotBlank() },
                trainType = a.optString("type").takeIf { it.isNotBlank() },
                sort = a.optString("sort").takeIf { it.isNotBlank() },
                limit = a.optInt("limit", 10),
                trainNo = a.optString("train_no").takeIf { it.isNotBlank() }
            )
        }.getOrElse { return ToolOutcome(false, "查询失败：${it.message}", Route.SANDBOX) }
        return ToolOutcome(!Rail.failed(text), text, Route.SANDBOX)
    } //火车票查询

    fun chatSearch(args: JSONObject): ToolOutcome {
        val q = args.optString("query").trim()
        if (q.isBlank()) return ToolOutcome(false, "chat_search 需要一个关键词", Route.SANDBOX)
        val sid = args.optString("session_id").trim()
        val role = args.optString("role", "all").trim().lowercase()
        val incSum = args.optBoolean("include_compressed", false)
        val hits = Repo.searchMessages(q, args.optInt("limit", 20), sid, role, incSum)
        if (hits.isEmpty()) {
            return ToolOutcome(
                true,
                "没有找到包含「$q」的历史消息" + (if (sid.isBlank()) "" else "（会话 $sid）") +
                    (if (role == "user") "，且限定为「我说的话」。" else if (role == "assistant") "，且限定为「AI 说的话」。" else "。") +
                    (if (!incSum) "压缩摘要未计入，需要的话带 include_compressed=true。" else ""),
                Route.SANDBOX
            )
        }
        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val uCount = hits.count { it.role == "user" }
        val aCount = hits.count { it.role == "assistant" }
        val sCount = hits.count { it.compressed }
        val sb = StringBuilder("找到 ").append(hits.size).append(" 条包含「").append(q).append("」的消息")
        sb.append("（用户 ").append(uCount).append(" / 助手 ").append(aCount)
        if (incSum) sb.append(" / 压缩摘要 ").append(sCount)
        sb.append("）：\n")
        hits.forEach { h ->
            val who = if (h.role == "user") "用户" else if (h.role == "assistant") "助手（AI）" else if (h.compressed) "压缩摘要" else h.role
            sb.append("\n- [").append(df.format(java.util.Date(h.at))).append("] ")
            sb.append(h.title.ifBlank { "未命名会话" }).append(" · ").append(who)
            sb.append("（session=").append(h.sessionId).append("）\n  ")
            //命中词前后各留一段，短消息直接给全文，避免片段看不出来龙去脉
            val body = h.content.replace('\n', ' ')
            val i = body.indexOf(q)
            val from = (i - 60).coerceAtLeast(0)
            val to = (i + q.length + 120).coerceAtMost(body.length)
            if (from > 0) sb.append("…")
            sb.append(body.substring(from, to))
            if (to < body.length) sb.append("…")
            sb.append('\n')
        }
        return ToolOutcome(true, sb.toString().trim(), Route.SANDBOX)
    } //搜聊天记录
}
