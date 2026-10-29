# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | ⏸ 暂缓
> 优先级：P1 最高 | P2 | P3
>
> 本表只放**还没做完的事**。已完成条目不留档（看 `CHANGELOG.md` 与 git 历史）；已放弃与有意不做的取舍去找对应的提交与代码注释，
> 本表不再列；发版流程见 `RELEASE.md`；工程约束（版本基线、只用公共 API、美术资源约定）见 `ARCHITECTURE.md`；
> 上游请求见 `UPSTREAM_API_REQUESTS.md`。

## 一、未开始

### N. ECO 数字奇点物品存储矩阵的大宗按钮：实机确认 — P2 ⬜
- 背景：反射层与 `-PecoApiJar` 开关已删，大宗压缩只剩 `integration/neoecoae/EcoBulkCells` 一条编译期路径；`mods.toml` 的 `neoecoae` 地板抬到 `[21.2.12,)`。判定只看 `IECOBulkMarkableCellItem`（物品面）与 `IECOBulkDisplayCell`（实例面），新载体按上游类型树应当自动命中，但没跑过机。
- 事实基线（`javap` 与 jar 内指南实证，别再按发版说明的口数写）：
  - 数字奇点物品存储矩阵要 **Bigger AE2** 在场才注册：门是 `integration.biggerae2.BiggerAE2Integration` 类上的 `@Integration("bigger_ae2")`（ECO 的 IntegrationManager 只在该模组装载时调起它），不是逐通道的 `requiredMod`——ITEM 与 FLUID 那两条是 null。
  - 旧 MEGA 大宗矩阵保留读取兼容并显示 `tooltip.neoecoae.bulk.moved` / `bulk.deprecated`，组装与拆解配方已撤。
  - 上游新增硬语义：**同一网络内矩阵共享已标记条目，同一压缩链不得在其他矩阵重复标记**。
