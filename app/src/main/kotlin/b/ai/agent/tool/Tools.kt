/*
 * Project:    Baicodex
 * Author:     by 沐白/Baix
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 * Date:       2026-11-20 21:47:04
 * Repository: https://github.com/mubai0122/Baicodex
 * Website:    https://agent.ai.baiccb.cn/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package b.ai.agent.tool
import b.ai.core.data.db.Repo
import b.ai.core.data.io.Layout
import b.ai.device.shell.root.Root
import b.ai.net.api.llm.text
import b.ai.ui.widget.tap

import b.ai.net.api.llm.LlmClient
import b.ai.net.api.a2a.A2aNode
import b.ai.net.api.a2a.A2aNodes
import b.ai.net.api.a2a.A2aSrv
import b.ai.net.lan.Lan
import b.ai.net.lan.LanCfg
import b.ai.net.lan.Team
import b.ai.core.misc.apk.Apk
import b.ai.core.cfg.app.AgentMode
import b.ai.core.cfg.app.Cfg
import b.ai.native.net.Net
import b.ai.native.script.Dsl
import b.ai.native.script.Js
import b.ai.net.mcp.Mcp
import b.ai.core.media.music.MusicCfg
import b.ai.net.mi.MiAuth
import b.ai.net.mi.MiHome
import b.ai.net.mi.MiStore
import b.ai.net.music.NeteaseLogin
import b.ai.agent.memory.Memory
import b.ai.core.model.chat.Route
import b.ai.core.task.alarm.Schedules
import b.ai.net.search.Search
import b.ai.core.cfg.secret.Secret
import b.ai.core.plugin.skill.Providers
import b.ai.core.plugin.skill.SkillFn
import b.ai.core.plugin.skill.SkillPacks
import b.ai.App
import b.ai.agent.flow.Flows
import b.ai.core.model.chat.FnModel
import b.ai.core.model.chat.WorkMode
import b.ai.device.sys.alarm.Alarms
import b.ai.device.fs.FileOps
import b.ai.device.shell.local.Linux
import b.ai.device.sys.probe.Router
import b.ai.device.shell.local.Shell
import b.ai.device.shell.session.Term
import b.ai.device.shell.root.Shiz
import b.ai.device.fs.Workspace
import b.ai.net.api.a2a.A2A as A2aClient
import b.ai.net.api.a2a.A2aRes
import b.ai.net.search.Bing
import b.ai.net.search.GoogleCse
import b.ai.net.search.Rail
import b.ai.net.search.Tavily
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ToolOutcome(val ok: Boolean, val text: String, val route: Route)

/** 工具定义 + 执行分发 */
object Tools {
    const val READ = "read_file"
    const val WRITE = "write_file"
    /** 局部改文件：old 按行定位，缩进与行尾换行自动对齐，命中唯一才替换 */
    const val EDIT = "edit_file"
    const val MAKE_DIR = "make_dir"
    const val DELETE = "delete_file"
    const val SHELL = "exec_shell"
    /** 终端查看 / 输入：命令卡住或要答交互提示时，先看画面再发键，不用重跑整条命令 */
    const val TERM_VIEW = "term_view"
    const val TERM_SEND = "term_send"
    const val LINUX = "linux_shell"
    const val WEB_SEARCH = "web_search"
    const val RAIL = "rail_query"
    const val MEMORY = "memory_save"
    /** 记忆检索：记忆条目多时模型拿不全，靠它按关键词回查 */
    const val MEMORY_READ = "memory_search"
    const val MEMORY_DEL = "memory_delete" //删永久记忆：按 id 删一条；关键词命中多条只列出来，避免一句话删掉一堆
    /** SSH：拆成一整组专用工具（连接 / 列目录 / 读 / 写 / 提权 / 执行 / 下载 / 上传），模型不用再拿 exec 拼 ssh 命令 */
    const val SSH_CONNECT = "ssh_connect"
    const val SSH_LS = "ssh_ls"
    const val SSH_READ = "ssh_read"
    const val SSH_WRITE = "ssh_write"
    const val SSH_SUDO = "ssh_sudo"
    const val SSH_EXEC = "ssh_exec"
    const val SSH_GET = "ssh_get"
    const val SSH_PUT = "ssh_put"
    /** SSH 全家桶：只在装了内置 Ubuntu 后下发 */
    private val SSH_ALL = listOf(SSH_CONNECT, SSH_LS, SSH_READ, SSH_WRITE, SSH_SUDO, SSH_EXEC, SSH_GET, SSH_PUT)
    /** JS：QuickJS 里跑脚本，脚本能用 bai.call 调别的工具，也能 registerTool 注册新工具 */
    const val JS = "js"
    const val ASK = "ask_user"
    /** 取某个技能的完整用法（README），技能函数由技能包动态注册 */
    const val SKILL_READ = "skill_read"

    /** 1.6.0 补齐的文件与系统工具：列目录 / 取信息 / 移动复制 / 压缩解压 / 内容搜索 */
    const val LIST = "list_dir"
    const val INFO = "file_info"
    const val MOVE = "move_path"
    const val ZIP = "zip_op"
    const val GREP = "grep_files"

    /** 重构：建工作区索引（.docs/function.txt 等）、查谁引用了谁、查自己的 imports、生成分步计划 */
    const val REF_SCAN = "refactor_scan"
    const val REF_REFS = "refactor_refs"
    const val REF_DEPS = "refactor_deps"
    const val REF_PLAN = "refactor_plan"

