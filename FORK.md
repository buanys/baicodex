# FORK 说明（自用分支）

这是 `mubai0122/baicodex` 的个人分支，自己用、自己维护。

- **基线**：`v1.18.2-base`（上游 1.18.2 + 本地 CI 改动）。任何时候 `git checkout v1.18.2-base` 都能回到"能跑的原版"。
- **上游**：`upstream` remote 只作参考，不再自动跟。需要某个修复时手动 `git cherry-pick <sha>`，不做整树 merge。
- **主分支**：`main` 永远保持"能编、能装、能跑"。所有大改在分支上做。

## 本地改动清单

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `.github/workflows/build-apk.yml` | 新增 CI：装 NDK/CMake、拉第三方源码、ccache、出 APK | 手机上 NDK 是 x86_64，编不了 native |
| `app/build.gradle.kts` | `abiFilters` 支持 `-Pbai.abis=arm64-v8a` | CI 只编 64 位，省一半 native 时间 |
| `app/src/main/cpp/CMakeLists.txt` | 加 13 行 ccache 开关（`BAI_CCACHE` 环境变量） | 不改默认行为，只给 CI 用 |
| `signing/baicodex-ci.jks` | 固定签名密钥 | 签名不变才能覆盖安装，不用每次卸载 |
| `.gitignore` | 放行上面那个 keystore | — |

除此之外与上游一致，`ui/` 目录零改动。

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

- 上游号 + 自用后缀：`versionName = "1.18.2-fork.1"`，`versionCode` 每次 +1。
- 改版本号只影响"装上去看起来是哪个版本"，不影响升级：能不能覆盖安装只取决于签名一致。

## 每次改动之后

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
