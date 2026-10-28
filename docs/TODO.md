# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | ⏸ 暂缓
> 优先级：P1 最高 | P2 | P3
>
> 本表只放**还没做完的事**。已完成条目不留档（看 `CHANGELOG.md` 与 git 历史）；已放弃与有意不做的取舍去找对应的提交与代码注释，
> 本表不再列；发版流程见 `RELEASE.md`；工程约束（版本基线、只用公共 API、美术资源约定）见 `ARCHITECTURE.md`；
> 上游请求见 `UPSTREAM_API_REQUESTS.md`。

## 一、未开始

### N. ECO 压缩链 API 发版后收口 API 层 — P2 ⬜
- 背景：元件管理终端的 ECO 大宗元件压缩标记已做成两层——`CellBulkCutoffCompat` 中立接口，API 适配器在 `src/ecoapi/java`（仅 `-PecoApiJar` 时进源集），反射适配器在 `integration/neoecoae/EcoBulkCellsCompat`。上游 PR #141 已合并（2026-10-27，merge `5c8c9f2e`），最新发行版 21.2.11 还不含它。
- ⬜ 带这条 API 的 ECO 版本发布后：把 API 适配器移进 `src/main/java`，把 ECO 依赖升到该版本，删掉 `ecoApiJar` 开关；反射适配器保留给旧版 ECO（`mods.toml` 地板仍是 `[21.2.1,)`）。
- ⬜ 本地快照 jar 停在合并前的 `8d0b028e`，上游另补了 `7919d10b`（截断查询改走库存投影）：按 `origin/main` 重打一份再验。
- ⬜ 实机把 ECO 大宗元件放进元件编码槽，验三枚按钮（截断调级／切换压缩选择／压缩统一管理）的显隐与落点、调级写回标记槽、不可压缩链停在 1 级且左右键无反应、统一管理按「有库存留在原矩阵 → 无库存优先归编码槽那张 → 装不下的回落网络第一顺位」压实、标记满 25 条时大宗拓展卡取不出。名单与库存现在都读元件副本（上游查生效截断会顺带落盘），所以还要确认截断层显示的仍是这条链真正生效的那一级。

### N. 上游 API 接入的剩余项 — P3 ⬜
- 背景：已接入 ECO `21.2.6` 的 `api.storage.IECOStoragePriorityHost` 与青春版 `1.3.6` 的 `setCellStack`，本模组侧走「接口优先、反射兜底」（`integration/neoecoae/NeoECOStoragePriority.java`）。
- ⬜ `ICellHost` 的类型化适配：上游已把 `ICellHost` 提到 `api.storage`，可新增 `integration/neoecoae/NeoECOCellHosts.java` 做编译期类型化，让 `CellDriveScanner` 只调中立 SPI、不再按名字探；`setCellStack(null)` / `EMPTY` / `removeCell()` 三路试探可收敛成一次文档化调用。
  - **硬约束**：SPI 的实现注册必须在「ECO 在场」的类里（`NeoECOIntegration`）。`CellDriveScanner` 是无条件加载的 common 代码，它若直接 `instanceof` 上游接口，缺 ECO 时会 `NoClassDefFoundError`。
  - 青春版只能走 SPI（纯运行时可选，连 compileOnly 都没有）。
- ⬜ 提取原因：本模组已有自己的 `ExtractResult`（4 值 `OK` / `EMPTY` / `LOCKED` / `REFUSED`，后两者出声、`EMPTY` 静默）；剩下的是「改用上游枚举给出更细的原因」（上游侧条件已由 ECO #119 满足，见 `UPSTREAM_API_REQUESTS.md` 的提交记录）。
- 维护：`UPSTREAM_API_REQUESTS.md` 的提交记录表随进展更新（UTC 时间戳）。

## 二、暂缓

### 8. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- EAE 矩阵 Pattern 槽硬过滤，磁盘配方只能间接进矩阵（高难度 IAECluster），暂缓。

### 9. 完整紧凑编码（改 `PatternDiskContents` 数据格式）— ⏸ 高风险
- 破坏存档兼容，保留后续专轮 + 迁移。

## 三、归档索引
- 已完成条目与实现细节：`CHANGELOG.md`、git 历史、`ARCHITECTURE.md` 的模块说明。
- 上游请求与状态（ECO / 青春版 / EAE+）：`UPSTREAM_API_REQUESTS.md`。
- 发版流程与踩坑：`RELEASE.md`。
