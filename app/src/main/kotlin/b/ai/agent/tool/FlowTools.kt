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

/** 工作流与脚本：存档、自动拟合、草稿步骤、JS 执行；定时参数的时间解析也在这里 */
internal object FlowTools {
    fun flowSave(args: JSONObject): ToolOutcome {
        val steps = Flows.stepsFrom(args.optJSONArray("steps"))
        if (steps.isEmpty()) return ToolOutcome(false, "flow_save 需要至少一个带 task 的步骤", Route.SANDBOX)
        val daily = args.optBoolean("daily", false)
        val at = parseAt(args.optString("at"), daily)
        val name = args.optString("name").trim().ifBlank { "工作流" }
        val auto = args.optBoolean("auto", true)
        val off = args.optString("id").trim()
        val old = if (off.isNotBlank()) Flows.byId(off) else null
        val f = if (old != null) {
            val merged = old.copy(
                name = name, steps = steps, auto = auto,
                at = if (at > 0L) at else old.at,
                daily = if (at > 0L) daily else old.daily,
                on = args.optBoolean("on", true)
            )
            Flows.update(merged)
            merged
        } else {
            Flows.add(name, steps, at, daily, auto)
        }
        if (f.at > 0L) Alarms.scheduleFlow(App.self, f) else Alarms.cancelFlow(App.self, f.id)
        val sb = StringBuilder()
        sb.append("已保存工作流「").append(f.name).append("」（id=").append(f.id).append("），共 ")
        sb.append(f.steps.size).append(" 步")
        if (f.at > 0L) {
            sb.append("，").append(if (f.daily) "每天 " else "").append(Alarms.fmt(f.at))
            sb.append(if (f.auto) " 自动执行" else " 只提醒")
        } else {
            sb.append("，未绑定定时")
        }
        sb.append("。要马上跑就调 flow_run 带上这个 id。")
        return ToolOutcome(true, sb.toString(), Route.SANDBOX)
    } //保存工作流

    fun flowAuto(args: JSONObject): ToolOutcome {
        val goal = args.optString("goal").trim()
        if (goal.isBlank()) return ToolOutcome(false, "flow_auto 需要一个目标", Route.SANDBOX)
        val name = args.optString("name").trim().ifBlank { goal.take(12) }
        var steps = Flows.stepsFrom(args.optJSONArray("steps"))
        val drafted = steps.isEmpty()
        if (drafted) steps = draftSteps(goal)
        if (steps.isEmpty()) {
            return ToolOutcome(false, "没能拆出步骤：把目标写得更具体一点，或者直接给 steps", Route.SANDBOX)
        }
        //自测步骤：模型干活容易「自称完成」，最后一步必须自己验一遍
        if (steps.none { it.title.contains("自测") || it.title.contains("验收") }) {
            steps = steps + b.ai.agent.flow.FlowStep(
                "自测与验收",
                "逐项验证前面每一步的产出：能执行的命令就跑一遍并贴结果；不能执行的列出验收点与判断依据。" +
                    "任何一项没通过，说明原因、影响范围与修复建议，不要只说「已完成」。"
            )
        }
        val daily = args.optBoolean("daily", false)
        val at = parseAt(args.optString("at"), daily)
        val f = Flows.add(name, steps.take(Flows.MAX_STEPS), at, daily, true)
        if (f.at > 0L) Alarms.scheduleFlow(App.self, f)

        val sb = StringBuilder()
        sb.append("已创建工作流「").append(f.name).append("」（id=").append(f.id).append("），共 ")
        sb.append(f.steps.size).append(" 步")
        sb.append(if (drafted) "，步骤由模型按目标拆解" else "，步骤用的是你给的清单")
        if (f.at > 0L) sb.append("，定时 ").append(Alarms.fmt(f.at))
        sb.append("。\n\n步骤：\n")
        f.steps.forEachIndexed { i, st -> sb.append(i + 1).append(". ").append(st.title).append("\n") }
        sb.append("\n测试清单（按序验，全过才算完成）：\n")
        f.steps.forEachIndexed { i, st ->
            sb.append("- [ ] ").append(i + 1).append(". ").append(st.title.take(20))
            sb.append("：产出可核对、失败有明确报错\n")
        }
        sb.append("\n要立刻跑就调 flow_run 带上 id=").append(f.id).append("。")
        return ToolOutcome(true, sb.toString(), Route.SANDBOX)
    } //自动建流

