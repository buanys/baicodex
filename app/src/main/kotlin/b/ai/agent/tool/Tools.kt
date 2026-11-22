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
        SHELL -> ShellTools.execShell(
            args.optString("command"),
            args.optString("path"),
            args.optString("session"),
            args.optString("keys")
        ).governed()
        //终端排障：先看画面（term_view），再决定发什么键（term_send / exec_shell 的 keys）
        TERM_VIEW -> DeviceTools.termView(args).governed()
        TERM_SEND -> DeviceTools.termSend(args).governed()
        LINUX -> ShellTools.execLinux(args.optString("command")).governed()
        WEB_SEARCH -> SearchTools.search(args).governed()
        RAIL -> SearchTools.rail(args).governed()
        MEMORY -> SkillTools.memorySave(args).governed()
        MEMORY_READ -> SkillTools.memorySearch(args).governed()
        MEMORY_DEL -> SkillTools.memoryDelete(args).governed()
        //ask_user 由 Agent 直接拦下弹面板，走到这里说明调用链不对
        ASK -> ToolOutcome(false, "ask_user 需要界面交互，无法在后台执行", Route.SANDBOX)
        SCREEN -> DeviceTools.screen(args).governed()
        LIST -> FileOps.list(
            args.optString("path"), args.optBoolean("recurse", false), args.optInt("limit", 200),
            args.optString("match"), args.optBoolean("summarize", false)
        ).toOutcome().governed()
        INFO -> FileOps.info(args.optString("path")).toOutcome().governed()
        MOVE -> FileOps.move(
            args.optString("from"), args.optString("to"), args.optBoolean("copy", false)
        ).toOutcome().governed()
        ZIP -> FileTools.zipOp(args).governed()
        GREP -> FileOps.grep(
            args.optString("path"), args.optString("pattern"), args.optString("ext"),
            args.optInt("max", 60), args.optBoolean("ignore_case", true),
            args.optBoolean("recurse", true), args.optBoolean("summarize", false)
        ).toOutcome().governed()
        CLIP -> DeviceTools.clip(args).governed()
        OPEN -> DeviceTools.openLink(args).governed()
        NOTI -> DeviceTools.noti(args).governed()
        HTTP -> NetTools.httpFetch(args).governed()
        PING -> NetTools.netPing(args).governed()
        CURL -> NetTools.netCurl(args).governed()
        PAGE -> NetTools.netPage(args).governed()
        APKTOOL -> NetTools.apk(args).governed()
        BROWSE -> NetTools.browse(args).governed()
        SKILL_READ -> SkillTools.skillRead(args.optString("name")).governed()
        //subagent_run 由主循环并行调度（要拿 Agent.status 报进度），走到这里说明调用链不对
        SUB -> ToolOutcome(false, "subagent_run 需要主循环调度，不能在后台单独执行", Route.SANDBOX)
        //flow_run 同理：要报进度、要能被停止键打断
        FLOW_RUN -> ToolOutcome(false, "flow_run 需要主循环调度，不能在后台单独执行", Route.SANDBOX)
        FLOW_SAVE -> FlowTools.flowSave(args)
        FLOW_AUTO -> FlowTools.flowAuto(args)
        CHAT_SEARCH -> SearchTools.chatSearch(args)
        FLOW_LIST -> FlowTools.flowList()
        A2A -> CollabTools.a2a(args).governed()
        LAN -> CollabTools.lanTool(args).governed()
        MI_DEVICES -> MiTools.miDevices(args).governed()
        MI_SCAN -> MiTools.miScan(args).governed()
        MI_READ -> MiTools.miRead(args).governed()
        MI_WRITE -> MiTools.miWrite(args).governed()
        MI_ACTION -> MiTools.miAction(args).governed()
        MUSIC_PLATS -> MusicTools.musicPlats(args).governed()
        MUSIC_LOGIN -> MusicTools.musicLogin(args).governed()
        MUSIC_CACHE -> MusicTools.musicCache(args).governed()
        SSH_CONNECT, SSH_LS, SSH_READ, SSH_WRITE, SSH_SUDO, SSH_EXEC, SSH_GET, SSH_PUT ->
            SshTools.sshTool(name, args).governed()
        JS -> FlowTools.jsRun(args).governed()
        else -> SkillPacks.find(name)?.let { SkillTools.skillExec(it, args) }?.governed()
            ?: FlowTools.jsTool(name, args)
            ?: SkillTools.mcpCall(name, args).governed()
    } //执行单个工具
    /** JS 脚本：跑在 cpp/js/qjs.cpp 的 QuickJS 里，脚本里的 bai.call 回到这里调别的工具 */
    /** JS 注册的工具：没有就返回 null，让后面的技能包 / MCP 继续找 */

    /** flow_save：存一条工作流，顺手登记定时（时间不合法就不绑定时，步骤照样存） */
    /** chat_search：按关键词翻历史消息，时间与会话标题都带上，模型据此引用 */

    /** flow_auto：拆步骤 → 补自测 → 存盘，返回可照着验收的测试清单 */

    /** 让模型把目标拆成步骤：返回空就由调用方退回按行切分 */

    /** flow_list：把工作流摆出来，id 和上次结果都带上 */
    /** a2a：本机保存的对端列表 + 5 种动作；对端找不到时允许直接写地址临时用 */
    /** 局域网团队协作：发现、配对、派活；peer 认名字/设备号/IP，台账里没有就现场扫一遍 */


    /** 定时时间解析：认 yyyy-MM-dd HH:mm、yyyy/MM/dd HH:mm、HH:mm（按今天算）；过期时间每天的顺延，单次的返 0 */
    /** skill_read：返回技能用法，正文按需给，不进系统提示 */

    /** 技能函数：先过 DSL 引擎校验+插值，cmd 逐行执行，http 直接发请求 */

    /** http 技能：报文照约定拆成 方法 / URL / header / body，不额外引 HTTP 库 */

    /** 存一条永久记忆：重复的直接告诉模型别啰嗦 */

    /** 按关键词查永久记忆：记忆可能几百条，模型看不到全部，缺细节时自己回查 */

    /** 删一条永久记忆：给 id 直接删；只给关键词时命中唯一一条才删，多条只列候选 */

    /** SSH 专用工具分发：每个动作一个独立工具名，远端路径转义全在 Ssh 里做 */

    /** MCP 能力调用：能力不在缓存 / 服务被删或停用都如实报错，不能静默当没这工具 */

    /** 搜索技能：按用户选的平台分发；平台表以后往里加分支即可 */

    /** 12306 查询：只读网络请求，走应用身份，不需要 root / Shizuku */

    /** 屏幕操作：全部动作走 A11y；没权限就置位让界面弹引导，而不是只回一句失败 */

    /** 截图落盘到私有目录 img/shots，路径回给模型；勾了图片输入的模型可以据此看图 */

    /** 剪贴板：get 读、set 写，成败由 Device 那边给 */

    /** 打开链接 / 分享：url 走浏览器，share 走系统分享面板 */

    /** 通知：list 读今日通知（可按应用筛），send 发一条本地通知给用户 */

    /** 直发 HTTP：不依赖 curl，headers 每行一个「名: 值」，返回状态码与正文前 8000 字 */
    /** net_ping：native TCP 建连计时，普通权限可跑 */
    /** net_curl：任意方法 + 自定义头，回状态码、原始响应头与正文 */
    /** net_page：native 抓 HTML 后转纯文本 */


    /**
     * 查看终端会话最近的回显：命令卡住 / 看不到进度时先调它。
     * 只读，不改状态，所以计划模式下也允许。
     */

    /**
     * 向终端会话 stdin 写文本：分页器发 q、交互确认 y/n、REPL 输入等。
     * 会改变会话状态，计划模式下被 PLAN_OFF 拦掉。
     */




    /** 执行头：通道 + 退出码 + 实际 cwd + 命令原文，模型不用猜命令跑在哪、跑的是什么 */

    /** 命令原文压成一行：多行命令也只看得到前后文，换个行当分隔 */

    /** 当前执行通道对应的 Route：结果卡片上的标签跟它走 */


    /** 米家台账：list 看本地、sync 拉云端、add 手工补（没有云端也能局域网直连） */

    /** 局域网扫描：只回填 IP 与在线状态，协议细节全在 C++ 侧 */

    /** 读属性：局域网优先、云端兜底都是 MiHome 内部的事，这里只报结果 */

    /** 写属性：会真改设备状态，计划模式下已被 PLAN_OFF 拦掉 */

    /** 调动作：args 支持 JSON 数组与逗号分隔两种写法 */

    /** 参数值按内容转类型：设备对 true / 数字 / 字符串是三种不同的东西，糊成字符串会被拒 */

    /** 动作参数表：优先按 JSON 数组解析，否则按逗号切；空串给空表 */

    /** 平台标识：认 id，也认中文名与网易 / qq / 酷我 这类简写 */

    /** 音质档位：认全称，也认 128 / 192 / 320 / 无损 这种简写 */

    /** 平台现状：顺序即优先级，登录态只说有没有、不说内容 */

    /** 音乐平台设置：一次只让一家说话，enable 直接把别的关掉 */

    /** 登录态：网易云能真登录（验证码 / cookie 校验），其它平台先只存 cookie */

    /** 缓存：size 只看占用，clear 真删并回显释放多少 */
}