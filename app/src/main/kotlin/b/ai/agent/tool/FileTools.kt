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

/** FileOps 结果 → 工具结果：Tools 与 FileTools 都用，放在顶层 */
    fun FileOps.Out.toOutcome() = ToolOutcome(ok, text, route)

    /** 压缩 / 解压：action 分流，未给输出路径就按源路径推导 */

/** 文件类工具：压缩与解压 */
internal object FileTools {
    fun zipOp(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "zip").lowercase()
        if (act == "unzip") {
            val z = a.optString("path").trim()
            if (z.isEmpty()) return ToolOutcome(false, "unzip 需要 path（zip 文件路径）", Route.SANDBOX)
            val to = a.optString("to").trim()
                .ifEmpty { z.removeSuffix(".zip").removeSuffix(".ZIP") + "_unzip" }
            return FileOps.unzip(z, to).toOutcome()
        }
        val paths = a.optString("paths").trim()
        if (paths.isEmpty()) return ToolOutcome(false, "zip 需要 paths（要打包的路径，逗号分隔）", Route.SANDBOX)
        val out = a.optString("out").trim().ifEmpty {
            paths.split(',').first().trim().trimEnd('/') + ".zip"
        }
        return FileOps.zip(paths, out).toOutcome()
    } //压缩解压

    /** 剪贴板：get 读、set 写，成败由 Device 那边给 */
}
