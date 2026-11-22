# FORK 说明（自用分支）

这是 `mubai0122/baicodex` 的个人分支，自己用、自己维护。

- **基线**：`v1.18.2-base`（上游 1.18.2 + 本地 CI 改动）。任何时候 `git checkout v1.18.2-base` 都能回到"能跑的原版"。
- **主分支**：`main` 永远保持"能编、能装、能跑"。所有大改在分支上做。

## 分支模型

| 分支 | 是什么 | 跟踪谁 |
|---|---|---|
| `main` | 我们自己的版本，永远可编可装 | `origin/main` |
| `vendor` | 上游原样镜像，只用来看和拉，一行不改 | `upstream/main`（`ff only`） |
| 功能分支 | 大改时开，验完再合进 main | — |

**同步上游**（冲突只会在 merge 那一步出现）：

```sh
git checkout vendor && git pull        # 只快进，不产生合并提交
git checkout main && git merge vendor
```

**看我们到底改了什么**：

```sh
git diff vendor..main --stat
```

**从上游挑单个修复**（不想整树同步时）：

```sh
git checkout main && git cherry-pick <sha>
```


## 本地改动清单

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `.github/workflows/build-apk.yml` | 新增 CI：装 NDK/CMake、拉第三方源码（quickjs / android_openssl / llama.cpp / bzip2）、ccache、出 APK | 手机上 NDK 是 x86_64，编不了 native |
| `app/build.gradle.kts` | `abiFilters` 支持 `-Pbai.abis=arm64-v8a` | CI 只编 64 位，省一半 native 时间 |
| `app/src/main/cpp/CMakeLists.txt` | 加 13 行 ccache 开关（`BAI_CCACHE` 环境变量） | 不改默认行为，只给 CI 用 |
| `signing/baicodex-ci.jks` | 固定签名密钥 | 签名不变才能覆盖安装，不用每次卸载 |
| `.gitignore` | 放行上面那个 keystore | — |

agent 层的结构改造（详见下方「agent 层结构改造」一节）：

| 文件 | 改了什么 |
|---|---|
| `agent/run/engine/TurnEngine.kt` | 循环内核：步循环、Key 轮换、工具批串行/并发、结果按序回灌，以及护栏 |
| `agent/run/engine/TurnHost.kt` | 内核向宿主要的全部能力，副作用留在 `Run` |
| `agent/tool/Tools.kt` | 只剩注册与路由：常量、模式过滤、参数校验、dispatch（553 行） |
| `agent/tool/ToolSpecs.kt` | 下发给模型的全部工具定义（964 行） |
| `agent/tool/{Mi,Music,Shell,Net,Flow,Collab,Skill,Search,Device,File,Ssh}Tools.kt` | 工具实现按域拆成 11 个文件 |
| `core/misc/Res.kt` | 带成败的结果类型：成败由产出层判定，不再从中文文案猜 |
| `app/src/test/.../TurnEngineTest.kt` | 9 条 JVM 单测 |

`ui/` 目录零改动。

## 签名

| 项 | 值 |
|---|---|
| 密钥库 | `signing/baicodex-ci.jks` |
| 别名 | `baicodex` |
| 口令 | `baicodex-ci`（store 与 key 相同） |
| 证书 | `CN=BaiCodex Personal, O=buanys, C=CN` |
| SHA-256 | `39:CD:70:2F:38:F2:D7:CB:75:7B:C4:98:64:27:44:BB:BF:33:7B:48:3F:2A:66:E5:83:89:33:2A:E0:7B:5B:58` |

> 仓库若要公开，先把这把钥匙换掉——keystore 和口令都在库里。

## 版本号

- 版本号跟上游走（`versionCode` / `versionName` 原样保留上游值），不另加后缀。
- 改版本号只影响"装上去看起来是哪个版本"，不影响升级：能不能覆盖安装只取决于签名一致。

## 上游 1.19+ 带来的两处变化

- **系统提示词正文移出仓库**：上游把提示词拆成 `seed.prompt.*` 种子，构建期由 `local.properties` 注入。
  CI 不写这些键，各段就落到 `PromptStore.kt` 里的默认文案——能用，但不是上游调过的那版。
  想要上游那版：往 `local.properties` 里补 `seed.prompt.*`，或在本机生成后再构建。
