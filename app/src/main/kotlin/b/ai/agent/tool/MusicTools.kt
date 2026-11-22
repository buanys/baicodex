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

/** 音乐平台的工具实现：平台/音质参数解析也在这里 */
internal object MusicTools {
    fun musicText(c: MusicCfg.Cfg): String {
        val sb = StringBuilder("音质：").append(c.quality).append('\n')
        sb.append("优先级与启用：\n")
        c.order.forEachIndexed { i, id ->
            val ck = if (MusicCfg.cookie(id).isEmpty()) "未登录" else "已登录"
            sb.append(i + 1).append(". ").append(MusicCfg.PLATS[id].orEmpty())
                .append(" · ").append(if (c.on[id] == true) "启用" else "关")
                .append(" · ").append(ck).append('\n')
        }
        sb.append("缓存：").append(MusicCfg.sizeText(MusicCfg.cacheSize()))
        return sb.toString().trimEnd()
    } //平台现状

    fun musicPlats(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "list").trim().lowercase()
        val c = MusicCfg.load()
        if (act == "list" || act.isEmpty()) return ToolOutcome(true, musicText(c), Route.SANDBOX)
        if (act == "quality") {
            val q = qualityOf(a.optString("quality"))
                ?: return ToolOutcome(false, "音质只认：" + MusicCfg.QUALITIES.joinToString(" / "), Route.SANDBOX)
            val err = MusicCfg.save(MusicCfg.Cfg(c.order, c.on, q))
            return ToolOutcome(err.isEmpty(), err.ifEmpty { "音质改成 $q" }, Route.SANDBOX)
        }
        val id = platOf(a.optString("plat"))
        val keys = MusicCfg.PLATS.keys.joinToString(" / ")
        if (id == null) return ToolOutcome(false, "plat 只认 $keys，也可以写中文名", Route.SANDBOX)
        val name = MusicCfg.PLATS[id].orEmpty()
        if (act == "up") {
            val err = MusicCfg.moveUp(id)
            return ToolOutcome(err.isEmpty(), err.ifEmpty { "$name 已上移一位" }, Route.SANDBOX)
        }
        if (act == "disable") {
            if (c.on[id] != true) return ToolOutcome(true, "$name 本来就是关的", Route.SANDBOX)
            if (c.on.count { it.value } <= 1) {
                return ToolOutcome(false, "至少要留一个启用的平台", Route.SANDBOX)
            }
            val on = LinkedHashMap(c.on)
            on[id] = false
            val err = MusicCfg.save(MusicCfg.Cfg(c.order, on, c.quality))
            return ToolOutcome(err.isEmpty(), err.ifEmpty { "$name 已关闭" }, Route.SANDBOX)
        }
        if (act != "enable") return ToolOutcome(false, "action 只认 list / enable / disable / quality / up", Route.SANDBOX)
        val on = LinkedHashMap<String, Boolean>()
        MusicCfg.PLATS.keys.forEach { on[it] = it == id }
        val err = MusicCfg.save(MusicCfg.Cfg(c.order, on, c.quality))
        return ToolOutcome(err.isEmpty(), err.ifEmpty { "$name 已设为唯一启用" }, Route.SANDBOX)
    } //平台设置

    fun musicLogin(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "status").trim().lowercase()
        val id = platOf(a.optString("plat"))
        if (id == null) {
            return ToolOutcome(
                false, "plat 只认 " + MusicCfg.PLATS.keys.joinToString(" / ") + "，也可以写中文名", Route.SANDBOX
            )
        }
        val name = MusicCfg.PLATS[id].orEmpty()
        val area = a.optString("area", "86").trim().ifEmpty { "86" }
        if (act == "sms" || act == "code") {
            if (id != "netease") {
                return ToolOutcome(false, "只有网易云支持验证码登录，$name 请用 set 粘 cookie", Route.SANDBOX)
            }
            if (act == "sms") {
                val r = NeteaseLogin.sendCaptcha(a.optString("phone"), area)
                return ToolOutcome(r.isEmpty(), r.ifEmpty { "验证码已发出，转给用户" }, Route.SANDBOX)
            }
            val r = NeteaseLogin.loginByCaptcha(a.optString("phone"), a.optString("code"), area)
            return ToolOutcome(NeteaseLogin.loginOk(r), r, Route.SANDBOX)
        }
        if (act == "verify") {
            if (id != "netease") {
                return ToolOutcome(true, "$name：凭据已存（这家还没接账号校验）", Route.SANDBOX)
            }
            val r = NeteaseLogin.meOut()
            return ToolOutcome(r.ok, "$name：${r.text}", Route.SANDBOX)
        }
        if (act == "clear") {
            MusicCfg.clearCookie(id)
            return ToolOutcome(true, "$name 的登录态已清除", Route.SANDBOX)
        }
        if (act == "set") {
            val err = MusicCfg.setCookie(id, a.optString("cookie"))
            val n = MusicCfg.cookie(id).length
            return ToolOutcome(err.isEmpty(), err.ifEmpty { "$name 登录态已存加密库（$n 字符）" }, Route.SANDBOX)
        }
        val ck = MusicCfg.cookie(id)
        return ToolOutcome(true, if (ck.isEmpty()) "$name：未登录" else "$name：已存凭据（${ck.length} 字符）", Route.SANDBOX)
    } //登录态

    fun musicCache(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "clear").trim().lowercase()
        if (act == "size") {
            return ToolOutcome(true, "缓存占用 " + MusicCfg.sizeText(MusicCfg.cacheSize()), Route.SANDBOX)
        }
        val freed = MusicCfg.clearCache()
        return ToolOutcome(true, "已清空缓存，释放 " + MusicCfg.sizeText(freed), Route.SANDBOX)
    } //清缓存

    fun platOf(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        MusicCfg.PLATS.keys.firstOrNull { it == s }?.let { return it }
        return MusicCfg.PLATS.keys.firstOrNull { id ->
            val name = MusicCfg.PLATS[id].orEmpty().lowercase()
            name.contains(s) || s.contains(id)
        }
    } //平台标识

    fun qualityOf(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        MusicCfg.QUALITIES.firstOrNull { it.lowercase().contains(s) }?.let { return it }
        return when {
            s.contains("无损") || s == "lossless" -> MusicCfg.QUALITIES[3]
            s.contains("320") -> MusicCfg.QUALITIES[2]
            s.contains("192") -> MusicCfg.QUALITIES[1]
            s.contains("128") -> MusicCfg.QUALITIES[0]
            else -> null
        }
    } //音质档位
}
