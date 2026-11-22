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

/** 设备侧工具：屏幕、截图、启动应用、剪贴板、通知、终端视图 */
internal object DeviceTools {
    fun screen(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "see").lowercase()
        //胶囊常驻开关：不吃无障碍检查，不然 quiet 在服务挂掉时收不回来
        if (act == "start" || act == "begin") {
            val given = a.optString("text").trim()
            val t = given.ifEmpty { b.ai.App.self.getString(b.ai.R.string.ag_capsule_fmt, Tools.label(Tools.SCREEN)) }
            b.ai.device.sys.host.Capsule.begin(t)
            return ToolOutcome(
                true,
                "胶囊已常驻：「$t」。接着一步步操作，全部做完再调 screen action=quiet 收起。",
                Route.SANDBOX
            )
        }
        if (act == "quiet" || act == "stop" || act == "end") {
            b.ai.device.sys.host.Capsule.end()
            return ToolOutcome(true, "胶囊已收起。", Route.SANDBOX)
        }
        if (!b.ai.device.sys.a11y.A11y.ready()) {
            b.ai.device.sys.a11y.A11y.askPerm()
            return ToolOutcome(
                false,
                "无障碍服务没开，屏幕操作跑不通。已经给用户弹了引导弹窗，等用户开启后再试。" +
                    "现在也可以改用 exec_shell（uiautomator dump / input tap / screencap），但那需要 Shizuku 或 Root。",
                Route.SANDBOX
            )
        }
        val t = when (act) {
            "see", "shot2" -> b.ai.device.sys.a11y.A11y.dump(400, a.optBoolean("all", false)) + "\n截图: " + b.ai.device.sys.a11y.A11y.shot(shotFile())
            "tree", "dump" -> b.ai.device.sys.a11y.A11y.dump(400, a.optBoolean("all", false))
            "shot", "screenshot" -> "截图: " + b.ai.device.sys.a11y.A11y.shot(shotFile())
            "tap", "click" -> b.ai.device.sys.a11y.A11y.tap(a.optInt("x", -1), a.optInt("y", -1))
            "long", "longpress" -> b.ai.device.sys.a11y.A11y.longPress(
                a.optInt("x", -1), a.optInt("y", -1), a.optInt("dur", 800).toLong()
            )
            "swipe" -> b.ai.device.sys.a11y.A11y.swipe(
                a.optInt("x", -1), a.optInt("y", -1), a.optInt("x2", -1), a.optInt("y2", -1),
                a.optInt("dur", 300).toLong()
            )
            "text", "input" -> b.ai.device.sys.a11y.A11y.input(a.optString("text"))
            "key" -> b.ai.device.sys.a11y.A11y.global(a.optString("key", "back"))
            "launch", "open" -> launchApp(a.optString("app"))
            "apps", "list" -> apps(a.optString("app"))
            else -> "不支持的 action：$act（可用 see / tree / shot / tap / long / swipe / text / key / launch / apps / start / quiet）"
        }
        return ToolOutcome(!b.ai.device.sys.a11y.A11y.failed(t), t, Route.SANDBOX)
    } //屏幕操作分发

    /** 截图落盘到私有目录 img/shots，路径回给模型；勾了图片输入的模型可以据此看图 */

    fun shotFile(): java.io.File =
        java.io.File(Layout.dir(b.ai.App.self, Layout.SHOT), "shot-" + System.currentTimeMillis() + ".jpg")

    /** 按包名或名字拉起应用 */

    fun launchApp(key: String): String {
        val c = b.ai.App.self
        val k = key.trim()
        if (k.isEmpty()) return "缺少 app 参数：给包名（如 com.tencent.mm），不确定就先 action=apps"
        val pm = c.packageManager
        var i = if (k.contains('.')) runCatching { pm.getLaunchIntentForPackage(k) }.getOrNull() else null
        if (i == null) {
            val hit = runCatching {
                pm.getInstalledApplications(0).firstOrNull { app ->
                    val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
                    label.contains(k, true) || app.packageName.contains(k, true)
                }
            }.getOrNull()
            i = hit?.let { runCatching { pm.getLaunchIntentForPackage(it.packageName) }.getOrNull() }
        }
        val intent = i ?: return "没找到这个应用：$k（用 action=apps 看能启动哪些）"
        val err = runCatching {
            c.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.exceptionOrNull()
        if (err != null) return "启动失败：${err.message}"
        return "已启动 " + (intent.component?.packageName ?: k)
    } //启动应用

    /** 列出可启动的应用：名字 → 包名 */

    fun apps(filter: String): String {
        val pm = b.ai.App.self.packageManager
        val kw = filter.trim()
        val rows = runCatching {
            pm.getInstalledApplications(0).mapNotNull { app ->
                runCatching { pm.getLaunchIntentForPackage(app.packageName) }.getOrNull()
                    ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName)
                if (kw.isNotBlank() && !label.contains(kw, true) && !app.packageName.contains(kw, true)) {
                    return@mapNotNull null
                }
                "$label → ${app.packageName}"
            }.sorted()
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return "没有匹配的可启动应用"
        val head = rows.take(120).joinToString("\n")
        return if (rows.size > 120) head + "\n（共 ${rows.size} 个，只列前 120）" else head
    } //列出可启动应用

    fun clip(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "get").lowercase()
        val r = if (act == "set") b.ai.device.sys.probe.Device.clipSet(a.optString("text"))
        else b.ai.device.sys.probe.Device.clipGet()
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //剪贴板

    /** 打开链接 / 分享：url 走浏览器，share 走系统分享面板 */

    fun openLink(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "url").lowercase()
        val r = if (act == "share") b.ai.device.sys.probe.Device.shareText(a.optString("text"))
        else b.ai.device.sys.probe.Device.openUrl(a.optString("url"))
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //打开或分享

    /** 通知：list 读今日通知（可按应用筛），send 发一条本地通知给用户 */

    fun noti(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "list").lowercase()
        if (act == "send") {
            val r = b.ai.device.sys.probe.Device.sendNoti(a.optString("title"), a.optString("text"))
            return ToolOutcome(r.ok, r.text, Route.SANDBOX)
        }
        return ToolOutcome(true, b.ai.device.sys.probe.Device.notiList(a.optInt("limit", 30), a.optString("app")), Route.SANDBOX)
    } //通知

    /** 直发 Tools.HTTP：不依赖 curl，headers 每行一个「名: 值」，返回状态码与正文前 8000 字 */
    /** net_ping：native TCP 建连计时，普通权限可跑 */

    fun termView(a: JSONObject): ToolOutcome {
        val r = Term.view(a.optString("session"), a.optInt("tail", 80))
        return ToolOutcome(r.ok, r.out, ShellTools.routeNow())
    } //查看终端内容

    /**
     * 向终端会话 stdin 写文本：分页器发 q、交互确认 y/n、REPL 输入等。
     * 会改变会话状态，计划模式下被 PLAN_OFF 拦掉。
     */

    fun termSend(a: JSONObject): ToolOutcome {
        val text = a.optString("text")
        if (text.isEmpty()) return ToolOutcome(false, "text 为空：要写什么就传什么，例如 q、y、help", Route.SANDBOX)
        val r = Term.send(a.optString("session"), text, a.optBoolean("enter", false))
        return ToolOutcome(r.ok, r.out, ShellTools.routeNow())
    } //向终端输入
}