    /** 剪贴板 / 打开链接 / 分享 / 本地通知与今日通知 / 直发 HTTP */
    const val CLIP = "clipboard"
    const val OPEN = "open_link"
    const val NOTI = "notification"
    const val HTTP = "http_fetch"
    /** 网络三件套：TCP 测延迟 / 通用 curl / 抓网页正文，走 cpp/net 的原生实现 */
    const val PING = "net_ping"
    const val CURL = "net_curl"
    const val PAGE = "net_page"

    /** APK 逆向：签名证书 / 二进制清单 / 权限与组件 / dex 字符串 / 包内文件 */
    const val APKTOOL = "apk"

    /** 浏览器：内置 WebView 读正文、点链接、填表单，不弹外置浏览器 */
    const val BROWSE = "browser"

    /** 屏幕操作：无障碍侧读控件树 / 截图 / 点击滑动长按输入，走 A11y */
    const val SCREEN = "screen"
    /** 分智能体：一次派多条只读子任务并行跑，主智能体只收结论 */
    const val SUB = "subagent_run"
    /** 工作流：多步顺序跑（每步一个分智能体），能存盘、能定时、失败可重试或跳过 */
    const val FLOW_RUN = "flow_run"
    /** 自动建流：给个目标就让模型拆步骤，并补一条自测步骤，存成可跑的工作流 */
    const val FLOW_AUTO = "flow_auto"
    /** 查聊天记录：按关键词翻历史消息（会话内或全库），避免「上次说过什么」只能靠猜 */
    const val CHAT_SEARCH = "chat_search"
    const val FLOW_SAVE = "flow_save"
    const val FLOW_LIST = "flow_list"
    /** A2A：和别的 A2A 智能体对话（A2A 1.0），本机也能当服务端 */
    const val A2A = "a2a"
    /** 局域网团队协作：发现、配对、把任务派给对端的大模型 */
    const val LAN = "lan"
    /** 米家：台账、局域网扫描、属性读写与动作；控制链路是「局域网优先、云端兜底」 */
    const val MI_DEVICES = "mijia_devices"
    const val MI_SCAN = "mijia_scan"
    const val MI_READ = "mijia_read"
    const val MI_WRITE = "mijia_write"
    const val MI_ACTION = "mijia_action"
    /** 音乐：平台开关与优先级、登录态（cookie 进加密库）、缓存清理 */
    const val MUSIC_PLATS = "music_platforms"
    const val MUSIC_LOGIN = "music_login"
    const val MUSIC_CACHE = "music_cache_clear"
    /** MCP 能力的工具名前缀：mcp_服务_能力 */
    const val MCP = "mcp_"

    /**工具清单：搜索是可选的技能，只有设置里选了平台才下发；Linux工具只在装了环境后出现；MCP能力按缓存表下发 */
    val all: List<String>
        get() {
            val l = ArrayList<String>()
            l.addAll(listOf(READ, WRITE, EDIT, MAKE_DIR, DELETE, SHELL, TERM_VIEW, TERM_SEND, RAIL, MEMORY, MEMORY_READ, MEMORY_DEL, ASK, SCREEN))
            l.addAll(
                listOf(
                    LIST, INFO, MOVE, ZIP, GREP, CLIP, OPEN, NOTI, HTTP, PING, CURL, PAGE, APKTOOL,
                    BROWSE, SUB, JS,
                    FLOW_RUN, FLOW_SAVE, FLOW_LIST, FLOW_AUTO, CHAT_SEARCH, A2A, LAN,
                    MI_DEVICES, MI_SCAN, MI_READ, MI_WRITE, MI_ACTION,
                    MUSIC_PLATS, MUSIC_LOGIN, MUSIC_CACHE
                )
            )
            if (Linux.installed()) {
                l.add(LINUX)
                l.addAll(SSH_ALL)
            }
            if (Search.selected != null) l.add(WEB_SEARCH)
            l.add(SKILL_READ)
            SkillPacks.funcs.forEach { if (it.name !in l) l.add(it.name) }
            Js.tools.forEach { if (it.name !in l) l.add(it.name) }
            l.addAll(Mcp.tools.map { it.full })
            return l
        }

    /** 计划模式禁掉的工具：一切会落地改动或有长期副作用的能力 */
    private val PLAN_OFF = setOf(
        WRITE, EDIT, MAKE_DIR, DELETE, MEMORY, MEMORY_DEL, LINUX, SCREEN, MOVE, ZIP, CLIP, OPEN, NOTI, JS,
        TERM_SEND,
        FLOW_RUN, FLOW_SAVE, FLOW_AUTO, A2A, LAN,
        SSH_CONNECT, SSH_WRITE, SSH_SUDO, SSH_EXEC, SSH_PUT,
        MI_WRITE, MI_ACTION, MUSIC_LOGIN, MUSIC_CACHE
    )

    /** 极简模式只留这五个：文件四件套 + exec_shell */
    private val MIN_SET = setOf(READ, WRITE, MAKE_DIR, DELETE, SHELL)

    /** 计划模式下 exec_shell 里一律判死的写操作片段：只读命令才放过 */
    private val PLAN_WRITE = listOf(
        "rm ", "mv ", "cp ", "touch ", "mkdir ", "rmdir ", "tee ", "chmod ", "chown ", "ln ",
        "sed -i", "truncate ", "dd ", "git commit", "git checkout", "git reset", "git add"
    )

    /** 重定向目标：> /dev/null 与 2>&1 这类只是丢弃输出，不算写文件 */
    private val REDIRECT = Regex("""\d?>>?\s*([^\s;|&]+)""")

    /** 当前模式下这个工具能不能用：下发定义和执行前都过一遍 */
    fun allowed(name: String): Boolean = when (Cfg.agentMode) {
        AgentMode.PLAN -> name !in PLAN_OFF
        AgentMode.MINIMAL -> name in MIN_SET
        else -> true
    }

