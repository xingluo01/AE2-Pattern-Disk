# 发版手册

从打 tag 到三平台发布的操作与踩坑记录。只记事实与操作，不放待办（待办在 `TODO.md`）。更新日志的写法见 `CHANGELOG_STYLE.md`。

## 一、发版流程

1. 把 `CHANGELOG_UNRELEASED.md` 里 `## [未发布]` 整段搬到 `CHANGELOG.md` 顶部，标题改成 `## [<版本>] - <YYYY-MM-DD>`（细则见 `CHANGELOG_STYLE.md` §一）。
2. `gradle.properties` 的 `mod_version` 改成同一个版本号（本地构建产物用它；CI 用 tag 覆盖）。
3. `git tag vX.Y.Z && git push origin vX.Y.Z`。
4. CI（`.github/workflows/release.yml`）依次做：解析 tag 版本 → JDK 21 → 构建 → Modrinth → CurseForge → GitHub Release。三个平台全自动，无需手工上传。
5. 发布后按第五节核对。

`build.gradle` 接入 `com.modrinth.minotaur`；`build.gradle` 的 `debugMode = true` 可用于干跑 Modrinth（首次建议）。中断后重跑同一 tag 会失败（Modrinth 版本号已存在、CurseForge 不去重），优先改版本号重发。

CI 未就绪时的本地后备路径：`MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=ae2-pattern-disk ./gradlew modrinth`。

## 二、仓库变量与密钥

| 类型 | 名称 |
|---|---|
| vars | `MODRINTH_PROJECT_ID`、`CURSEFORGE_PROJECT_ID` |
| secrets | `MODRINTH_TOKEN`（PAT，勾 `VERSION_CREATE` + `VERSION_WRITE` + `PROJECT_WRITE`）、`CURSEFORGE_TOKEN` |

## 三、Modrinth

- 项目 `ae2-pattern-disk`，id `rtvtr6bT`；审核通过前对公开 API 返回 404，属预期（draft 不可见）。
- 依赖由 `build.gradle` 的 `modrinth { dependencies { required.project … } }` 声明，AE2 = `ae2`（id `XxWD5pD3`）、GuideME = `guideme`（id `Ck4E7v7R`）。

## 四、CurseForge

**双 API 混用，两者不通用。** CurseForge 项目数字 id 是 `1698612`（上传接口只认数字 id，见下）。

- **上传**走传统接口 `POST https://minecraft.curseforge.com/api/projects/{数字id}/upload-file`，header `X-Api-Token`：
  - token 是**网站账户 token**（UUID 形式，`curseforge.com/account/api-tokens` 生成），不是 console 的 API key；
  - 路径只认**数字 project id**，用 slug 会 302 到错误页；
  - `gameVersions` 必须是**数字 id**，且只在传统接口 `GET /api/game/versions` 里有：`1.21.1=11779`、`NeoForge=10150`、`Client=9638`、`Server=9639`。少环境组会报 `must select at least one version from the environment group of versions`；Eternal 那边的 1.21.1 是 89 / 12735 等，两套编号不通用，传错会被判 invalid dependency。
- **元数据查询**走 Eternal API `https://api.curseforge.com/v1/...`，header `x-api-key`：key 是 **console 的 API key**（`$2a$10$…` bcrypt 串，`console.curseforge.com` 生成）。拿它去上传会报 `API token is malformed`。
- **CI 用的 Action**（`itsmeow/curseforge-upload`）`game_versions` 接受名称/slug 但只查表转 id，**不自动补环境组**、**匹配不到会静默丢弃** → `release.yml` 里直接写数字 id。
- `relations:` 让 CF 页标出前置（该 Action 只发 slug）：`applied-energistics-2:requiredDependency,applied-energistics-2-wireless-terminals:requiredDependency`。第二条是 AE2WTLib（必需前置），slug 用**现代**那份 `applied-energistics-2-wireless-terminals`（Mari_023，CF 项目 459929），不是老的 `ae2wtlib`（p455w0rd，只到 1.12.2）。
- 新项目首个文件、以及每个新文件都要**人工审核**：`isAvailable=False`、`latestFiles` 暂为空属正常。

## 五、发布后核对

