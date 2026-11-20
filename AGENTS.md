# AGENTS.md — baicodex 自用分支

上游：`mubai0122/baicodex`（AGPL-3.0）。本分支**只做构建层改动**，业务代码保持上游原样。
分支模型与上游同步流程见 `FORK.md`。

---

## 三层架构（动手前先看这张表）

| 层 | 语言 | 位置 | 职责 |
|---|---|---|---|
| **编排** | Kotlin（约 62k 行） | `app/src/main/kotlin/b/ai/` | Agent 循环、工具表、权限确认、Compose UI、模型供应商接入 |
| **能力** | C++（约 5.1k 行） | `app/src/main/cpp/` | llama.cpp 本地推理、QuickJS 沙箱、HTTP/curl、OpenSSL、高斯模糊、米家局域网协议、DSL 与文本编辑 |
| **内核** | Rust（约 5.4k 行） | `app/src/main/rust/src/` | KV/文件存储、记忆模型与排序、设备信息、计算、密钥与脱敏、启动自检 |

三者编译进同一个 `libbaicodex.so`，Kotlin 通过 **13 个门面类、74 个 `external fun`** 调用。

## 边界规则

1. **Agent 的决策、流程、提示词、工具注册，只写在 Kotlin。** 不要往 native 里塞业务逻辑。
2. **必须依赖 C 库的能力走 C++**：推理（llama.cpp）、脚本（QuickJS）、HTTP（curl/OpenSSL）、图像、米家协议。
3. **纯逻辑、纯计算、要零拷贝的走 Rust**：存储、记忆、脱敏、加密、设备信息、自检。
4. **新增一个 native 能力要同时改三处**：实现（C++/Rust）→ JNI 导出（`cpp/jni/*_jni.cpp` 或 `rust/src/ffi/*.rs`）→ Kotlin 门面 `external fun` 声明，缺一处就是运行时 UnsatisfiedLinkError。
5. **不要为小功能引入新的 native 依赖。** 能用 Kotlin 或现有门面组合出来的，就别改 CMake。
6. **`ui/` 目录默认不动**，除非用户明确要求改界面。

## JNI 门面索引

| 门面 | 函数数 | 底层 |
|---|---:|---|
| `Calc.kt` | 15 | Rust compute |
| `BxKernel.kt` | 15 | Rust 内核（store/memory/device/selfcheck） |
| `Llama.kt` | 10 | C++ llama.cpp |
| `Net.kt` | 5 | C++ http/curl |
| `MiCrypto.kt` | 5 | C++ 米家加密 |
| `Js.kt` | 5 | C++ QuickJS |
| `Guard.kt` | 5 | C++/Rust 运行期守卫 |
| `Vault.kt` | 3 | Rust 存储 |
| `MiLanNative.kt` | 3 | C++ 米家局域网 |
| `Mask.kt` | 3 | Rust/C++ 脱敏 |
| `Dsl.kt` | 3 | C++ DSL |
| `TextEdit.kt` | 1 | C++ 编辑 |
| `Blur.kt` | 1 | C++ 图像 |

## 关键文件

- `app/src/main/kotlin/b/ai/agent/run/Agent.kt` — 主循环、子智能体调度、确认弹窗
- `app/src/main/kotlin/b/ai/agent/tool/Tools.kt` — 工具表（注册、权限、native 选路）
- `app/src/main/kotlin/b/ai/agent/sub/SubAgent.kt` — 子智能体（隔离消息表、并发 3、轮次上限）
- `app/src/main/kotlin/b/ai/agent/flow/FlowRunner.kt` — 工作流（顺序、重试、后台确认超时）
- `app/src/main/rust/src/core/` — `base` / `feature` / `security`
- `app/src/main/cpp/core/feature/llm/llm.cpp` — 本地推理入口

## 构建

本机编不了 native（NDK 只有 x86_64 主机工具链），一律走 CI：

```sh
git push origin main        # 自动构建，约 5 分钟
```

产物在 Actions 的 artifact 里，只含 `app-arm64-v8a-debug.apk`。
签名固定为 `signing/baicodex-ci.jks`，可直接覆盖安装。
