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

/** 终端与 root shell：命令执行、走哪条权限路、输出裁剪 */
internal object ShellTools {

    /** 计划模式下也算「写」的命令：改文件、装包、跑构建 */
    private val PLAN_WRITE = listOf(
        "rm ", "mv ", "cp ", "dd ", "chmod ", "chown ", "mkdir ", "touch ",
        "tee ", "sed -i", "truncate ", "ln ", "install ", "patch ",
        "npm i", "npm install", "pnpm i", "pnpm add", "yarn add", "pip install",
        "apt ", "pkg install", "cargo build", "gradle ", "./gradlew"
    )

    /** 重定向写文件：用 > 或 >> 落盘也算写 */
    private val REDIRECT = Regex("""\d?>>?\s*([^\s;|&]+)""")
    fun execShell(command: String, path: String = "", session: String = "", keys: String = ""): ToolOutcome {
        //功能键：只往会话里打信号，不执行新命令
        if (keys.isNotBlank()) {
            val r = Term.keys(keys, session)
            return ToolOutcome(r.ok, r.out, routeNow())
        }
        if (command.isBlank()) return ToolOutcome(false, "命令为空", Route.SANDBOX)
        //计划模式一律不许落盘：带写操作的命令直接拒，只放过只读命令
        if (Cfg.agentMode == AgentMode.PLAN) {
            val hit = PLAN_WRITE.find { command.contains(it) }
            //重定向只拦真正落盘的目标，/dev/null 与 2>&1 放过
            val sink = REDIRECT.findAll(command)
                .map { it.groupValues[1] }
                .firstOrNull { it != "/dev/null" && it != "&1" && it != "&2" }
            val bad = hit ?: sink
            if (bad != null) {
                return ToolOutcome(
                    false,
                    "计划模式下不允许改动文件（命中「${bad.trim()}」），本次命令没有执行；先出计划，用户确认后再动手。",
                    Route.SANDBOX
                )
            }
        }
        //绑了工作区就先把 cwd 切进去：模型只写相对路径也能跑对
        //显式要会话或指定目录 → 走常驻会话：cd / export 跨调用保持，还能被 keys 打断
        if (session.isNotBlank() || path.isNotBlank()) {
            val name = session.ifBlank { "main" }
            val r = Term.exec(command, path, name, 120_000L)
            val dir = Term.cwdOf(name).ifBlank { path }
            val head = "[" + Cfg.workMode.label + " · 会话 " + name + "] exit=" + r.code +
                " · cwd=" + dir.ifBlank { "未绑工作区" } + "\n\$ " + echoCmd(command) + "\n"
            return ToolOutcome(r.ok, head + r.out, routeNow())
        }
        val cmd = Workspace.wrap(command)
        return when (Cfg.workMode) {
            WorkMode.ROOT -> {
                val r = Root.run(cmd, timeoutMs = 120_000L)
                ToolOutcome(r.ok, shellHead("root", r.code, command) + r.out, Route.ROOT)
            } //root 身份：一次 su -c
            WorkMode.SHIZUKU -> {
                val r = Shiz.run(cmd, timeoutMs = 120_000L)
                ToolOutcome(r.ok, shellHead("Shizuku", r.code, command) + r.out, Route.SHIZUKU)
            } //shell 身份：Shizuku 用户服务
            WorkMode.NORMAL -> {
                val r = Shell.run(cmd, timeoutMs = 120_000L)
                //exec_shell 是应用身份的 sh，命令里的路径原样交给内核：零宽只作用于文件四件套，
                //命令直连 Android/data 或 obb 时失败必须说清原因，别让模型以为是零宽没开
                val lack = !r.ok && !Router.allFiles() &&
                    (command.contains("/Android/data") || command.contains("/Android/obb"))
                val extra = if (lack) {
                    "\n（exec_shell 走原路径，零宽变换只对 read_file / write_file / make_dir / delete_file 生效；" +
                        "这个目录请改用那几个工具，或在设置 → 权限 里打开「所有文件访问」）"
                } else ""
                ToolOutcome(r.ok, shellHead("沙箱", r.code, command) + r.out + extra, Route.SANDBOX)
            } //应用身份：sh
        }
    } //exec_shell：不自动降级，权限不足直接把错误回给模型

    fun shellHead(tag: String, code: Int, command: String): String {
        val dir = runCatching { Workspace.path() }.getOrDefault("")
        val where = if (dir.isBlank()) "未绑工作区" else dir
        return "[$tag] exit=$code · cwd=$where\n\$ " + echoCmd(command) + "\n"
    } //通道与命令回显

    fun echoCmd(command: String): String {
        val one = command.replace("\r", " ").replace("\n", " ⏎ ")
        return if (one.length > 300) one.take(300) + "…" else one
    } //命令回显

    fun routeNow(): Route = when (Cfg.workMode) {
        WorkMode.ROOT -> Route.ROOT
        WorkMode.SHIZUKU -> Route.SHIZUKU
        else -> Route.SANDBOX
    } //当前通道

    fun execLinux(command: String): ToolOutcome {
        if (command.isBlank()) return ToolOutcome(false, "命令为空", Route.SANDBOX)
        val r = Linux.run(Workspace.wrap(command))
        return ToolOutcome(r.ok, shellHead("Ubuntu", r.code, command) + r.out, Route.SANDBOX)
    } //linux_shell：在自带 Ubuntu 里跑，/sdcard 是同一条路径
}