- ⬜ 实机确认三枚按钮的**三档显隐**：编码槽不是大宗元件→全隐；是大宗元件但没有可压缩链（含出厂空盘）→只留「压缩统一管理」；有可压缩链→三枚都在，截断物钮画选中链那一级。再验 ① 数字奇点物品存储矩阵 ② 旧 MEGA 大宗矩阵 ③ 青春版 L1 小宗仓 各自落在哪一档，以及调级写回标记槽。青春版 1.4.1→1.4.3 没有任何 `IECOBulk*` 实现，L1 小宗仓能不能算压缩站点只能实测。
- ⬜ 聚合归属的实测：**归属只看优先级**——编码槽那张最先，其后按控制器存储优先级降序（`IECOStoragePriorityHost` 读不到排最后），同级按扫描顺序；每条链归顺序里第一个还有格位的那台，同链其余标记被去重，**库存本身不动**（低优先级矩阵上的库存会变成无标记库存）。摆两台不同优先级的 ECO 主机验：高优先级那台应当收满，低优先级只接溢出；再看日志 `[cell-mgmt] bulk consolidation: … target order:` 里 `p?` 是否出现（`p?` = 优先级没读到，顺序会退化成扫描顺序）。
- ⬜ **空的高优先级矩阵必须能接收**：曾在 `siteOf` 里加过「网络里的空盘不参与聚合」的守卫，结果标记只能在已有标记的矩阵之间去重、搬不进空矩阵——已删除。实测：给一台矩阵设较高优先级但留空（0 标记），按一次统一管理，链应当**搬过去**而不是原地去重。
- ⬜ 优先级取两级：`IECOStorageCell.getStoragePriority()`（矩阵自身，ECO 元件界面里调的那个）优先，其次宿主控制器 `IECOStoragePriorityHost`，最后扫描顺序；日志每站点打 `/q<自身>` `/p<宿主>`，`p?` 表示控制器优先级没读到。
- ⬜ 宿主类型与优先级来源：ECO 主机（`storage_system_*`）取控制器 `IECOStoragePriorityHost`；AE2 驱动器与 **ExtendedAE 的 `ex_drive`**（`TileExDrive extends DriveBlockEntity`，同一条枚举）取驱动器自己的 `getPriority()`——两者都是 AE2 存储优先级，同尺度可比。存档实证：`ae2:drive` 里躺着 2 处 `neoecoae:eco_mega_long_bulk_cell`，所以「盘放进普通 ME 驱动器」不是假设。聚合日志每个站点带 `/eco` 或 `/drive`；非 ECO 主机里的可标记元件会打一次性 WARN。待定：这类盘要不要只读不写（排除出聚合目标）。
- ⬜ 显示口径变更要现场定性：生效截断现在取 `getCompressionCutoff`（上游账本优先），不再是配置槽里的标记物；两者分歧时首轮「压缩统一管理」可能把截断层写回标记槽，那是一次静默归一，要看回执条数对不对。
- ⬜ 归并边界：`EcoBulkCells.rewrite` 在名单超出格数时整份不写，而 `CellManagementTermMenu` 的 `room` 会把主矩阵的接收量压到 `capacity()`、剩下的回落到原站点，所以 25 格 → 5 格不会整份拒绝；真正会「什么都没发生且无回执」的是 `touched == 0`（没有可搬的链，或链全部已有库存）。实测时把这两种情形分开看，必要时给一句「没有需要整理的标记」回执。
- ⬜ 抬地板的现场形状：装 21.2.1–21.2.11 时应当是**整包停在加载阶段**，报错屏点名「Mod ae2_pattern_disk only supports neoecoae [21.2.12,)」（`ModSorter.sort` 命中已安装可选依赖越界后只用 systemMods 构加载列表）。实测一次再对外说。
- ⬜ MEGA 分支（`MegaCellsCompat` + 客户端 `MegaCutoffButtonFactory`）按用户口径**保留**：它反射的是 megacells 自己的类，与 ECO 版本无关；上游真撤那侧接口时再动。
- ⬜ 上游附属对 21.2.12 的跟进：`ecomorecell` 1.0.0 按 `impl/storage/**` 编译、启动即崩，已从开发运行期摘掉；`some-useless-things` 2.4.6.2 的 `CompactL9BlockEntity` 仍命名 `impl.storage.infinite.ECOStorageHostMode`，属潜伏炸点。等各自重发再挂回。
- ⬜ 开发环境里另一处无关噪音：`useless_mod` 1.21.1-2.4.6.2 向 Data Energistics 3.3.5 注册插件时 `NoSuchMethodError`（`PatternProviderMetadata.<init>` 签名变了），每次启动报一条 ERROR。与本模组无关，但它会把「真炸点」混在固定噪音里，值得单独摘掉或升级其一。
- ⬜ 两处已知脆弱面（非本轮引入）：`CellDriveScanner.ECO_CELL_INTERFACES` 只按接口 simple name 匹配，上游已证明会搬树改名；`ECOStorageCells.getCellInventory` 取到的元件没有配对 `releaseCellInventory`。
- ⬜ 发版前置：三按钮实机通过后，才把「支持 **ECO 数字奇点物品存储矩阵**的元件管理终端大宗按钮（需 Bigger AE2）」写进 `CHANGELOG_UNRELEASED.md`——现在只有类型树证据，不足以对外宣称支持。
- ⬜ 测试缺口：`rewrite` / `sameMarker` / `dedupeByChain` 都是纯逻辑，`test` 源集已能解析 AE2 与 ECO，补断言很便宜——按本仓「手动实测为主」的取舍决定要不要补。

### N. 上游 API 接入的剩余项 — P3 ⬜
- 背景：已接入 ECO `21.2.6` 的 `api.storage.IECOStoragePriorityHost` 与青春版 `1.3.6` 的 `setCellStack`；契约在场即直连（地板 `[21.2.12,)` 保证），只剩「从驱动器走到存储控制器」那一步仍按方法名探（`CellDriveScanner#storageController`）。
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