    fun label(name: String): String = when (name) {
        READ -> "读取文件"
        WRITE -> "写入文件"
        EDIT -> "修改文件"
        MAKE_DIR -> "创建目录"
        DELETE -> "删除文件"
        SHELL -> "执行命令"
        TERM_VIEW -> "查看终端内容"
        TERM_SEND -> "向终端输入"
        LINUX -> "Linux 命令"
        WEB_SEARCH -> "联网搜索"
        RAIL -> "火车票查询"
        MEMORY -> "记住这件事"
        MEMORY_READ -> "搜记忆"
        MEMORY_DEL -> "删记忆"
        ASK -> "向用户提问"
        SKILL_READ -> "查技能用法"
        SCREEN -> "操作屏幕"
        LIST -> "列目录"
        INFO -> "看文件信息"
        MOVE -> "移动/复制"
        ZIP -> "压缩/解压"
        GREP -> "搜文件内容"
        CLIP -> "剪贴板"
        OPEN -> "打开/分享"
        NOTI -> "通知"
        HTTP -> "发 HTTP 请求"
        PING -> "测网络延迟"
        CURL -> "发 curl 请求"
        PAGE -> "抓网页正文"
        APKTOOL -> "看安装包"
        BROWSE -> "浏览网页"
        SUB -> "分智能体"
        FLOW_RUN -> "跑工作流"
        FLOW_SAVE -> "保存工作流"
        FLOW_AUTO -> "自动建工作流"
        CHAT_SEARCH -> "查聊天记录"
        FLOW_LIST -> "列工作流"
        A2A -> "A2A 对话"
        SSH_CONNECT -> "SSH 连服务器"
        SSH_LS -> "SSH 列目录"
        SSH_READ -> "SSH 读文件"
        SSH_WRITE -> "SSH 写文件"
        SSH_SUDO -> "SSH 提权执行"
        SSH_EXEC -> "SSH 执行命令"
        SSH_GET -> "SSH 下载"
        SSH_PUT -> "SSH 上传"
        JS -> "JS 脚本"
        else -> SkillPacks.find(name)?.let { "${it.skill}·${it.name}" }
            ?: if (Js.has(name)) "JS·$name" else mcpLabel(name)
    }

    /** MCP 能力的显示名：MCP·服务名·能力名，找不到缓存就原样返回 */
    private fun mcpLabel(name: String): String {
        if (!name.startsWith(MCP)) return name
        val t = Mcp.findTool(name) ?: return name
        return "MCP·" + t.server + "·" + t.name
    } //MCP 显示名

    /** 高危标记：删除与任意命令执行；MCP 里凡是能跑命令的也按高危算 */
    fun isHighRisk(name: String): Boolean {
        if (name == DELETE || name == SHELL || name == LINUX || name == JS) return true
        //SSH 里只有会改远端或跑任意命令的算高危，ls / read / get 是只读，不必每次弹确认
        if (name == SSH_SUDO || name == SSH_EXEC || name == SSH_WRITE || name == SSH_PUT) return true
        //米家写属性 / 调动作会真动设备，清缓存会删文件，都按高危算
        if (name == MI_WRITE || name == MI_ACTION || name == MUSIC_CACHE) return true
        if (Js.has(name)) return true
        if (!name.startsWith(MCP)) return false
        val t = Mcp.findTool(name) ?: return true
        val n = t.name.lowercase()
        return HIGH_WORDS.any { n.contains(it) }
    } //高危判定

    private val HIGH_WORDS = listOf("run", "shell", "exec", "cmd", "command", "terminal", "install", "kill")

    internal fun ToolOutcome.governed(): ToolOutcome {
        if (!Dsl.ready) return this
        //0 = max：不限，原样放行；否则按 token 预算折算字符（1 token ≈ 1.8 字符）
        val tok = Cfg.toolBudgetTokens
        if (tok <= 0) return this
        val cap = if (tok >= Int.MAX_VALUE / 18) Int.MAX_VALUE else tok * 18 / 10
        val r = Dsl.nativeGovern(text, cap)
        if (!r.startsWith("OK\n")) return this
        return ToolOutcome(ok, r.substring(3), route)
    } //工具结果治理：超预算直接截止，只保留头部



    /** 组一份参数表：properties 手给，required 显式列（SSH 那组每个工具的必填项都不一样） */

    private fun params(vararg props: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        props.forEach { (k, v) ->
            properties.put(k, v)
            required.put(k)
        }
        return JSONObject().put("type", "object").put("properties", properties).put("required", required)
    }


    fun definitions(): JSONArray = toolSpecs()

    fun execute(name: String, args: JSONObject): ToolOutcome {
        //参数先过一遍：漏字段不再被 optString 的默认值兜成空串去执行（那会变成「成功但做错事」）
        brief(name, args)?.let { return ToolOutcome(false, it, Route.SANDBOX) }
        return dispatch(name, args)
    }

    /**
     * 必填参数校验。
     *
     * 只覆盖会直接改文件、执行命令的这一族（读/写/改/建目录/删/终端/root shell），
     * 其余工具按同样方式后续补。返回 null 表示参数齐了，返回字符串是给模型看的缺失说明。
     */
    private fun brief(name: String, args: JSONObject): String? {
        fun filled(key: String) = args.optString(key).isNotBlank()
        val lack = when (name) {
            READ -> if (filled("path")) null else "path"
            WRITE -> when {
                !filled("path") -> "path"
                //内容允许是空串（建空文件），只要求这个键在
                !args.has("content") -> "content"
                else -> null
            }
            EDIT -> {
                val mode = args.optString("mode").trim().lowercase()
                when {
                    !filled("path") -> "path"
                    mode == "insert" || mode == "delete" ->
                        if (args.optInt("line", 0) <= 0) "line（mode=$mode 需要从 1 开始的行号）"
                        else if (mode == "insert" && !filled("text")) "text（mode=insert 需要插入的内容）"
                        else null
                    !filled("old") -> "old"
                    !filled("new") -> "new"
                    else -> null
                }
            }
            MAKE_DIR, DELETE -> if (filled("path")) null else "path"
            SHELL, LINUX -> if (filled("command")) null else "command"
            else -> null
        } ?: return null
        return "缺少必填参数：$lack。$name 这次没有执行——补上参数再调用，别拿空参数重试。"
    }