    fun draftSteps(goal: String): List<b.ai.agent.flow.FlowStep> {
        val m = FnModel.conn(FnModel.SUMMARY)
        if (m[0].isNotBlank() && m[3].isNotBlank()) {
            val pay = JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你是任务拆解器。把用户的目标拆成 2-6 个可执行步骤，每步一句话，写清做什么与产出物。" +
                        "只输出 JSON 数组，形如 [{\"title\":\"短标题\",\"task\":\"这一步要做什么\"}]，" +
                        "不要解释、不要 Markdown 代码块、不要额外字段。"))
                .put(JSONObject().put("role", "user").put("content", goal))
            val txt = runCatching {
                LlmClient(m[0], m[1], Providers.byId(m[2]).auth, protocol = m[4])
                    .chat(m[3], pay, temperature = 0.3, tools = JSONArray(), opts = Cfg.opts(m[2], m[0]))
                    .optString("content")
            }.getOrNull().orEmpty()
            val arr = runCatching {
                val s = txt.indexOf('[')
                val e = txt.lastIndexOf(']')
                if (s >= 0 && e > s) JSONArray(txt.substring(s, e + 1)) else JSONArray()
            }.getOrNull()
            if (arr != null && arr.length() > 0) {
                val out = ArrayList<b.ai.agent.flow.FlowStep>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("task").trim().ifBlank { o.optString("title").trim() }
                    if (t.isEmpty()) continue
                    out.add(b.ai.agent.flow.FlowStep(o.optString("title").trim().ifBlank { "步骤" + (out.size + 1) }, t))
                }
                if (out.isNotEmpty()) return out.take(Flows.MAX_STEPS)
            }
        }
        //没有可用模型就按行/分号切：至少能得到一条能跑的工作流
        return goal.split('\n', '；', ';')
            .map { it.trim().trim('-', '*', '·', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '、', ' ') }
            .filter { it.length >= 4 }
            .take(6)
            .mapIndexed { i, t -> b.ai.agent.flow.FlowStep("步骤" + (i + 1), t) }
    } //拆步骤

    fun flowList(): ToolOutcome {
        val list = Flows.list
        if (list.isEmpty()) return ToolOutcome(true, "还没有保存过工作流。可以用 flow_save 建一条。", Route.SANDBOX)
        val sb = StringBuilder("共 ").append(list.size).append(" 条工作流：\n")
        list.forEach { f ->
            sb.append("\n- ").append(f.name).append("（id=").append(f.id).append("）")
            sb.append("，").append(f.steps.size).append(" 步")
            if (f.at > 0L) {
                sb.append("，").append(if (f.daily) "每天 " else "单次 ").append(Alarms.fmt(f.at))
                if (!f.on) sb.append("（已关）")
            } else {
                sb.append("，未绑定定时")
            }
            if (f.lastAt > 0L) {
                sb.append("\n  上次 ").append(Alarms.fmt(f.lastAt)).append(if (f.lastOk) " 成功" else " 失败")
                if (f.lastNote.isNotBlank()) sb.append("：").append(f.lastNote.replace("\n", " ").take(60))
            }
            sb.append("\n  步骤：").append(f.steps.joinToString(" → ") { it.title.take(10) })
        }
        return ToolOutcome(true, sb.toString(), Route.SANDBOX)
    } //列工作流

    fun jsRun(args: JSONObject): ToolOutcome {
        val code = args.optString("script")
        if (code.isBlank()) return ToolOutcome(false, "js 需要 script 参数", Route.SANDBOX)
        val ms = args.optInt("timeout", 15_000).coerceIn(1_000, 300_000)
        val r = Js.run(code, ms)
        return ToolOutcome(r.first, r.second, Route.SANDBOX)
    } //跑 JS 脚本

    fun jsTool(name: String, args: JSONObject): ToolOutcome? {
        if (!Js.has(name)) return null
        val r = Js.call(name, args)
        return ToolOutcome(r.first, r.second, Route.SANDBOX)
    } //跑 JS 注册的工具

    fun parseAt(raw: String, daily: Boolean): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        val now = System.currentTimeMillis()
        var at = 0L
        for (pat in listOf("yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm", "HH:mm")) {
            val f = SimpleDateFormat(pat, Locale.CHINA)
            f.isLenient = false
            val d = runCatching { f.parse(s) }.getOrNull() ?: continue
            at = d.time
            if (pat == "HH:mm") {
                val src = Calendar.getInstance().apply { time = d }
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
                c.set(Calendar.MINUTE, src.get(Calendar.MINUTE))
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                at = c.timeInMillis
            }
            break
        }
        if (at <= 0L) return 0L
        if (at > now) return at
        return if (daily) Schedules.rollForward(at, true, now) else 0L
    } //定时时间解析
}
