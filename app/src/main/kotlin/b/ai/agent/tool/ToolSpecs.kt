/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.agent.tool

import b.ai.core.data.log.Logs as Log
import b.ai.core.plugin.skill.SkillPacks
import b.ai.device.shell.local.Linux
import b.ai.native.script.Js
import b.ai.net.mcp.Mcp
import b.ai.net.search.Search
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下发给模型的工具定义（名字 / 描述 / 参数 schema）。
 *
 * 从 Tools.kt 里拆出来单独一份：这是模型看到的「接口」，跟工具的实现是两件事——
 * 改描述不会碰到执行逻辑，review 时也能一眼看完模型到底被承诺了什么。
 */
internal fun toolSpecs(): JSONArray {
        val arr = JSONArray()
        arr.put(
            fn(
                Tools.READ,
                "读取文件内容，按行分页并带行号与总行数；路径是目录时返回目录列表。" +
                    "行号可直接喂给 edit_file 的 line 参数；文件很大就一页页翻，别一次要几千行。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("path", str("绝对路径"))
                        .put("page", JSONObject().put("type", "integer").put("description", "第几页，从 1 开始，默认 1"))
                        .put("lines", JSONObject().put("type", "integer").put("description", "每页多少行，默认 400，上限 2000"))
                ).put("required", JSONArray().put("path"))
            )
        )
        arr.put(
            fn(
                Tools.WRITE, "写入文本文件（覆盖写，父目录自动创建）。",
                params("path" to str("绝对路径"), "content" to str("完整文本内容"))
            )
        )
        //局部改：模型最爱大段重写整个文件，这里给它一条只改几行的路
        arr.put(
            fn(
                Tools.EDIT,
                "局部修改文件，两种用法。①默认 replace：把 old 这段原文换成 new，old 按行匹配、缩进和行尾空白" +
                    "自动忽略，其余字符必须与文件里完全一致，命中多处会报错。②mode=insert / delete：按行号插入或删除，" +
                    "line 直接抄 read_file 结果里的行号；insert 用 text 给新代码、pos 决定插在该行后还是前，" +
                    "delete 用 count 说明删几行。插入行的缩进会自动对齐到锚点行，不用自己补。" +
                    "改几行就用它，别整篇 write_file。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("path", str("绝对路径"))
                        .put("old", str("replace：文件里现在这几行的原文，原样抄，可以只抄正文不管缩进"))
                        .put("new", str("replace：替换后的新代码，缩进不用自己补，靠前面对齐"))
                        .put("mode", str("留空=replace；insert=按行号插入；delete=按行号删除"))
                        .put(
                            "line",
                            JSONObject().put("type", "integer")
                                .put("description", "insert / delete 的行号，从 1 开始；insert 时 line=0 表示插到文件最前面")
                        )
                        .put("count", JSONObject().put("type", "integer").put("description", "delete：删几行，默认 1"))
                        .put("pos", str("insert：after（默认，插在该行后面）或 before（插在该行前面）"))
                        .put("text", str("insert：要插入的代码"))
                ).put("required", JSONArray().put("path"))
            )
        )
        arr.put(fn(Tools.DELETE, "删除文件或目录（目录递归删除）。", params("path" to str("绝对路径"))))
        arr.put(fn(Tools.MAKE_DIR, "创建目录（递归建父级），已存在不算失败。", params("path" to str("绝对路径"))))
        arr.put(
            fn(
                Tools.SHELL, "执行 shell 命令，返回 stdout 与 stderr 合并后的输出。解压、安装、逆向等都用它拼命令完成。",
                params(
                    "command" to str("要执行的完整 shell 命令"),
                    "session" to str(
                        "会话名。不填 = 一次性执行，跑完进程就退。填了就在常驻会话里跑：" +
                            "同名会话共用 cwd 与环境变量（cd、export 跨调用保持），不同名各自独立，最多 6 个"
                    ),
                    "path" to str("本次命令的工作目录，如 /sdcard/Download。填了会先 cd 过去；不填用当前工作区"),
                    "keys" to str(
                        "功能键，配合 session 用，传了就不执行 command：" +
                            "ctrl-c 打断会话里正在跑的命令、ctrl-d 写 EOF、enter 回车"
                    )
                )
            )
        )
        //终端查看 / 输入：排障用。命令卡住、看不到进度时先 view 看回显，再 send 发键（如分页器 q）
        arr.put(
            fn(
                Tools.TERM_VIEW,
                "查看某个终端会话最近的回显画面（最后若干行）。命令长时间没输出、怀疑卡住时先调用它看清当前状态，" +
                    "再决定发什么键或用 keys=ctrl-c 结束，避免盲目打断。",
                toolIn(
                    JSONObject().put("session", str("会话名，和 exec_shell 用的是同一套；不填=main"))
                        .put("tail", num("返回最后多少行，默认 80，最多 800"))
                )
            )
        )
        arr.put(
            fn(
                Tools.TERM_SEND,
                "向终端会话的 stdin 写文本：回答 y/n 确认、给分页器（less/more）发 q、在 REPL 里敲内容等。" +
                    "配合 term_view 用：先 view 看画面，再 send 对应输入。",
                toolIn(
                    JSONObject().put("session", str("会话名，和 exec_shell 用的是同一套；不填=main"))
                        .put("text", str("要写入的文本，例如 q、y、help"))
                        .put(
                            "enter",
                            JSONObject().put("type", "boolean").put("description", "写完是否补一个回车，默认 false")
                        ),
                    "text"
                )
            )
        )
        //12306：余票 / 中转 / 经停三个动作合成一个工具，参数多但少一个工具就少一轮误选
        val railProps = JSONObject()
            .put("action", str("tickets 查余票（默认）；transfer 查中转；route 查某车次经停站"))
            .put("from", str("出发站或城市中文名，如 杭州、上海虹桥、北京"))
            .put("to", str("到达站或城市中文名"))
            .put("date", str("出发日期 yyyy-MM-dd，只能查今天起 15 天内"))
            .put("middle", str("仅 transfer：中转城市中文名，如 南京、武汉"))
            .put("type", str("车次类型筛选，可组合：G D Z T K，不填=全部"))
            .put("sort", str("排序：start 出发时间（默认）、arrive 到达时间、duration 历时"))
            .put("limit", JSONObject().put("type", "integer").put("description", "显示条数，默认 10，最多 40"))
            .put("train_no", str("仅 route：12306 内部车次号，形如 650000K52806，从 tickets 结果的 no= 取"))
        arr.put(
            fn(
                Tools.RAIL,
                "查 12306 余票、中转组合与车次经停站。车次、时刻、余票一律用它，不要凭记忆编。" +
                    "用户说「明天、后天」先换算成 yyyy-MM-dd。",
                JSONObject().put("type", "object").put("properties", railProps)
                    .put("required", JSONArray().put("action").put("from").put("to").put("date"))
            )
        )
        //内置 Linux：装好环境才下发，命令在 proot 里的 Ubuntu 跑
        if (Linux.installed()) {
            arr.put(
                fn(
                    Tools.LINUX,
                    "在应用自带的 Ubuntu 24.04（proot）里执行命令，返回输出。" +
                        "apt 装依赖、编译、跑脚本都用它；宿主 Android 的命令仍用 exec_shell。",
                    params("command" to str("要执行的 Ubuntu shell 命令"))
                )
            )
        }
        //搜索技能：只有用户选了平台才把 web_search 交给模型
        val provider = Search.selected
        if (provider != null) {
            //参数做成可选：query 必填，其余走默认值，省得模型每次都堆一堆字段
            val webProps = JSONObject()
                .put("query", str("搜索词，越具体越好"))
                .put("max_results", JSONObject().put("type", "integer").put("description", "返回条数，1-10，默认 5"))
                .put("topic", str("general 或 news，默认 general"))
                .put("days", JSONObject().put("type", "integer").put("description", "news 时限定最近多少天"))
                .put("search_depth", str("basic 或 advanced，默认 basic"))
            arr.put(
                fn(
                    Tools.WEB_SEARCH,
                    "联网搜索（${provider.label}）。需要最新信息、查资料、确认事实时用它，返回标题、链接与摘要。",
                    JSONObject().put("type", "object").put("properties", webProps)
                        .put("required", JSONArray().put("query"))
                )
            )
        }
        //永久记忆：模型自己判断哪些值得长期记住
        arr.put(
            fn(
                Tools.MEMORY,
                "把值得长期记住的事实、结论或用户偏好存进永久记忆。用户删掉之前，它每轮都会随系统提示生效；普通寒暄、临时信息不要存。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject().put(
                        "text",
                        str("要记住的一句话，写成能独立看懂的陈述句")
                    )
                ).put("required", JSONArray().put("text"))
            )
        )
        //向用户提问：模型自己决定问什么、给哪些选项，UI 分页展示，可多选 + 自定义填写
        val qItem = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("q", str("问题正文，一句话说清要选什么"))
                    .put(
                        "options",
                        JSONObject().put("type", "array").put("description", "可选项，2-4 个，短句，不要带 A/B 前缀")
                            .put("items", JSONObject().put("type", "string"))
                    )
                    .put("multi", JSONObject().put("type", "boolean").put("description", "是否可多选，默认 false"))
                    .put("other", JSONObject().put("type", "boolean").put("description", "末尾是否给「自己填写」，默认 true"))
            )
            .put("required", JSONArray().put("q").put("options"))
        arr.put(
            fn(
                Tools.ASK,
                "向用户提问以便确认细节。一次可以问多个问题，界面会按页展示（‹ 1/3 ›），每题列出可选项，" +
                    "用户还能自己填写。信息不足时先问，不要靠猜。",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("questions", JSONObject().put("type", "array")
                        .put("description", "要问的问题，1-6 个").put("items", qItem)))
                    .put("required", JSONArray().put("questions"))
            )
        )
        //永久记忆：按关键词回查
        arr.put(
            fn(
                Tools.MEMORY_READ,
                "在永久记忆里按关键词检索，返回命中的记忆条目（带 id）与总条数。记忆条数多时不一定全在提示里，" +
                    "遇到像是以前告诉过你的事（地址、密码、偏好、结论）但当前上下文没有，先用它查一次，别直接问用户、也别猜。" +
                    "查询用短关键词效果最好，可以空格分隔多个词。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("query", str("关键词，空格分隔多个，例如「服务器 密码」"))
                        .put("limit", JSONObject().put("type", "integer").put("description", "最多返回几条，默认 20，上限 50"))
                ).put("required", JSONArray().put("query"))
            )
        )
        //永久记忆：删除。命中多条一律不直接删，先列候选让模型按 id 逐条删
        arr.put(
            fn(
                Tools.MEMORY_DEL,
                "删除永久记忆。传 id 删指定一条（id 从 memory_search 结果里拿）；只传 query 时，" +
                    "命中唯一一条才直接删，命中多条会把候选列出来，需要你再按 id 逐条删。" +
                    "动手前先跟用户确认要删哪条，删完把删掉的内容告诉用户。",
                params(
                    "id" to JSONObject().put("type", "integer").put("description", "要删的记忆 id"),
                    "query" to str("按关键词找要删的记忆，只在能唯一定位时才删")
                )
            )
        )
        //Tools.JS：QuickJS 脚本，能调工具也能注册工具，能力等于任意代码执行，归高危
        arr.put(
            fn(
                Tools.JS,
                "在内置 QuickJS 里执行一段 Tools.JS（ES2023，没有 Node 的文件系统 / 网络模块）。脚本里可用：" +
                    "console.log / print 输出、bai.call(工具名, 参数对象) 同步调用任意工具并拿到它的输出（形如 \"OK\\n...\"）、" +
                    "registerTool({name, description, parameters, run}) 注册新工具。适合循环、JSON 变换、批量拼命令这类 shell 写起来别扭的活。",
                params(
                    "script" to str("要执行的 Tools.JS 源码"),
                    "timeout" to JSONObject().put("type", "integer")
                        .put("description", "超时毫秒，默认 15000，最多 300000")
                )
            )
        )
        //SSH：拆成一整组专用工具，模型不用再拿 exec 拼 ssh 命令；远端路径一律由工具侧做单引号转义
        arr.put(
            fn(
                Tools.SSH_CONNECT,
                "连服务器（只需配一次）：保存 host / user / pass / port 并当场自检。内置 Ubuntu 缺 openssh-client 会自动补装，" +
                    "不需要重装环境。换服务器或改密码时再调一次。",
                toolIn(
                    JSONObject()
                        .put("host", str("服务器地址或域名，例如 1.2.3.4"))
                        .put("port", JSONObject().put("type", "integer").put("description", "端口，默认 22"))
                        .put("user", str("登录用户名"))
                        .put("pass", str("登录密码")),
                    "host", "user", "pass"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_LS,
                "列出服务器上的目录：默认长格式（含隐藏文件、权限、大小）。想看多层结构就把 depth 设 2~4。" +
                    "远端路径由工具自己加引号，不用你手动转义。",
                toolIn(
                    JSONObject()
                        .put("path", str("远端目录，例如 /www/wwwroot"))
                        .put("depth", JSONObject().put("type", "integer").put("description", "递归层数，默认 1 只列本层")),
                    "path"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_READ,
                "读服务器上的文本文件（单次上限 4MB）。大文件或二进制改用 ssh_get 下载。",
                toolIn(
                    JSONObject()
                        .put("path", str("远端文件路径"))
                        .put("max_lines", JSONObject().put("type", "integer").put("description", "只返回前 N 行，0（默认）＝全文")),
                    "path"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_WRITE,
                "把整段文本写到服务器上的文件（父目录自动创建，已存在则覆盖）。",
                toolIn(
                    JSONObject()
                        .put("path", str("远端文件路径"))
                        .put("content", str("要写入的完整文本，可以传空字符串建空文件")),
                    "path", "content"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_SUDO,
                "以 root（sudo）身份在服务器上执行一条命令：改 nginx / systemctl / apt / 写 /etc 这类系统位置都用它，" +
                    "不要用 ssh_exec 自己拼 sudo。密码由工具喂给 sudo -S，不用也不许写进命令里（写了会泄露到远端 ps）。",
                toolIn(
                    JSONObject()
                        .put("command", str("要执行的命令，例如 systemctl restart nginx 或 nginx -t"))
                        .put("timeout", JSONObject().put("type", "integer").put("description", "超时秒数，默认 180")),
                    "command"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_EXEC,
                "以登录用户在服务器上执行一条命令，只用于没有专用工具的场景（看进程、看磁盘、跑脚本）。" +
                    "列目录用 ssh_ls、读文件用 ssh_read、写文件用 ssh_write、要 root 用 ssh_sudo、下载上传用 ssh_get / ssh_put。" +
                    "命令原样交给远端 sh，路径自己加引号。",
                toolIn(
                    JSONObject()
                        .put("command", str("远端命令，例如 df -h 或 ps aux | head -20"))
                        .put("timeout", JSONObject().put("type", "integer").put("description", "超时秒数，默认 180")),
                    "command"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_GET,
                "从服务器下载文件到本机（本机就是这台手机，local 要给手机上的绝对路径）。",
                toolIn(
                    JSONObject()
                        .put("path", str("服务器上的文件路径"))
                        .put("local", str("本机绝对路径，例如 /sdcard/baicode/dl/x.tar.gz")),
                    "path", "local"
                )
            )
        )
        arr.put(
            fn(
                Tools.SSH_PUT,
                "把本机文件上传到服务器（远端父目录自动创建）。",
                toolIn(
                    JSONObject()
                        .put("local", str("本机绝对路径"))
                        .put("path", str("服务器上的目标路径")),
                    "local", "path"
                )
            )
        )
        //屏幕操作：无障碍侧控制，动作合进一个工具，按 action 取参数
        arr.put(
            fn(
                Tools.SCREEN,
                "操作当前手机屏幕（无障碍）。先用 see/tree 看清界面再动手：tree 给控件树与坐标，see 额外存一张截图。" +
                    "动作：tap 点坐标、long 长按、swipe 滑动、text 往焦点输入框打字、key 系统键(back/home/recents/notifications/quick)、" +
                    "launch 按包名启动 App、apps 列出可启动的 App。没开无障碍会失败并提示用户去开。" +
                    "多步操作（连着几下点击/输入/滑动）第一步先 action=start 让顶部胶囊常驻，全部做完最后一步 action=quiet 收起，" +
                    "中间每步不用再管胶囊。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put(
                            "action",
                            str(
                                "see 控件树+截图(默认) / tree / shot / tap / long / swipe / text / key / launch / apps / " +
                                    "start 胶囊常驻 / quiet 收起胶囊"
                            )
                        )
                        .put("x", JSONObject().put("type", "integer").put("description", "tap/long/swipe 起点 x"))
                        .put("y", JSONObject().put("type", "integer").put("description", "tap/long/swipe 起点 y"))
                        .put("x2", JSONObject().put("type", "integer").put("description", "swipe 终点 x"))
                        .put("y2", JSONObject().put("type", "integer").put("description", "swipe 终点 y"))
                        .put("dur", JSONObject().put("type", "integer").put("description", "长按 / 滑动时长毫秒"))
                        .put("text", str("text 动作要输入的文字；start 时可给一句「正在做什么」，会显示在胶囊上"))
                        .put("key", str("key 动作：back / home / recents / notifications / quick"))
                        .put("app", str("launch 用包名；不确定就先 action=apps"))
                        .put(
                            "all",
                            JSONObject().put("type", "boolean").put(
                                "description",
                                "tree / see 时是否连没有文字的空壳容器一起输出，默认 false；默认读不到控件时也会自动附一份诊断输出"
                            )
                        )
                ).put("required", JSONArray().put("action"))
            )
        )
        //1.6.0 补齐的文件与系统工具：参数化动作合并到一个工具里，少一个工具就少一次误选
        arr.put(
            fn(
                Tools.LIST,
                "列目录（比 read_file 读目录更清楚）：带类型、体积、修改时间。recurse=true 递归到 6 层；" +
                    "match 按文件名过滤（*.kt 通配或关键字）；summarize=true 让总结模型归纳这批文件是什么、先看哪几个。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("path", str("目录绝对路径"))
                        .put("recurse", JSONObject().put("type", "boolean").put("description", "是否递归，默认 false"))
                        .put("limit", JSONObject().put("type", "integer").put("description", "最多列多少项，默认 200"))
                        .put("match", str("按文件名过滤：*.kt 这类通配，或普通关键字（忽略大小写），留空=全部"))
                        .put(
                            "summarize",
                            JSONObject().put("type", "boolean")
                                .put("description", "true 时把结果交给总结模型归纳，默认 false")
                        )
                ).put("required", JSONArray().put("path"))
            )
        )
        arr.put(
            fn(
                Tools.INFO, "看文件或目录的信息：体积、修改时间、图片尺寸、md5 与 sha256（20MB 内）。",
                params("path" to str("绝对路径"))
            )
        )
        arr.put(
            fn(
                Tools.MOVE, "移动文件或目录；copy=true 改成复制。跨通道（root / Shizuku / 零宽）会自动选路。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("from", str("源路径"))
                        .put("to", str("目标路径"))
                        .put("copy", JSONObject().put("type", "boolean").put("description", "true=复制，默认 false=移动"))
                ).put("required", JSONArray().put("from").put("to"))
            )
        )
        arr.put(
            fn(
                Tools.ZIP, "压缩或解压 zip：action=zip 把 paths 打包（英文逗号分隔多个路径）；action=unzip 解压到 to。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("zip（默认）或 unzip"))
                        .put("paths", str("仅 zip：要打包的路径，英文逗号分隔"))
                        .put("path", str("仅 unzip：zip 文件路径"))
                        .put("out", str("仅 zip：压缩包输出路径"))
                        .put("to", str("仅 unzip：解压目标目录，留空则解到压缩包同名的文件夹"))
                ).put("required", JSONArray().put("action"))
            )
        )
        arr.put(
            fn(
                Tools.GREP, "在文件或整个目录里按内容搜（正则），输出 路径:行号: 内容；跳过二进制与超 2MB 的文件。" +
                    "recurse=false 只搜该目录直属文件；summarize=true 让总结模型归纳命中集中在哪、下一步看哪几行。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("path", str("文件或目录的绝对路径"))
                        .put("pattern", str("正则表达式"))
                        .put("ext", str("只搜这些扩展名，英文逗号分隔，留空=全部"))
                        .put("max", JSONObject().put("type", "integer").put("description", "最多命中多少条，默认 60"))
                        .put("ignore_case", JSONObject().put("type", "boolean").put("description", "是否忽略大小写，默认 true"))
                        .put("recurse", JSONObject().put("type", "boolean").put("description", "目录是否往下递归，默认 true"))
                        .put(
                            "summarize",
                            JSONObject().put("type", "boolean")
                                .put("description", "true 时把命中结果交给总结模型归纳，默认 false")
                        )
                ).put("required", JSONArray().put("path").put("pattern"))
            )
        )
        arr.put(
            fn(
                Tools.CLIP, "读写剪贴板：action=get 读当前剪贴板文本；action=set 把 text 写进剪贴板。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("get（默认）或 set"))
                        .put("text", str("仅 set：要复制进剪贴板的文字"))
                ).put("required", JSONArray().put("action"))
            )
        )
        arr.put(
            fn(
                Tools.OPEN, "打开链接或分享文本：action=url 用浏览器打开网页；action=share 拉起系统分享面板把 text 发出去。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("url（默认）或 share"))
                        .put("url", str("仅 url：要打开的网址"))
                        .put("text", str("仅 share：要分享的文字"))
                ).put("required", JSONArray().put("action"))
            )
        )
        arr.put(
            fn(
                Tools.NOTI, "通知：action=list 看今天收到的通知（app 可按应用名/包名筛）；action=send 给用户发一条本地通知。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("list（默认）或 send"))
                        .put("app", str("仅 list：只看这个应用的通知，留空=全部"))
                        .put("limit", JSONObject().put("type", "integer").put("description", "仅 list：最多几条，默认 30"))
                        .put("title", str("仅 send：通知标题"))
                        .put("text", str("仅 send：通知正文"))
                ).put("required", JSONArray().put("action"))
            )
        )
        arr.put(
            fn(
                Tools.HTTP, "直接发 Tools.HTTP 请求（不用拼 curl）：返回状态码与正文前 8000 字。headers 每行一个「名: 值」。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("method", str("GET（默认）/ POST / PUT / Tools.DELETE / HEAD"))
                        .put("url", str("完整 URL，带 https://"))
                        .put("headers", str("可选：每行一个「名: 值」"))
                        .put("body", str("可选：请求体，JSON 或纯文本"))
                ).put("required", JSONArray().put("url"))
            )
        )
        arr.put(
            fn(
                Tools.PING,
                "测网络延迟：对域名或 IP 的端口做 TCP 建连计时（普通权限就能跑，不依赖 ICMP），" +
                    "返回每次耗时与最小/平均/最大值。判断网络通不通、哪个环节慢时先跑它。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("host", str("域名或 IP，例如 agent.ai.baiccb.cn"))
                        .put("port", JSONObject().put("type", "integer").put("description", "端口，默认 443；测 Tools.HTTP 用 80"))
                        .put("count", JSONObject().put("type", "integer").put("description", "测几次，1-10，默认 3"))
                        .put("timeout", JSONObject().put("type", "integer").put("description", "单次超时毫秒，默认 3000"))
                ).put("required", JSONArray().put("host"))
            )
        )
        arr.put(
            fn(
                Tools.CURL,
                "发一次任意方法的 Tools.HTTP 请求（原生实现，比 http_fetch 快）：返回状态码、原始响应头与正文；" +
                    "默认跟 30x 跳转。要看响应头、要发 PUT/PATCH/Tools.DELETE、要精确控超时用它。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("method", str("GET（默认）/ POST / PUT / Tools.DELETE / HEAD"))
                        .put("url", str("完整 URL，带 https://"))
                        .put("headers", str("可选：每行一个「名: 值」"))
                        .put("body", str("可选：请求体，JSON 或纯文本"))
                        .put("timeout", JSONObject().put("type", "integer").put("description", "超时毫秒，默认 15000"))
                        .put("max", JSONObject().put("type", "integer").put("description", "正文最多返多少字，默认 20000"))
                ).put("required", JSONArray().put("url"))
            )
        )
        arr.put(
            fn(
                Tools.PAGE,
                "抓网页正文：原生下载 HTML 后直接去标签、去脚本与样式、解 HTML 实体，只回纯文本，" +
                    "比 browser 快得多。只读文章/文档/公告用它；要点链接、要登录态、要跑 Tools.JS 才用 browser。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("url", str("完整网址，可省略 https://"))
                        .put("timeout", JSONObject().put("type", "integer").put("description", "超时毫秒，默认 15000"))
                        .put("max", JSONObject().put("type", "integer").put("description", "正文最多返多少字，默认 20000"))
                ).put("required", JSONArray().put("url"))
            )
        )
        arr.put(
            fn(
                Tools.APKTOOL,
                "看 APK 安装包（纯本地解析，不用装 apktool）：action=info 概览（包名/版本/SDK/签名指纹/权限/组件）；" +
                    "sign 签名证书详情；permissions 权限清单；components 组件清单；" +
                    "dex 搜 dex 里的字符串（keyword 可给正则）；files 包内文件。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("info（默认）/ sign / permissions / components / dex / files"))
                        .put("path", str("apk 文件的绝对路径"))
                        .put("keyword", str("仅 dex：字符串过滤，支持正则，留空=按体积列前几条"))
                        .put("limit", JSONObject().put("type", "integer").put("description", "最多多少条/项，默认 60"))
                ).put("required", JSONArray().put("path"))
            )
        )
        arr.put(
            fn(
                Tools.BROWSE,
                "内置浏览器（不弹外置 App，直接在应用里抓页面）：action=open 打开网址并返回正文；" +
                    "text 重读正文；links 列链接；find 找页面里的文字；click 按文字点链接或按钮；" +
                    "input 往输入框填内容（target 指定框的提示文字）；scroll 往下翻；back / forward / reload / url；" +
                    "eval 跑一段 Tools.JS 取结果。要查网页里才有的内容、要点开链接、要登录后的页面，用它而不是 web_search。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("action", str("open（默认）/ text / links / find / click / input / scroll / back / forward / reload / url / eval"))
                        .put("url", str("仅 open：完整网址，可省略 https://"))
                        .put("text", str("find / click 要匹配的文字；input 要填的内容；scroll 翻多少像素"))
                        .put("target", str("仅 input：输入框的提示文字或 name，留空=第一个能填的框"))
                        .put("script", str("仅 eval：要执行的 Tools.JS，返回值转成字符串"))
                        .put("limit", JSONObject().put("type", "integer").put("description", "text 最多返回多少字，默认 4000"))
                ).put("required", JSONArray().put("action"))
            )
        )
        //分智能体：一次派多条互相独立的只读子任务并行跑，只有结论回主上下文，省主模型 token
        val subItem = JSONObject().put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("title", str("这条子任务的名字，2-8 字，汇总时用它分节"))
                    .put("task", str("交给分智能体的完整指令：做什么、在哪做、要什么格式的结论"))
                    .put(
                        "priority",
                        str("优先级 high / normal / low，默认 normal。high 先占并行槽；会写文件或执行命令的任务至少给 normal，关键步骤给 high")
                    )
            )
            .put("required", JSONArray().put("title").put("task"))
        arr.put(
            fn(
                Tools.SUB,
                "派分智能体并行干活：一次给 2-4 条互相独立的子任务（查资料、读文件、搜代码、写文件、跑命令、查车次），" +
                    "它们同时跑，各自返回结论，最后一次给你合并后的结果。每条任务必须标 priority：high 先跑、low 最后跑。" +
                    "子智能体的工具权限和确认弹窗跟你完全一致，用户拒绝的那一步会如实回报。" +
                    "适合「同时推进几件互不依赖的事」；只有一件事时自己做更快。",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put(
                            "tasks",
                            JSONObject().put("type", "array").put("items", subItem)
                                .put("description", "子任务列表，2-4 条最好，最多 6 条")
                        )
                        .put(
                            "rounds",
                            JSONObject().put("type", "integer")
                                .put("description", "每个分智能体最多几轮工具调用，默认 6，上限 12")
                        )
                ).put("required", JSONArray().put("tasks"))
            )
        )
        //工作流：多步串起来，能存盘、能定时、失败有退路
        val stepItem = JSONObject().put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("title", str("这一步的名字，2-8 字，报告里用它分节"))
                    .put("task", str("交给分智能体的完整指令：做什么、在哪做、要什么格式的结果。每步独立，拿不到上一步的结论"))
                    .put("retry", JSONObject().put("type", "integer").put("description", "失败后重试几次，默认 1，最多 3"))
                    .put("on_fail", str("stop / skip：重试用完还失败时，是停住整条流还是跳过继续，默认 stop"))
            )
            .put("required", JSONArray().put("task"))
        val stepsProp = JSONObject().put("type", "array").put("items", stepItem)
            .put("description", "步骤列表，按顺序执行，最多 12 步")
        arr.put(
            fn(
                Tools.FLOW_RUN,
                "跑一条工作流：多个步骤按顺序执行，每一步交给一个分智能体做完再进下一步。" +
                    "适合「先查、再改、再验证」这种有先后的活；互相独立的活直接用 subagent_run 并行更快。" +
                    "给 flow_id 就跑已保存的流；也可以直接给 name + steps 现跑一次（不存盘）。" +
                    "每步失败会按 retry 重试，重试用完按 on_fail 停住或跳过。",
                toolIn(
                    JSONObject()
                        .put("flow_id", str("已保存工作流的 id（先用 flow_list 查）"))
                        .put("name", str("临时工作流的名字，不存盘时用"))
                        .put("steps", stepsProp)
                        .put(
                            "rounds",
                            JSONObject().put("type", "integer")
                                .put("description", "每一步最多几轮工具调用，默认 6，上限 8")
                        )
                )
            )
        )
        arr.put(
            fn(
                Tools.FLOW_SAVE,
                "把一组步骤保存成工作流，以后能一键跑或定时跑。带上 at 就同时登记定时（到点自动执行）。" +
                    "传 id 就是覆盖已有的那条。",
                toolIn(
                    JSONObject()
                        .put("id", str("要覆盖的工作流 id，留空表示新建"))
                        .put("name", str("工作流名字，2-12 字"))
                        .put("steps", stepsProp)
                        .put("at", str("定时时间，格式 2026-01-01 08:30 或 08:30（今天这个点）；不填就不绑定定时"))
                        .put("daily", JSONObject().put("type", "boolean").put("description", "true 表示每天同一时间跑"))
                        .put(
                            "auto",
                            JSONObject().put("type", "boolean")
                                .put("description", "到点是否自动执行，默认 true；false 就只发通知提醒")
                        )
                        .put("on", JSONObject().put("type", "boolean").put("description", "开关，默认 true")),
                    "name", "steps"
                )
            )
        )
        arr.put(
            fn(
                Tools.FLOW_LIST,
                "列出已保存的工作流：id、名字、步骤数、定时、上次跑的结果。要跑或要改之前先调它拿 id。",
                JSONObject().put("type", "object").put("properties", JSONObject())
            )
        )
        arr.put(
            fn(
                Tools.CHAT_SEARCH,
                "在本地聊天记录里按关键词搜历史消息，返回命中的会话标题、说话人、时间与片段，" +
                    "并按「用户 / 助手 / 压缩摘要」给出条数统计。" +
                    "用户问「上次那个报错怎么解决的」「我之前说过什么」「你当时怎么答的」时先用它，别凭空回忆；" +
                    "role 可只查一方说的话，include_compressed 决定是否把上下文压缩摘要也算进来。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("query", str("关键词，尽量取原话里的名词或报错片段"))
                        .put("session_id", str("只搜某个会话时填，留空搜全部会话"))
                        .put(
                            "role",
                            JSONObject().put("type", "string")
                                .put("enum", JSONArray().put("all").put("user").put("assistant"))
                                .put("description", "只查某一方：user=我说的话，assistant=AI 说的话，all=都查（默认）")
                        )
                        .put(
                            "include_compressed",
                            JSONObject().put("type", "boolean")
                                .put("description", "是否把上下文压缩摘要（role=summary）也算进结果，默认 false")
                        )
                        .put("limit", JSONObject().put("type", "integer").put("description", "最多返回多少条，默认 20，上限 50"))
                ).put("required", JSONArray().put("query"))
            )
        )
        arr.put(
            fn(
                Tools.FLOW_AUTO,
                "按目标自动创建一条工作流：模型先把目标拆成可执行步骤，再自动补一条「自测与验收」步骤，" +
                    "保存后返回测试清单。只想存现成步骤就直接带 steps（会跳过自动拆解）。",
                JSONObject().put("type", "object").put(
                    "properties",
                    JSONObject()
                        .put("name", str("工作流名字，2-12 字，留空取目标前 12 字"))
                        .put("goal", str("要达成的目标，写清产出与验收标准"))
                        .put("steps", stepsProp)
                        .put("at", str("可选：定时时间，格式 08:30 或 2026-01-01 08:30"))
                        .put("daily", JSONObject().put("type", "boolean").put("description", "true 表示每天同一时间"))
                ).put("required", JSONArray().put("name").put("goal"))
            )
        )
        //Tools.A2A：把一部分活派给外部智能体；本机也能当服务端被别的 agent 调用
        arr.put(
            fn(
                Tools.A2A,
                "和别的 Tools.A2A 智能体（Tools.A2A 1.0）对话。action：\n" +
                    "- nodes：列本机保存过哪些对端（先调它拿名字）\n" +
                    "- card：看对端的 AgentCard（名字、能力、技能、接口地址）\n" +
                    "- send：把一条任务派给对端，等它出结果\n" +
                    "- get / cancel：用任务 id 查进度 / 让对端取消\n" +
                    "- tasks：列对端最近的任务\n" +
                    "node 可以是保存过的名字或 id，也可以直接写 http(s):// 地址。",
                toolIn(
                    JSONObject()
                        .put("action", str("nodes / card / send / get / cancel / tasks"))
                        .put("node", str("对端：保存过的名字或 id，或直接写 http(s)://地址"))
                        .put("text", str("send 时派过去的任务内容，写清楚要什么结果"))
                        .put("task", str("get / cancel 用的任务 id"))
                        .put("context", str("tasks 时按上下文筛选，可空")),
                    "action"
                )
            )
        )
        //局域网团队协作：扫到对面、配对、把活派给对面的模型
        arr.put(
            fn(
                Tools.LAN,
                "局域网团队协作：同一 Wi-Fi 下的两台设备互相派活。先 scan 找设备，再 pair 配对（要对方配对码），" +
                    "之后 assign 就能把一条任务交给对面的大模型跑，结果原样回来。action：\n" +
                    "- status（默认）：本机协作状态 + 已配对设备\n" +
                    "- serve：开/关本机接活服务（on、port）\n" +
                    "- scan：扫同一 Wi-Fi 下的设备，约 2 秒\n" +
                    "- pair：配对，要 peer（扫到的设备名 / 设备号 / IP）与 code（对方 6 位配对码）\n" +
                    "- peers：已配对设备，含在线标记与协作端口\n" +
                    "- unpair：解除配对，要 peer\n" +
                    "- card：看对面能干什么，要 peer\n" +
                    "- assign：把任务派给对面大模型并等结果，要 peer 与 task\n" +
                    "- tasks：列对面最近的任务；cancel：取消，要 peer 与 task/任务 id",
                toolIn(
                    JSONObject()
                        .put("action", str("status / serve / scan / pair / peers / unpair / card / assign / tasks / cancel"))
                        .put("peer", str("对面设备：名字、设备号或 IP"))
                        .put("code", str("pair 时对方的 6 位配对码"))
                        .put("task", str("assign 派过去的任务内容"))
                        .put("on", JSONObject().put("type", "boolean").put("description", "serve 时开关，默认 true"))
                        .put("port", num("serve 时监听端口，默认沿用设置里的协作端口")),
                    "action"
                )
            )
        )
        //米家：台账在本地，控制走「局域网优先、云端兜底」
        arr.put(
            fn(
                Tools.MI_DEVICES,
                "米家设备台账。action：\n" +
                    "- list（默认）：列出本地台账里的设备，带 did、名字、型号、IP、在线状态、有没有本地 token\n" +
                    "- sync：拉云端设备清单并进台账，需要已经登录米家账号\n" +
                    "- add：手工补一台设备（did + ip + token），没有云端也能局域网直连\n" +
                    "要控制设备前先调它拿 did；siid / piid / aiid 是设备自己的规格号。",
                toolIn(
                    JSONObject()
                        .put("action", str("list / sync / add，默认 list"))
                        .put("did", str("add 时的设备 did（纯数字）"))
                        .put("ip", str("add 时的设备局域网 IP"))
                        .put("token", str("add 时的设备 token，32 位十六进制"))
                        .put("name", str("add 时的设备名，可空"))
                )
            )
        )
        arr.put(
            fn(
                Tools.MI_SCAN,
                "扫一遍局域网，把应答的米家设备 IP 回填台账并标记在线。设备不在同一网段、或没开局域网协议时会扫不到。",
                toolIn(
                    JSONObject().put("timeout", num("扫多少毫秒，默认 2500，上限 10000"))
                )
            )
        )
        arr.put(
            fn(
                Tools.MI_READ,
                "读米家设备的一个属性（siid 服务 + piid 属性）。局域网连不上会自动回落云端，两边都不通才返回失败。",
                params("did" to str("设备 did"), "siid" to num("服务 id"), "piid" to num("属性 id"))
            )
        )
        arr.put(
            fn(
                Tools.MI_WRITE,
                "写米家设备的一个属性。value 会按内容自动转类型：true/false 转布尔、纯数字转整数、带小数点转小数，其余按字符串。" +
                    "这是会改变设备状态的写操作。",
                params(
                    "did" to str("设备 did"), "siid" to num("服务 id"), "piid" to num("属性 id"),
                    "value" to str("要写入的值")
                )
            )
        )
        arr.put(
            fn(
                Tools.MI_ACTION,
                "调米家设备的一个动作（siid 服务 + aiid 动作）。args 可选：写 JSON 数组或用逗号分隔。",
                toolIn(
                    JSONObject()
                        .put("did", str("设备 did"))
                        .put("siid", num("服务 id"))
                        .put("aiid", num("动作 id"))
                        .put("args", str("动作参数，JSON 数组或逗号分隔，可空")),
                    "did", "siid", "aiid"
                )
            )
        )
        //音乐：只解决「哪家优先、什么音质、登没登录、缓存多大」
        arr.put(
            fn(
                Tools.MUSIC_PLATS,
                "音乐平台设置。action：\n" +
                    "- list（默认）：看三个平台（网易云 / QQ 音乐 / 酷我）的启用状态、优先级、登录态与缓存占用\n" +
                    "- enable：把某个平台设为唯一启用（一次只让一家说话，免得同一条命令几家一起抢）\n" +
                    "- disable：关掉某个平台，至少留一个启用\n" +
                    "- quality：改音质档位（标准 128k / 高品 192k / 极高 320k / 无损）\n" +
                    "- up：把某个平台在优先级里往上挪一位",
                toolIn(
                    JSONObject()
                        .put("action", str("list / enable / disable / quality / up，默认 list"))
                        .put("plat", str("平台：netease / qq / kuwo，也可以写中文名"))
                        .put("quality", str("音质档位，也可以写 320 / 无损 这种简写")),
                    "action"
                )
            )
        )
        arr.put(
            fn(
                Tools.MUSIC_LOGIN,
                "音乐平台登录态。action：\n" +
                    "- status（默认）：看有没有登录\n" +
                    "- verify：拿已存的凭据真去查一次账号，能查出昵称才算真登录\n" +
                    "- set：把 cookie 存进加密库\n" +
                    "- clear：清掉凭据\n" +
                    "- sms：给手机号发验证码（只有网易云支持）\n" +
                    "- code：用手机号 + 验证码登录（只有网易云支持）\n" +
                    "cookie 只进加密库、不回显内容；验证码登录成功后凭据自动落库，不用再 set。",
                toolIn(
                    JSONObject()
                        .put("action", str("status / verify / set / clear / sms / code，默认 status"))
                        .put("plat", str("平台：netease / qq / kuwo"))
                        .put("cookie", str("set 时的 cookie 原文，只取第一行"))
                        .put("phone", str("sms / code 时的手机号"))
                        .put("code", str("code 时的短信验证码"))
                        .put("area", str("国家码，默认 86")),
                    "plat"
                )
            )
        )
        arr.put(
            fn(
                Tools.MUSIC_CACHE,
                "音乐缓存目录（baicode/music）。action：size 只看占用 / clear（默认）真删并回显释放了多少。",
                toolIn(JSONObject().put("action", str("size / clear，默认 clear")))
            )
        )
        //技能包：清单在系统提示里，每个函数直接注册成独立工具，不搞两级跳
        arr.put(
            fn(
                Tools.SKILL_READ,
                "取某个技能的完整用法（README）。第一次用一个技能前先调它，看清参数与示例再调具体函数。",
                params("name" to str("技能 id 或名字，见系统提示里的技能清单"))
            )
        )
        SkillPacks.funcs.forEach { f ->
            val props = JSONObject()
            f.params.forEach { (k, re) -> props.put(k, str(if (re.isBlank()) "字符串" else "需匹配：$re")) }
            arr.put(
                fn(
                    f.name,
                    "[" + f.skill + "] " + f.desc,
                    JSONObject().put("type", "object").put("properties", props)
                        .put("required", JSONArray(f.params.keys.toList()))
                )
            )
        }
        //Tools.JS 脚本注册的工具：schema 由脚本自己给，原样透传
        Js.tools.forEach { d ->
            val p = runCatching { JSONObject(d.parameters) }.getOrElse { JSONObject() }
            arr.put(fn(d.name, "[Tools.JS] " + d.description, p))
        }
        //Tools.MCP 能力：schema 是服务端给的 parameters 原文，原样透传给模型
        Mcp.tools.forEach { t ->
            val p = runCatching { JSONObject(t.schema) }.getOrElse { JSONObject() }
            arr.put(fn(t.full, "[Tools.MCP·" + t.server + "] " + t.desc, p))
        }
        //按当前模式裁掉不可用的工具：模型看不到定义就不会去试；顺便按名字去重，OpenAI 侧重名直接 400
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optJSONObject("function")?.optString("name").orEmpty()
            if (n.isEmpty()) continue
            if (!seen.add(n)) {
                Log.w("Tools", "工具定义重名，已丢弃后一条：$n")
                continue
            }
            if (Tools.allowed(n)) out.put(o)
        }
        return out
}

private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

private fun fn(name: String, desc: String, parameters: JSONObject): JSONObject =
    JSONObject().put("type", "function").put(
        "function",
        JSONObject().put("name", name).put("description", desc).put("parameters", parameters)
    )

private fun num(desc: String) = JSONObject().put("type", "integer").put("description", desc)

private fun toolIn(props: JSONObject, vararg required: String): JSONObject {
    val arr = JSONArray()
    required.forEach { arr.put(it) }
    return JSONObject().put("type", "object").put("properties", props).put("required", arr)
}

private fun params(vararg props: Pair<String, JSONObject>): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    props.forEach { (k, v) ->
        properties.put(k, v)
        required.put(k)
    }
    return JSONObject().put("type", "object").put("properties", properties).put("required", required)
}