    private fun dispatch(name: String, args: JSONObject): ToolOutcome = when (name) {
        READ -> FileOps.read(
            args.optString("path"), args.optInt("page", 1), args.optInt("lines", 400)
        ).toOutcome().governed()
        WRITE -> FileOps.write(args.optString("path"), args.optString("content")).toOutcome().governed()
        EDIT -> {
            //mode=insert / delete 走按行号那套，其余仍是 old→new 的整段替换
            val mode = args.optString("mode").trim().lowercase()
            if (mode == "insert" || mode == "delete") {
                FileOps.editAt(
                    args.optString("path"), mode, args.optInt("line", 0), args.optInt("count", 1),
                    args.optString("pos", "after").trim().lowercase() != "before", args.optString("text")
                ).toOutcome().governed()
            } else {
                FileOps.edit(args.optString("path"), args.optString("old"), args.optString("new")).toOutcome().governed()
            }
        }
        MAKE_DIR -> FileOps.mkdir(args.optString("path")).toOutcome().governed()
        DELETE -> FileOps.delete(args.optString("path")).toOutcome().governed()
        SHELL -> execShell(
            args.optString("command"),
            args.optString("path"),
            args.optString("session"),
            args.optString("keys")
        ).governed()
        //终端排障：先看画面（term_view），再决定发什么键（term_send / exec_shell 的 keys）
        TERM_VIEW -> termView(args).governed()
        TERM_SEND -> termSend(args).governed()
        LINUX -> execLinux(args.optString("command")).governed()
        WEB_SEARCH -> search(args).governed()
        RAIL -> rail(args).governed()
        MEMORY -> memorySave(args).governed()
        MEMORY_READ -> memorySearch(args).governed()
        MEMORY_DEL -> memoryDelete(args).governed()
        //ask_user 由 Agent 直接拦下弹面板，走到这里说明调用链不对
        ASK -> ToolOutcome(false, "ask_user 需要界面交互，无法在后台执行", Route.SANDBOX)
        SCREEN -> screen(args).governed()
        LIST -> FileOps.list(
            args.optString("path"), args.optBoolean("recurse", false), args.optInt("limit", 200),
            args.optString("match"), args.optBoolean("summarize", false)
        ).toOutcome().governed()
        INFO -> FileOps.info(args.optString("path")).toOutcome().governed()
        MOVE -> FileOps.move(
            args.optString("from"), args.optString("to"), args.optBoolean("copy", false)
        ).toOutcome().governed()
        ZIP -> zipOp(args).governed()
        GREP -> FileOps.grep(
            args.optString("path"), args.optString("pattern"), args.optString("ext"),
            args.optInt("max", 60), args.optBoolean("ignore_case", true),
            args.optBoolean("recurse", true), args.optBoolean("summarize", false)
        ).toOutcome().governed()
        CLIP -> clip(args).governed()
        OPEN -> openLink(args).governed()
        NOTI -> noti(args).governed()
        HTTP -> httpFetch(args).governed()
        PING -> netPing(args).governed()
        CURL -> netCurl(args).governed()
        PAGE -> netPage(args).governed()
        APKTOOL -> apk(args).governed()
        BROWSE -> browse(args).governed()
        SKILL_READ -> skillRead(args.optString("name")).governed()
        //subagent_run 由主循环并行调度（要拿 Agent.status 报进度），走到这里说明调用链不对
        SUB -> ToolOutcome(false, "subagent_run 需要主循环调度，不能在后台单独执行", Route.SANDBOX)
        //flow_run 同理：要报进度、要能被停止键打断
        FLOW_RUN -> ToolOutcome(false, "flow_run 需要主循环调度，不能在后台单独执行", Route.SANDBOX)
        FLOW_SAVE -> flowSave(args)
        FLOW_AUTO -> flowAuto(args)
        CHAT_SEARCH -> chatSearch(args)
        FLOW_LIST -> flowList()
        A2A -> a2a(args).governed()
        LAN -> lanTool(args).governed()
        MI_DEVICES -> miDevices(args).governed()
        MI_SCAN -> miScan(args).governed()
        MI_READ -> miRead(args).governed()
        MI_WRITE -> miWrite(args).governed()
        MI_ACTION -> miAction(args).governed()
        MUSIC_PLATS -> musicPlats(args).governed()
        MUSIC_LOGIN -> musicLogin(args).governed()
        MUSIC_CACHE -> musicCache(args).governed()
        SSH_CONNECT, SSH_LS, SSH_READ, SSH_WRITE, SSH_SUDO, SSH_EXEC, SSH_GET, SSH_PUT ->
            sshTool(name, args).governed()
        JS -> jsRun(args).governed()
        else -> SkillPacks.find(name)?.let { skillExec(it, args) }?.governed()
            ?: jsTool(name, args)
            ?: mcpCall(name, args).governed()
    } //执行单个工具
    /** JS 脚本：跑在 cpp/js/qjs.cpp 的 QuickJS 里，脚本里的 bai.call 回到这里调别的工具 */
    private fun jsRun(args: JSONObject): ToolOutcome {
        val code = args.optString("script")
        if (code.isBlank()) return ToolOutcome(false, "js 需要 script 参数", Route.SANDBOX)
        val ms = args.optInt("timeout", 15_000).coerceIn(1_000, 300_000)
        val r = Js.run(code, ms)
        return ToolOutcome(r.first, r.second, Route.SANDBOX)
    } //跑 JS 脚本
    /** JS 注册的工具：没有就返回 null，让后面的技能包 / MCP 继续找 */
    private fun jsTool(name: String, args: JSONObject): ToolOutcome? {
        if (!Js.has(name)) return null
        val r = Js.call(name, args)
        return ToolOutcome(r.first, r.second, Route.SANDBOX)
    } //跑 JS 注册的工具

