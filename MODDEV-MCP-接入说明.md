# AE2-Pattern-Disk 接入 ModDevMCP 完整结论文档

> 生成日期：2026-08-21
> 结论：skill-first 版 ModDevMCP 0.3 **无法通过任何公共仓库直接拉取**，唯一可行路径是**用官方源码本地构建**。本机 mavenLocal 的 0.3 是魔改版，与本项目不兼容，不可直接用。

---

## 一、核心结论（一句话）

**ModDevMCP skill-first 0.3 版（唯一匹配 MC 1.21.1 的版本）只存在于官方 GitHub master 源码，从未发布到 CurseForge / Modrinth / GitHub Release / Maven Central。要接入 AE2-Pattern-Disk（MC 1.21.1 + NeoForge 21.1.248 + Java 21），必须从官方源码本地构建。**

---

## 二、ModDevMCP 是什么

ModDevMCP 是一个面向 Minecraft NeoForge 模组调试的 **skill-first 服务模型**：

- 运行在游戏 `Mod` 内部的本地 HTTP 服务，仅绑定 loopback（默认端口 `47812`）
- **技能入口**：`moddev-usage`（必读），探测端点 `GET /api/v1/status`
- Agent（模型）通过 HTTP 请求驱动游戏：UI 检查/截图/操作、命令执行、世界管理、热重载等
- 本机已导出全部技能到 `C:\Users\星落\.moddev\skills\`（26 skill + 6 category）

能力分类：`status`（服务状态）、`ui`（界面检查/截图/操作）、`command`（MC 命令）、`input`（键鼠/剪贴板）、`world`（世界管理）、`hotswap`（热重载）。

---

## 三、版本可用性矩阵（关键证据）

| 版本 | 架构 | 目标 MC | 目标 NeoForge | Java | 获取渠道 | 与本项目兼容 |
|------|------|---------|---------------|------|----------|:---:|
| **0.3（skill-first）** | ✅ skill-first | 1.21.1 | 21.1.219 | 21 | **仅源码本地构建** | ✅ 匹配 |
| **0.3（本机 mavenLocal 魔改版）** | skill-first | 1.21.x | **26.1.2（alpha）** | **25** | 本机已装 | ❌ 不匹配 |
| **0.1.6**（Maven Central） | ❌ 旧 JSON-RPC | 1.21.1 | 21.1.219 | 17 | Maven Central 可拉 | 部分（旧架构） |

### 魔改版与官方版的差异（致命）

本机 `~/.m2/repository/dev/vfyjxf/moddevmcp/0.3/` 的 jar 检查结果：

- **Java 字节码版本 = 0x45（Java 25）** → 本项目 Java 21 JVM 无法加载
- `META-INF/neoforge.mods.toml` 声明依赖 NeoForge `[26.1.2.74,)` + MC `[26.1.2,26.2)` → 与项目 NeoForge 21.1.248 完全不同版本线
- 而 NeoForge **26.1.x 在官方 maven 只有 alpha 快照，无稳定 26.1.2**

**结论：魔改版是为某个不同的 26.1 项目构建的，不能用于 AE2-Pattern-Disk。**

---

## 四、官方仓库与"curse.maven"澄清

官方仓库 `C:\Users\星落\source\repos\ModDevMCP`（master @ `4a99e2b`）：

- `gradle.properties`：`minecraft_version=1.21.1`、`neo_version=21.1.219`、`mod_version=0.3`
- `Mod/build.gradle:21`：`toolchain.languageVersion = JavaLanguageVersion.of(21)`
- 该仓库 `build.gradle` 里确有 `maven { url = "https://cursemaven.com" }`，但那是**官方仓库消费其他 mod 依赖用的，不是发布通道**
- **ModDevMCP 从未发布到 CurseForge**（`api.cfwidget.com` 返回 404，GitHub code search `curse.maven:moddevmcp` 0 结果）

### 检索证据（排除 curse.maven / 其它公共仓库）

| 验证途径 | 结果 |
|----------|------|
| CurseForge / cfwidget | 项目不存在（404） |
| Modrinth API | total_hits: 0 |
| GitHub Releases | 空列表 |
| Maven Central | 仅 0.1 / 0.1.1 / 0.1.2 / 0.1.3 / 0.1.6（全为旧架构，无 0.3） |
| GitHub code search | 无任何 `curse.maven:moddevmcp` 引用 |
| 唯一已知消费者 LDLib2 | 用本地构建依赖（`localImplementation` + 本地构建 0.3） |

---

## 五、官方没有现成 jar

已确认：
- 官方仓库内无任何已构建 jar（只有 gradle wrapper jar）
- 无 GitHub Release、无 CI workflow 构建产物
- 本机 gradle 缓存无官方版 moddevmcp

**官方 0.3 从未产出可下载的 jar 工件。**

---

## 六、接入方案（推荐：本地构建）

与官方唯一已知消费者 LDLib2（GTCEu 团队）相同做法：

### 1. 构建官方 0.3 到 mavenLocal

```bash
cd C:/Users/星落/source/repos/ModDevMCP
./gradlew :Mod:publishToMavenLocal
```

- 官方源码 target = MC 1.21.1 + NeoForge 21.1.219 + Java 21，与本项目完全匹配
- 会产出正确的 `dev.vfyjxf:moddevmcp:0.3`（Java 21 字节码 + NeoForge 21.1）
- ⚠️ **先处理魔改版冲突**：本机 mavenLocal 已有同坐标 0.3（魔改版），需先隔离/删除 `~/.m2/repository/dev/vfyjxf/moddevmcp/0.3/`，否则官方构建产物会与之冲突

### 2. 在 AE2-Pattern-Disk/build.gradle 接入

```groovy
// repositories 已含 mavenLocal()（无需新增）