- **bz2 解压需要 bzip2 源码**：`CMakeLists.txt` 里新增了 `_third_party/bzip2` 静态库，
  CI 的 "Fetch third-party sources" 步骤已跟着加了 `BZIP2_SHA` 钉版本拉取。

## agent 层结构改造（不在上游）

上游把编排、上下文、压缩、权限、通知全塞在 `Agent.kt`（2249 行）和 `Tools.kt`（2545 行）里，
零测试。这一轮把循环内核抽出来并补上护栏，动手前先读 `~/baicodex-agent-layer.md`（评估）
和 `~/scream-to-baicodex.md`（对照与移植清单）。

| 文件 | 改了什么 |
|---|---|
| `agent/run/engine/TurnEngine.kt` | 循环内核：步循环、Key 轮换、工具批串行/并发、结果按序回灌，以及护栏 |
| `agent/run/engine/TurnHost.kt` | 内核向宿主要的全部能力，副作用一律留在 `Run` 里 |
| `agent/run/engine/TurnTypes.kt` | `ToolOut` / `Gate` / `AfterAnswer` / `Stage` |
| `Agent.kt` 的 `Run` | 实现 `TurnHost`；`runLoop` 只剩一行 `engine.run()` |
| `net/api/llm/LlmOpts.kt` | 一次请求的全部策略；`Cfg.opts(...)` 按本轮后端快照算好 |
| `LlmClient` | 不再回读 `Cfg`，也不再回退到 `Tools.definitions()`；`tools = null` 就是不给工具 |
| `app/src/test/.../TurnEngineTest.kt` | 9 条 JVM 单测，钉住收敛、拒绝、失败、Key 轮换、护栏 |
| `.github/workflows/build-apk.yml` | 加 `Unit tests` 步骤，跑在打包之前，红即停 |
| `agent/sub/SubAgent.kt` | SYS 硬约束、首轮零调用催办、重复调用 2/3/4、请求改走流式 |

护栏取值（来源 `~/scream-src/packages/agent-core/src/agent/turn/defaults.ts`，按手机端收窄）：

| 值 | 含义 |
|---|---|
| 40 | 单个用户消息内的工具轮数上限，跑满收口 |
| 6 / 10 | 连续工具失败：提醒换做法 / 强制收口 |
| 8 | 连续被用户拒绝：收口 |
| 3 / 5 / 8 | 同名同参重复调用：提醒 / 警告 / 强制停 |

本机跑单测（CI 之外）：

```
ANDROID_HOME=~/android-sdk ~/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle \
  :app:testDebugUnitTest -Pbai.abis=arm64-v8a
```

## 每次改动之后

本机编译回路（CI 之外更快）：

```
# 前提：local.properties 已指向本机 SDK；~/gradle-9.0.0 是从腾讯镜像解出来的 Gradle 9.0.0
#（services.gradle.org 在这边下载不动）；--init-script 把 Maven 全部换到阿里云（必须「清空再填」，
# 只追加的话 Gradle 还是会先试 dl.google.com 然后超时）
cd ~/baicodex
ANDROID_HOME=~/android-sdk ~/gradle-9.0.0/bin/gradle \
  --init-script ~/.baicodex-mirror.gradle \
  :app:compileDebugKotlin -Pbai.abis=arm64-v8a   # 增量约 2-8 秒
ANDROID_HOME=~/android-sdk ~/gradle-9.0.0/bin/gradle \
  --init-script ~/.baicodex-mirror.gradle \
  :app:testDebugUnitTest -Pbai.abis=arm64-v8a    # 单测，几秒
# 编 APK 还是要 CI：NDK 是 x86_64 的，本机是 aarch64，native 编不了
```

1. 推上去，等 CI 绿（约 5 分钟）。
2. 下载 APK，**覆盖安装**（不要卸载）。
3. 跑一遍冒烟清单：

- [ ] 能覆盖安装，不报"应用未安装"
- [ ] 冷启动不崩
- [ ] 发一条消息，能流式回完
- [ ] 让它读一个文件（工具调用正常）
- [ ] 派一次子智能体（2 条任务，能出合并结果）
- [ ] 进设置切一次外观，不闪退
- [ ] 杀进程重进，会话还在

## 回滚

```sh
git revert <commit>     # 撤某次改动
git reset --hard v1.18.2-base   # 回到基线（会丢本地改动，慎用）
```