    /** flow_save：存一条工作流，顺手登记定时（时间不合法就不绑定时，步骤照样存） */
    private fun flowSave(args: JSONObject): ToolOutcome {
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
    /** chat_search：按关键词翻历史消息，时间与会话标题都带上，模型据此引用 */
    private fun chatSearch(args: JSONObject): ToolOutcome {
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

    /** flow_auto：拆步骤 → 补自测 → 存盘，返回可照着验收的测试清单 */
    private fun flowAuto(args: JSONObject): ToolOutcome {
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

    /** 让模型把目标拆成步骤：返回空就由调用方退回按行切分 */
    private fun draftSteps(goal: String): List<b.ai.agent.flow.FlowStep> {
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

    /** flow_list：把工作流摆出来，id 和上次结果都带上 */
    private fun flowList(): ToolOutcome {
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
    /** a2a：本机保存的对端列表 + 5 种动作；对端找不到时允许直接写地址临时用 */
    private fun a2a(args: JSONObject): ToolOutcome {
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
    private fun a2aNode(key: String): A2aNode? {
        val k = key.trim()
        if (k.isEmpty()) return null
        A2aNodes.find(k)?.let { return it }
        if (!k.startsWith("http://") && !k.startsWith("https://")) return null
        return A2aNode(id = "", name = k, url = A2aNodes.norm(k))
    } //对端解析
    /** 局域网团队协作：发现、配对、派活；peer 认名字/设备号/IP，台账里没有就现场扫一遍 */
    private fun lanTool(a: JSONObject): ToolOutcome {
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

    private fun peerLine(p: LanCfg.Peer): String {
        val ago = p.lastSeen.takeIf { it > 0 }?.let {
            val m = (System.currentTimeMillis() - it) / 60_000
            if (m <= 0) "刚刚" else m.toString() + " 分钟前"
        } ?: "没探活过"
        val a2a = if (p.a2aPort > 0) "协作端口 " + p.a2aPort else "没开协作"
        return "- " + p.name.ifBlank { p.ip } + " " + p.ip + ":" + p.port + "，" + a2a + "，上次 " + ago + "（设备号 " + p.id + "）"
    } //一行一台设备，给模型看也给人看

    private fun findPeer(key: String): LanCfg.Peer? {
        Team.find(key)?.let { return it }
        val k = key.trim()
        if (k.isEmpty()) return null
        return Lan.scan(2000).firstOrNull {
            it.id.equals(k, true) || it.name.equals(k, true) || it.ip == k || it.name.contains(k, true)
        }
    } //先查台账，再现场扫
    /** 定时时间解析：认 yyyy-MM-dd HH:mm、yyyy/MM/dd HH:mm、HH:mm（按今天算）；过期时间每天的顺延，单次的返 0 */
    private fun parseAt(raw: String, daily: Boolean): Long {
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
    /** skill_read：返回技能用法，正文按需给，不进系统提示 */
    private fun skillRead(key: String): ToolOutcome {
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

    /** 技能函数：先过 DSL 引擎校验+插值，cmd 逐行执行，http 直接发请求 */
    private fun skillExec(f: SkillFn, args: JSONObject): ToolOutcome {
        if (f.bad.isNotEmpty()) return ToolOutcome(false, "技能包校验没过：${f.bad}", Route.SANDBOX)
        val vals = LinkedHashMap<String, String>()
        f.params.forEach { (k, _) ->
            val v = args.optString(k, "")
            if (v.isEmpty()) return ToolOutcome(false, "缺少参数 $k（${f.name}）", Route.SANDBOX)
            vals[k] = v
        }
        val (ok, out) = Dsl.render(f.body, f.kind, f.params, Secret.all(), vals)
        if (!ok) return ToolOutcome(false, "技能模板校验失败：$out", Route.SANDBOX)
        if (f.kind == "http") return httpRaw(out)
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ToolOutcome(false, "插值后没有可执行命令", Route.SANDBOX)
        val sb = StringBuilder()
        var route = Route.SANDBOX
        for (l in lines) {
            val o = execShell(l)
            route = o.route
            sb.append(o.text).append('\n')
            if (!o.ok) return ToolOutcome(false, sb.toString().trim(), route)
        }
        return ToolOutcome(true, sb.toString().trim(), route)
    } //执行技能函数

    /** http 技能：报文照约定拆成 方法 / URL / header / body，不额外引 HTTP 库 */
    private fun httpRaw(text: String): ToolOutcome {
        val nl = text.indexOf('\n')
        val first = (if (nl < 0) text else text.substring(0, nl)).trim()
        val seg = first.split(" ", limit = 2)
        if (seg.size < 2) return ToolOutcome(false, "http 报文首行不合法", Route.SANDBOX)
        val method = seg[0].trim().uppercase()
        val url = seg[1].trim()
        val headers = ArrayList<Pair<String, String>>()
        var i = if (nl < 0) text.length else nl + 1
        var bodyStart = text.length
        while (i < text.length) {
            val e = text.indexOf('\n', i)
            val row = text.substring(i, if (e < 0) text.length else e).trim()
            if (row.isEmpty()) {
                bodyStart = if (e < 0) text.length else e + 1
                break
            }
            val c = row.indexOf(':')
            if (c > 0) headers.add(row.substring(0, c).trim() to row.substring(c + 1).trim())
            i = if (e < 0) text.length else e + 1
        }
        val body = if (bodyStart < text.length) text.substring(bodyStart) else ""
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val txt = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }
                .getOrDefault("")
            conn.disconnect()
            ToolOutcome(code in 200..299, "HTTP $code\n" + txt.take(6000), Route.SANDBOX)
        }.getOrElse { ToolOutcome(false, "请求失败：" + (it.message ?: it.javaClass.simpleName), Route.SANDBOX) }
    } //发 http 技能请求

    /** 存一条永久记忆：重复的直接告诉模型别啰嗦 */
    private fun memorySave(args: JSONObject): ToolOutcome {
        val t = args.optString("text").trim()
        if (t.isEmpty()) return ToolOutcome(false, "memory_save 需要 text 参数", Route.SANDBOX)
        val ok = Memory.add(t, "")
        return if (ok) ToolOutcome(true, "已记住：$t", Route.SANDBOX)
        else ToolOutcome(true, "这条已经记过了：$t", Route.SANDBOX)
    } //存记忆

    /** 按关键词查永久记忆：记忆可能几百条，模型看不到全部，缺细节时自己回查 */
    private fun memorySearch(args: JSONObject): ToolOutcome {
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

    /** 删一条永久记忆：给 id 直接删；只给关键词时命中唯一一条才删，多条只列候选 */
    private fun memoryDelete(args: JSONObject): ToolOutcome {
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

    /** SSH 专用工具分发：每个动作一个独立工具名，远端路径转义全在 Ssh 里做 */
    private fun sshTool(name: String, args: JSONObject): ToolOutcome {
        fun need(v: String, what: String): ToolOutcome? =
            if (v.isBlank()) ToolOutcome(false, "$name 需要 $what 参数", Route.SANDBOX) else null
        fun out(r: b.ai.device.shell.local.ShellResult) =
            ToolOutcome(r.ok, r.out.ifBlank { if (r.ok) "完成" else "失败（退出码 ${r.code}）" }, Route.SANDBOX)
        val path = args.optString("path").trim()
        val local = args.optString("local").trim()
        val cmd = args.optString("command")
        val timeout = args.optInt("timeout", 180).coerceIn(10, 1800) * 1000L
        return when (name) {
            SSH_CONNECT -> {
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
            SSH_LS -> need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.ls(path, args.optInt("depth", 1)))
            SSH_READ -> need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.read(path, args.optInt("max_lines", 0)))
            SSH_WRITE -> need(path, "path") ?: run {
                if (!args.has("content")) {
                    ToolOutcome(false, "ssh_write 需要 content（写空文件就传空字符串）", Route.SANDBOX)
                } else {
                    out(b.ai.device.shell.remote.Ssh.write(path, args.optString("content")))
                }
            }
            SSH_SUDO -> need(cmd, "command") ?: out(b.ai.device.shell.remote.Ssh.sudo(cmd, timeout))
            SSH_EXEC -> need(cmd, "command") ?: out(b.ai.device.shell.remote.Ssh.exec(cmd, timeout))
            SSH_GET -> need(path, "path") ?: need(local, "local") ?: out(b.ai.device.shell.remote.Ssh.pull(path, local))
            SSH_PUT -> need(local, "local") ?: need(path, "path") ?: out(b.ai.device.shell.remote.Ssh.push(local, path))
            else -> ToolOutcome(false, "未知 SSH 工具：$name", Route.SANDBOX)
        }
    } //ssh 分发

    /** MCP 能力调用：能力不在缓存 / 服务被删或停用都如实报错，不能静默当没这工具 */
    private fun mcpCall(full: String, args: JSONObject): ToolOutcome {
        if (!full.startsWith(MCP)) return ToolOutcome(false, "未知工具: $full", Route.SANDBOX)
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

    /** 搜索技能：按用户选的平台分发；平台表以后往里加分支即可 */
    private fun search(args: JSONObject): ToolOutcome {
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

    /** 12306 查询：只读网络请求，走应用身份，不需要 root / Shizuku */
    private fun rail(a: JSONObject): ToolOutcome {
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

    /** 屏幕操作：全部动作走 A11y；没权限就置位让界面弹引导，而不是只回一句失败 */
    private fun screen(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "see").lowercase()
        //胶囊常驻开关：不吃无障碍检查，不然 quiet 在服务挂掉时收不回来
        if (act == "start" || act == "begin") {
            val given = a.optString("text").trim()
            val t = given.ifEmpty { b.ai.App.self.getString(b.ai.R.string.ag_capsule_fmt, label(SCREEN)) }
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
    private fun shotFile(): java.io.File =
        java.io.File(Layout.dir(b.ai.App.self, Layout.SHOT), "shot-" + System.currentTimeMillis() + ".jpg")

    /** 按包名或名字拉起应用 */
    private fun launchApp(key: String): String {
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
    private fun apps(filter: String): String {
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

    private fun FileOps.Out.toOutcome() = ToolOutcome(ok, text, route)

    /** 压缩 / 解压：action 分流，未给输出路径就按源路径推导 */
    private fun zipOp(a: JSONObject): ToolOutcome {
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
    private fun clip(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "get").lowercase()
        val r = if (act == "set") b.ai.device.sys.probe.Device.clipSet(a.optString("text"))
        else b.ai.device.sys.probe.Device.clipGet()
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //剪贴板

    /** 打开链接 / 分享：url 走浏览器，share 走系统分享面板 */
    private fun openLink(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "url").lowercase()
        val r = if (act == "share") b.ai.device.sys.probe.Device.shareText(a.optString("text"))
        else b.ai.device.sys.probe.Device.openUrl(a.optString("url"))
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //打开或分享

    /** 通知：list 读今日通知（可按应用筛），send 发一条本地通知给用户 */
    private fun noti(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "list").lowercase()
        if (act == "send") {
            val r = b.ai.device.sys.probe.Device.sendNoti(a.optString("title"), a.optString("text"))
            return ToolOutcome(r.ok, r.text, Route.SANDBOX)
        }
        return ToolOutcome(true, b.ai.device.sys.probe.Device.notiList(a.optInt("limit", 30), a.optString("app")), Route.SANDBOX)
    } //通知

    /** 直发 HTTP：不依赖 curl，headers 每行一个「名: 值」，返回状态码与正文前 8000 字 */
    /** net_ping：native TCP 建连计时，普通权限可跑 */
    private fun netPing(a: JSONObject): ToolOutcome {
        val host = a.optString("host").trim()
        if (host.isEmpty()) return ToolOutcome(false, "缺少 host", Route.SANDBOX)
        val port = a.optInt("port", 443).coerceIn(1, 65535)
        val count = a.optInt("count", 3).coerceIn(1, 10)
        val ms = a.optInt("timeout", 3000).coerceIn(500, 30_000)
        val r = Net.ping(host, port, count, ms)
        return ToolOutcome(!Net.failed(r), r, Route.SANDBOX)
    } //测延迟
    /** net_curl：任意方法 + 自定义头，回状态码、原始响应头与正文 */
    private fun netCurl(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val method = a.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val ms = a.optInt("timeout", 15_000).coerceIn(1_000, 120_000)
        val max = a.optInt("max", 20_000).coerceIn(200, 1_000_000)
        val r = Net.curl(method, url, a.optString("headers"), a.optString("body"), ms, max)
        val code = r.removePrefix("HTTP ").substringBefore('\n').trim().toIntOrNull()
        return ToolOutcome(code != null && code < 400, r, Route.SANDBOX)
    } //发 curl
    /** net_page：native 抓 HTML 后转纯文本 */
    private fun netPage(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val ms = a.optInt("timeout", 15_000).coerceIn(1_000, 120_000)
        val max = a.optInt("max", 20_000).coerceIn(200, 200_000)
        val r = Net.page(url, ms, max)
        return ToolOutcome(!Net.failed(r), r, Route.SANDBOX)
    } //抓正文

    private fun httpFetch(a: JSONObject): ToolOutcome {
        val url = a.optString("url").trim()
        if (url.isEmpty()) return ToolOutcome(false, "缺少 url", Route.SANDBOX)
        val method = a.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val body = a.optString("body")
        val heads = ArrayList<Pair<String, String>>()
        a.optString("headers").lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) heads.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return runCatching {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.instanceFollowRedirects = true
            heads.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            val ctype = conn.contentType.orEmpty()
            conn.disconnect()
            val head = "HTTP $code" + if (ctype.isNotEmpty()) "（$ctype）" else ""
            val cut = if (text.length > 8000) text.take(8000) + "\n…已截断（共 ${text.length} 字符）" else text
            ToolOutcome(code in 200..299, "$head\n$cut", Route.SANDBOX)
        }.getOrElse {
            ToolOutcome(false, "请求失败：" + (it.message ?: it.javaClass.simpleName), Route.SANDBOX)
        }
    } //直发 HTTP

    /**
     * 查看终端会话最近的回显：命令卡住 / 看不到进度时先调它。
     * 只读，不改状态，所以计划模式下也允许。
     */
    private fun termView(a: JSONObject): ToolOutcome {
        val r = Term.view(a.optString("session"), a.optInt("tail", 80))
        return ToolOutcome(r.ok, r.out, routeNow())
    } //查看终端内容

    /**
     * 向终端会话 stdin 写文本：分页器发 q、交互确认 y/n、REPL 输入等。
     * 会改变会话状态，计划模式下被 PLAN_OFF 拦掉。
     */
    private fun termSend(a: JSONObject): ToolOutcome {
        val text = a.optString("text")
        if (text.isEmpty()) return ToolOutcome(false, "text 为空：要写什么就传什么，例如 q、y、help", Route.SANDBOX)
        val r = Term.send(a.optString("session"), text, a.optBoolean("enter", false))
        return ToolOutcome(r.ok, r.out, routeNow())
    } //向终端输入

    private fun apk(a: JSONObject): ToolOutcome {
        val r = Apk.run(
            a.optString("action", "info"), a.optString("path"),
            a.optString("keyword"), a.optInt("limit", 60)
        )
        return ToolOutcome(r.ok, r.text, Route.SANDBOX)
    } //看安装包

    private fun browse(a: JSONObject): ToolOutcome {
        val t = b.ai.device.web.Browser.run(
            a.optString("action", "open"), a.optString("url"), a.optString("text"),
            a.optString("target"), a.optString("script"), a.optInt("limit", 4000)
        )
        return ToolOutcome(!b.ai.device.web.Browser.failed(t), t, Route.SANDBOX)
    } //浏览网页

    private fun execShell(command: String, path: String = "", session: String = "", keys: String = ""): ToolOutcome {
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

    /** 执行头：通道 + 退出码 + 实际 cwd + 命令原文，模型不用猜命令跑在哪、跑的是什么 */
    private fun shellHead(tag: String, code: Int, command: String): String {
        val dir = runCatching { Workspace.path() }.getOrDefault("")
        val where = if (dir.isBlank()) "未绑工作区" else dir
        return "[$tag] exit=$code · cwd=$where\n\$ " + echoCmd(command) + "\n"
    } //通道与命令回显

    /** 命令原文压成一行：多行命令也只看得到前后文，换个行当分隔 */
    private fun echoCmd(command: String): String {
        val one = command.replace("\r", " ").replace("\n", " ⏎ ")
        return if (one.length > 300) one.take(300) + "…" else one
    } //命令回显

    /** 当前执行通道对应的 Route：结果卡片上的标签跟它走 */
    private fun routeNow(): Route = when (Cfg.workMode) {
        WorkMode.ROOT -> Route.ROOT
        WorkMode.SHIZUKU -> Route.SHIZUKU
        else -> Route.SANDBOX
    } //当前通道

    private fun execLinux(command: String): ToolOutcome {
        if (command.isBlank()) return ToolOutcome(false, "命令为空", Route.SANDBOX)
        val r = Linux.run(Workspace.wrap(command))
        return ToolOutcome(r.ok, shellHead("Ubuntu", r.code, command) + r.out, Route.SANDBOX)
    } //linux_shell：在自带 Ubuntu 里跑，/sdcard 是同一条路径

    /** 米家台账：list 看本地、sync 拉云端、add 手工补（没有云端也能局域网直连） */
    private fun miDevices(a: JSONObject): ToolOutcome {
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

    /** 局域网扫描：只回填 IP 与在线状态，协议细节全在 C++ 侧 */
    private fun miScan(a: JSONObject): ToolOutcome {
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

    /** 读属性：局域网优先、云端兜底都是 MiHome 内部的事，这里只报结果 */
    private fun miRead(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val piid = a.optInt("piid", -1)
        if (siid < 0 || piid < 0) return ToolOutcome(false, "siid / piid 必须是非负整数", Route.SANDBOX)
        val v = MiHome.read(did, siid, piid)
            ?: return ToolOutcome(false, "读不到属性（设备离线、token 失效，或云端没登录）", Route.SANDBOX)
        return ToolOutcome(true, "did=$did siid=$siid piid=$piid → $v", Route.SANDBOX)
    } //读属性

    /** 写属性：会真改设备状态，计划模式下已被 PLAN_OFF 拦掉 */
    private fun miWrite(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val piid = a.optInt("piid", -1)
        if (siid < 0 || piid < 0) return ToolOutcome(false, "siid / piid 必须是非负整数", Route.SANDBOX)
        val v = valueOf(a.optString("value"))
        val err = MiHome.write(did, siid, piid, v)
        return ToolOutcome(err == null, err ?: "已写入：did=$did siid=$siid piid=$piid = $v", Route.SANDBOX)
    } //写属性

    /** 调动作：args 支持 JSON 数组与逗号分隔两种写法 */
    private fun miAction(a: JSONObject): ToolOutcome {
        val did = a.optString("did").trim()
        if (did.isEmpty()) return ToolOutcome(false, "缺少 did，先调 mijia_devices 拿", Route.SANDBOX)
        val siid = a.optInt("siid", -1)
        val aiid = a.optInt("aiid", -1)
        if (siid < 0 || aiid < 0) return ToolOutcome(false, "siid / aiid 必须是非负整数", Route.SANDBOX)
        val err = MiHome.action(did, siid, aiid, argsOf(a.optString("args")))
        return ToolOutcome(err == null, err ?: "动作已下发：did=$did siid=$siid aiid=$aiid", Route.SANDBOX)
    } //调动作

    /** 参数值按内容转类型：设备对 true / 数字 / 字符串是三种不同的东西，糊成字符串会被拒 */
    private fun valueOf(raw: String): Any {
        val s = raw.trim()
        s.lowercase().toBooleanStrictOrNull()?.let { return it }
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        return s
    } //值转型

    /** 动作参数表：优先按 JSON 数组解析，否则按逗号切；空串给空表 */
    private fun argsOf(raw: String): List<Any> {
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

    /** 平台标识：认 id，也认中文名与网易 / qq / 酷我 这类简写 */
    private fun platOf(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        MusicCfg.PLATS.keys.firstOrNull { it == s }?.let { return it }
        return MusicCfg.PLATS.keys.firstOrNull { id ->
            val name = MusicCfg.PLATS[id].orEmpty().lowercase()
            name.contains(s) || s.contains(id)
        }
    } //平台标识

    /** 音质档位：认全称，也认 128 / 192 / 320 / 无损 这种简写 */
    private fun qualityOf(raw: String): String? {
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

    /** 平台现状：顺序即优先级，登录态只说有没有、不说内容 */
    private fun musicText(c: MusicCfg.Cfg): String {
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

    /** 音乐平台设置：一次只让一家说话，enable 直接把别的关掉 */
    private fun musicPlats(a: JSONObject): ToolOutcome {
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

    /** 登录态：网易云能真登录（验证码 / cookie 校验），其它平台先只存 cookie */
    private fun musicLogin(a: JSONObject): ToolOutcome {
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

    /** 缓存：size 只看占用，clear 真删并回显释放多少 */
    private fun musicCache(a: JSONObject): ToolOutcome {
        val act = a.optString("action", "clear").trim().lowercase()
        if (act == "size") {
            return ToolOutcome(true, "缓存占用 " + MusicCfg.sizeText(MusicCfg.cacheSize()), Route.SANDBOX)
        }
        val freed = MusicCfg.clearCache()
        return ToolOutcome(true, "已清空缓存，释放 " + MusicCfg.sizeText(freed), Route.SANDBOX)
    } //清缓存
}