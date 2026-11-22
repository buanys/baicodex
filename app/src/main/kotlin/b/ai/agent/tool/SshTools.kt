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

/** SSH 远端操作 */
internal object SshTools {
    fun sshTool(name: String, args: JSONObject): ToolOutcome {
        fun need(v: String, what: String): ToolOutcome? =
            if (v.isBlank()) ToolOutcome(false, "$name 需要 $what 参数", Route.SANDBOX) else null
        fun out(r: b.ai.device.shell.local.ShellResult) =
            ToolOutcome(r.ok, r.out.ifBlank { if (r.ok) "完成" else "失败（退出码 ${r.code}）" }, Route.SANDBOX)
        val path = args.optString("path").trim()
        val local = args.optString("local").trim()
        val cmd = args.optString("command")
        val timeout = args.optInt("timeout", 180).coerceIn(10, 1800) * 1000L
        return when (name) {
            Tools.SSH_CONNECT -> {
                val host = args.optString("host").trim()
                val user = args.optString("user").trim()
                val pass = args.optString("pass")
                when {
                    host.isBlank() -> ToolOutcome(false, "ssh_connect 需要 host", Route.SANDBOX)
                    user.isBlank() -> ToolOutcome(false, "ssh_connect 需要 user", Route.SANDBOX)
                    pass.isBlank() -> ToolOutcome(false, "ssh_connect 需要 pass", Route.SANDBOX)
                    else -> {
                        val r = b.ai.device.shell.remote.Ssh.save(host, args.optInt("port", 22), user, pass)
                        out(r.copy(out = if (r.ok) "连接已保存并验证通过：$user@$host\n" + r.out else r.out))
                    }
                }
            }
            Tools.SSH_LS -> need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.ls(path, args.optInt("depth", 1)))
            Tools.SSH_READ -> need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.read(path, args.optInt("max_lines", 0)))
            Tools.SSH_WRITE -> need(path, "path") ?: run {
                if (!args.has("content")) {
                    ToolOutcome(false, "ssh_write 需要 content（写空文件就传空字符串）", Route.SANDBOX)
                } else {
                    out(b.ai.device.shell.remote.Ssh.write(path, args.optString("content")))
                }
            }
            Tools.SSH_SUDO -> need(cmd, "command") ?: out(b.ai.device.shell.remote.Ssh.sudo(cmd, timeout))
            Tools.SSH_EXEC -> need(cmd, "command") ?: out(b.ai.device.shell.remote.Ssh.exec(cmd, timeout))
            Tools.SSH_GET -> need(path, "path") ?: need(local, "local") ?: out(b.ai.device.shell.remote.Ssh.pull(path, local))
            Tools.SSH_PUT -> need(local, "local") ?: need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.push(local, path))
            else -> ToolOutcome(false, "未知 SSH 工具：$name", Route.SANDBOX)
        }
    } //ssh 分发
}