- CI 日志里有 `changelog source: CHANGELOG.md (N chars)`，且发布正文首行是小节名（`### 新增` 一类）。若是 `commit log` 或首行是 `- 主题 (sha)`，说明没取到版本段（`CHANGELOG_STYLE.md` §一）。
- `fail_on_unmatched_files: true` 保护：产物路径写错会硬失败，而不是生成没有附件的 release。
- Modrinth `GET /v2/version/{id}` 的 `dependencies` 里应有 `pNabrMMw` + `required`；CF 文件页 Relations 应出现两条前置。
- 网页侧人工项（API 改不了）：Client/Server 环境标记（现为 unknown）、gallery 截图、项目正文里的授权表述。（Dependencies 已由 `relations:` 自动声明，不在此列。）

## 六、依赖供给（已无私有 jar）

2026-10-26 起构建不再需要任何私有 jar：`release.yml` 的 fetch 步骤与 `MODDEVMCP_JAR_URL` / `EAEPLUS_JAR_URL` 两个仓库变量都已停用。

- `dev.vfyjxf:moddevmcp` 已从构建移除：`localRuntime`、`compileOnly files('libs/moddevmcp-0.3.jar')` 与 `settings.gradle` 的 composite-build 替换一并删掉。
- ExtendedAE Plus 改用 CurseForge 发布件（`curse.maven:extendedae-plus-1337639:8998231`，1.6.3），已带终端兼容层接口。
- NEO ECO 自 2026-10-08 起走 CurseForge 发布件（`curse.maven:neo-eco-ae-extension-1460639`），不再走这条供给。

`libs/` 下的 jar 现在只服务本地开发运行期：AECS 用 `localRuntime files('libs/ae2cs-<version>.jar')` 装进 dev 客户端（仅运行期；`files()` 对缺失文件静默跳过，CI 的 `compileJava` 不读它）。release `deps-v1` 上的旧件仅作历史留档，不再被任何步骤下载。

该 release 的 tag 落在 `v0.2.1^` 上，所以 changelog 步骤用 `--match 'v[0-9]*'` 避开它被 `git describe` 当成上一个版本、把发布说明截成一行。

## 七、CI action 版本

Node20 于 **2026-09-23** 从 runner 移除，四个声明 node20 的 action 已全部升到 node24：`actions/checkout@v7`、`actions/setup-java@v6`、`gradle/actions/setup-gradle@v6`（加 `cache-provider: basic`）、`softprops/action-gh-release@v3`。

- 时间线：runner 自 **2026-06-16** 起默认跑 Node24（node20 声明被自动迁移），**2026-09-23** 才移除 Node20 ⇒ `itsmeow` 在 Node24 下早已实测可用，9-23 之后的风险只是声明过期。

- `cache-provider: basic` 是许可/兼容考虑：v6 默认的专有缓存组件有 ToU 问题。它**没有实际缓存收益**——v6 的 `cache-read-only` 在非默认分支下恒为 true，而本仓库只有 tag 触发的 workflow。
- `itsmeow/curseforge-upload@v3.1.2` 仍是 node20，上游最后提交 2024-04、无更高版本可升。发版时会输出 `Node.js 20 is deprecated … forced to run on Node.js 24` 警告，**上传功能正常**。
  预案（推荐序）：① fork 后改 `runs.using: node24` 并 pin commit SHA（上游自 2024-04 零活动）；② 自写 curl 直连 CF 传统上传接口（最彻底解耦，接口与数字 id 已在用）；③ 换 `Kir-Antipov/mc-publish`（**用前必须核凭据类型**：它走 Eternal API 的 `x-api-key`，与现在的站点 UUID token 不是同一种，另需核版本参数语义）；④ 给上游提只改 `runs.using: node24` 的 PR（礼节性，合并概率极低）。

## 八、已知踩坑

- **Gradle 配置缓存**：minotaur 的 `:modrinth` 任务在配置缓存下报 `invocation of 'Task.project' at execution time is unsupported`（`Configuration cache entry discarded`），但 BUILD SUCCESSFUL、发布正常。若日后 Gradle 收紧，可加 `--no-configuration-cache` 或升级 minotaur。
- **`-Dorg.gradle.java.home`**：某次临时验证里它未生效（`--version` 通过、`tasks` 报 `invalid org.gradle.java.home: C:/Java/jdk-21`），改用 `sed` 直接替换 `gradle.properties` 可通。但 `release.yml` 现用的 `-D` 写法已被真实发版证明有效，**不要主动改**；只有某天出现 `invalid org.gradle.java.home` 时才切换。
- **从默认分支触发的 workflow 与 tag 发版不同**：前者 `cache-read-only=false`（可写缓存），后者 `true`（只读）。
