# AGENTS.md

本仓库的**文档地图 + 动手前必读的硬约定**。给进入这个仓库的 AI 代理（pi / Claude Code / Cursor…），也给人看。
**先读本页再动手**：这里只做索引，细节都在被指向的文档里，本页不重复。

## 这是什么

AE2（应用能源 2）的附属模组 `ae2_pattern_disk`：样板磁盘、磁盘驱动的样板供应器与转存器、两种装配室、
样板磁盘编码终端 / 管理终端、元件管理终端。

**基线：Minecraft 1.21.1 · NeoForge 21.1.241 · AE2 19.2.18 · Java 21**
权威来源是 `gradle/libs.versions.toml` 与 `gradle.properties`，这一行只是速览。

## 一、规则类文档（进 git，改代码前必读）

| 文档 | 什么时候读 |
|---|---|
| `docs/ARCHITECTURE.md` | 改任何代码前。分层、目录职责、`integration/` 的红线 |
| `docs/ENCODING_MODES.md` | 碰编码终端档位时：档位状态散在菜单 / logic / 屏幕 / 面板 / 样式 / 模型六处 |
| `docs/CHANGELOG_STYLE.md` | 改 `CHANGELOG.md` 前：格式被 CI 硬约束，`## [` 是段落边界 |
| `docs/TODO.md` | 想知道「这事做没做、为什么没做」时：含已知欠账与明确「有意不做」的条目 |
| `CHANGELOG.md` | 发行说明（版本段会被 CI 取为发布文案）；**改格式前先读 `docs/CHANGELOG_STYLE.md`** |
| `docs/UPSTREAM_API_REQUESTS.md` | 要向上游（NEO ECO / ECO Prototype）提请求或跟进时 |
| `README.md` | 玩法自述（英文，商店页与 GitHub 首页） |
| `docs/PLATFORM.md` · `docs/PLATFORM_MCMOD.md` | 对外平台说明（英文 / MCMOD 中文） |

## 二、信息类文档（**本机本地，已 gitignore**，不随仓库分发）

| 文档 | 内容 |
|---|---|
| `docs/LOCAL_DEPS.md` | 本机路径索引：`libs/*.jar` 各从哪个仓库构建、兄弟仓库快捷方式指向哪、gradle 缓存在哪 |
| `docs/LOCAL_CLONES.md` | `D:\Temp` 下 19 个上游源码浅克隆的分支基线（含「为什么选这条分支」） |
| `docs/COMPATIBILITY.md` | 给视频创作者的兼容清单（交付件，不入库） |

> 换机器：两份 `LOCAL_*` 按实际路径改即可，构建脚本不受影响——真正的约定写在
> `build.gradle` / `settings.gradle` 的路径注释里，冲突时以构建脚本为准。

## 三、动手前必须知道的硬约定

- **语言**：注释、`docs/` 与 `CHANGELOG.md` 一律中文；**对外英文文案（`README.md`、`docs/PLATFORM.md`）**、代码标识符与游戏内英文文案除外。
- **集成红线**：`integration/<邻居>` 只写那个邻居的适配；**邻居的类型名不得漏进总是加载的类**——
  缺那个模组时会在加载阶段炸。
- **「名字即协议」**：`registerClientAction("...")` 的动作名与 `@GuiSync(n)` 的注解值靠客户端/服务端配对，
  不能改、也不能被第二个字段复用。
- **`libs/*.jar` 是本地件**（被 gitignore）：缺了构建会失败或回退到不兼容发布版，怎么凑齐见 `docs/LOCAL_DEPS.md`。
- **没有测试代码**：只有 `main` 与 `client` 两个源集有内容，验证靠构建 + 实机（`runClient`）。

## 四、常用命令

```bash
./gradlew build              # 全量构建
./gradlew compileJava        # 只编 main（服务端/通用）
./gradlew compileClientJava  # 只编 client
./gradlew runClient          # 实机验证（开发运行期配方查看器默认 JEI）
./gradlew runServer
./gradlew runData            # 数据生成
```

## 五、读上游源码之前

- 位置与分支见 `docs/LOCAL_CLONES.md`。**先确认分支**：好几个仓的 `main` 不在 1.21.1 线上
  （AE2WTLib 的 `main` 是 MC 1.21.9+，AdvancedAE 的 `main` 是 MC 26.1.2）。
- **别信 gradle 缓存里那份 AE2 sources jar**：`appliedenergistics2-forge-15.0.18-sources.jar` 是
  **1.20.1 Forge**，而本项目用 **AE2 19.2.x（1.21.1 NeoForge）**，读错版本会得出「某方法有 / 没有」这类反向结论。