dependencies {
    implementation("dev.vfyjxf:moddevmcp:0.3") {
        transitive = false
    }
}

neoForge {
    runs {
        client {
            systemProperty 'moddevmcp.project.root', project.projectDir.absolutePath
        }
        // server 若需，同样加
    }
}
```

> 已按官方 TestMod 模板核实：`transitive = false`；moddevmcp 已 jarJar 内嵌唯一依赖 `net.lenni0451:Reflect:1.6.2`；无需在 `neoforge.mods.toml` 声明依赖。

### 3. 运行游戏验证服务

```bash
./gradlew runClient
# 游戏加载后，服务应监听 47812：
curl http://127.0.0.1:47812/api/v1/status
# 期望返回 serviceReady=true
```

### 4. Agent 调用链路（skill-first）

```bash
# 1. 探测状态
curl http://127.0.0.1:47812/api/v1/status
# 2. 发现能力
curl http://127.0.0.1:47812/api/v1/categories
curl http://127.0.0.1:47812/api/v1/skills
# 3. 读技能细节
curl http://127.0.0.1:47812/api/v1/skills/{skillId}/markdown
# 4. 执行操作
curl -X POST http://127.0.0.1:47812/api/v1/requests \
  -H "Content-Type: application/json" \
  -d '{"operationId":"status.get","input":{}}'
```

---

## 七、风险与注意

- **魔改版冲突**：必须处理 mavenLocal 里 Java 25/NeoForge 26.1 的 0.3 魔改版，否则构建/运行会拉到错误版本
- **构建耗时**：官方仓库首次构建需下载 Gradle 9.2.1 及 NeoForge 21.1.219 依赖，可能耗时数分钟
- **NeoForge 版本线**：官方源码是 21.1.219，本项目是 21.1.248，同线兼容，无问题
- **本项目运行稳定性**：`run/crash-reports/` 有历史 JVM 崩溃记录，接入后需关注游戏运行稳定性

---

## 八、当前状态

- ✅ `build.gradle` 已回滚至原始状态（此前试探性加的 moddevmcp 依赖已移除）
- ✅ 官方源码已在本机（`C:\Users\星落\source\repos\ModDevMCP`，master @ 4a99e2b）
- ✅ 本机 mavenLocal 的 0.3 魔改版已定位（待处理）
- ⏳ 未执行本地构建、未修改接入（等待你决策）
