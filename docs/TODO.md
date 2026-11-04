# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | ⏸ 暂缓
> 优先级：P1 最高 | P2 | P3
>
> 本表只放**还没做完的事**。已完成条目不留档（看 `CHANGELOG.md` 与 git 历史）；已放弃与有意不做的取舍去找对应的提交与代码注释，
> 本表不再列；发版流程见 `RELEASE.md`；工程约束（版本基线、只用公共 API、美术资源约定）见 `ARCHITECTURE.md`；
> 上游请求见 `UPSTREAM_API_REQUESTS.md`。

## 一、未开始

### N. 元件管理终端：驱动器格式自动兼容 + 准入按格式分家 — P2 ⬜
- 背景：`CellDriveScanner` 不再按 `instanceof DriveBlockEntity` 认驱动器，改认 AE2 的公开契约 `IChestOrDrive`（另需探到 `getInternalInventory()` 才收，否则逐格物品拿不到，`getCellItem` 只给物品会抹平分区）。放入时按格式双向收口：驱动器格式只收 `StorageCells.isCellHandled` 认得的元件，单格元件座只收探针认得的存储矩阵——探针由 `integration/neoecoae/EcoCellFormat` 注册，回答走 ECO 的 `api.storage.ECOStorageCells#isCellHandled`，缺席时退回接口名。
- 已有证据：`CellFormatGateTest` 12 条用例（第三方驱动器被列出且格数现读、矩阵进不了驱动器、AE2 元件进不了矩阵座、三条对照、`isStorageCell` 仍是两格式并集所以编码槽口径不变、空栈两种都不是、格序对不上（短/长）整台不列、宿主 `isItemValid` 说不收就不写、`canAcceptCell` 不落写入、宿主抛异常只丢这一台）；`compileJava` + `compileClientJava` + 全量 `test` 通过。
- ⬜ 实机：ME 箱子（`ae2:chest`）不应出现在元件表里。`IMEChest` 继承 `IChestOrDrive`，它的库存是「元件格 + 输入缓存格」拼起来的，格序与 `getCellCount()` 不对齐——判据是「长度逐一相等 + 显式排除 `IMEChest`」，箱子形状那一条已被单测覆盖，实机再看真箱子。
- ⬜ 实机：压缩统一管理遇到「ECO 矩阵躺在 AE2 驱动器里」时应整台跳过（现在先 `canAcceptCell` 再动手），确认那两张盘一张都没少；若日志出现「写回失败且原件放不回去」，看那一张有没有真的落在玩家脚下。
- ⬜ 实机：把 ECO 矩阵右键上传到普通 ME 驱动器 → 应当静默拒收，原盘还好好在矩阵座里。
- ⬜ 反选那一侧（AE2 元件进矩阵座）0.9.8 多半不是玩家能遇到的错：上游 `setCellStack` 自带 `isItemValid`（青春版也自判），所以更新日志没写这条。要验的是本轮那道格式闸没把上游**有意**放行的东西挡掉——AE2 创意元件（ECO 委派 handler 认领的那类）必须还能进矩阵座。
- ⬜ 实机：存量错放只挡新的——存档里 `ae2:drive` 那 2 处 `neoecoae:eco_mega_long_bulk_cell` 仍要能取出来（取出路径不设格式闸）。
- ⬜ 实机：把 AE2 元件 Shift+左键存进 ECO 矩阵座 → 应被这道格式闸挡掉。反选那一侧在 0.9.8 多半不是玩家能遇到的错（上游 `setCellStack` 自带 `isItemValid`，青春版也自判），更新日志因此没写；这格真正要验的是**别误伤**——AE2 创意元件那类被 ECO 的委派 handler 主动认领的栈，两边都必须照旧能放（口径已由单测 `cellClaimedByBothHandlersGoesEitherWay` 钉住）。
- ⬜ 实机：第三方驱动器（实现 `IChestOrDrive` 但不继承 `DriveBlockEntity`）在表里的显示、优先级列（读不到该显示「—」而不是 0）与中键优先级入口（非 `IPriorityHost` 的设备应报不支持）。
- ⬜ 待定：跨格式拒收目前静默（与 AE2 原生驱动器槽一致），只有 debug 级留痕。要不要给一句「这台不收这种元件」的提示，看实测时玩家会不会以为按钮坏了。
- ⬜ 实机（这轮撤下的风险）：库存长度必须逐一等于契约格数这条护栏是保守取舍——任何「合法但库存比格数长」的第三方驱动器（比如自带输出缓存槽的驱动器变体）会从表上**消失**，而不是被错位读写。摆一台真实第三方驱动器，确认它是该列没列，还是本来就不该列。
- ⬜ 对外文案：实机过了才把这两条写进 `docs/COMPATIBILITY.md` 与 `README.md`——现在只有单测与编译证据。

### N. ECO 数字奇点物品存储矩阵的大宗按钮：实机确认 — P2 ⬜
- 背景：反射层与 `-PecoApiJar` 开关已删，大宗压缩只剩 `integration/neoecoae/EcoBulkCells` 一条编译期路径；`mods.toml` 的 `neoecoae` 地板抬到 `[21.2.12,)`。判定只看 `IECOBulkMarkableCellItem`（物品面）与 `IECOBulkDisplayCell`（实例面），新载体按上游类型树应当自动命中，但没跑过机。
- 事实基线（`javap` 与 jar 内指南实证，别再按发版说明的口数写）：
  - 数字奇点物品存储矩阵要 **Bigger AE2** 在场才注册：门是 `integration.biggerae2.BiggerAE2Integration` 类上的 `@Integration("bigger_ae2")`（ECO 的 IntegrationManager 只在该模组装载时调起它），不是逐通道的 `requiredMod`——ITEM 与 FLUID 那两条是 null。
  - 旧 MEGA 大宗矩阵保留读取兼容并显示 `tooltip.neoecoae.bulk.moved` / `bulk.deprecated`，组装与拆解配方已撤。
  - 上游新增硬语义：**同一网络内矩阵共享已标记条目，同一压缩链不得在其他矩阵重复标记**。
- ⬜ 实机确认三枚按钮的**三档显隐**：编码槽不是大宗元件→全隐；是大宗元件但没有可压缩链（含出厂空盘）→只留「压缩统一管理」；有可压缩链→三枚都在，截断物钮画选中链那一级。再验 ① 数字奇点物品存储矩阵 ② 旧 MEGA 大宗矩阵 ③ 青春版 L1 小宗仓 各自落在哪一档，以及调级写回标记槽。青春版 1.4.1→1.4.3 没有任何 `IECOBulk*` 实现，L1 小宗仓能不能算压缩站点只能实测。
- ⬜ 聚合归属的实测：**归属只看优先级**——按矩阵自身元件级优先级、控制器存储优先级降序（`IECOStoragePriorityHost` 读不到排最后），同级按扫描顺序；每条链归顺序里第一个还有格位的那台，同链其余标记被去重，**库存本身不动**（低优先级矩阵上的库存会变成无标记库存）。摆两台不同优先级的 ECO 主机验：高优先级那台应当收满，低优先级只接溢出；再看日志 `[cell-mgmt] bulk consolidation: … target order:` 里 `p?` 是否出现（`p?` = 优先级没读到，顺序会退化成扫描顺序）。
- ⬜ **空的高优先级矩阵必须能接收**：曾在 `siteOf` 里加过「网络里的空盘不参与聚合」的守卫，结果标记只能在已有标记的矩阵之间去重、搬不进空矩阵——已删除。实测：给一台矩阵设较高优先级但留空（0 标记），按一次统一管理，链应当**搬过去**而不是原地去重。
- ⬜ 优先级取两级：`IECOStorageCell.getStoragePriority()`（矩阵自身，ECO 元件界面里调的那个）优先，其次宿主控制器 `IECOStoragePriorityHost`，最后扫描顺序；日志每站点打 `/q<自身>` `/p<宿主>`，`p?` 表示控制器优先级没读到。
- ⬜ 宿主类型与优先级来源：ECO 主机（`storage_system_*`）取控制器 `IECOStoragePriorityHost`；AE2 驱动器与 **ExtendedAE 的 `ex_drive`**（`TileExDrive extends DriveBlockEntity`，同一条枚举）取驱动器自己的 `getPriority()`——两者都是 AE2 存储优先级，同尺度可比。存档实证：`ae2:drive` 里躺着 2 处 `neoecoae:eco_mega_long_bulk_cell`，所以「盘放进普通 ME 驱动器」不是假设。聚合日志每个站点带 `/eco` 或 `/drive`；非 ECO 主机里的可标记元件会打一次性 WARN。不是假设。这类盘的口径本轮已定：表格照常列出、能取出、但只读不写——准入闸门挡新放入，`collectBulkSites` 又把写不回去的站点排除出聚合目标（WARN 同步为「只出现在表里，不作为聚合目标」）。
- ⬜ 显示口径变更要现场定性：生效截断现在取 `getCompressionCutoff`（上游账本优先），不再是配置槽里的标记物；两者分歧时首轮「压缩统一管理」可能把截断层写回标记槽，那是一次静默归一，要看回执条数对不对。
- ⬜ 归并边界：`EcoBulkCells.rewrite` 在名单超出格数时整份不写，而 `CellManagementTermMenu` 的 `room` 会把每台矩阵的接收量压到 `capacity()`、剩下的回落到原站点，所以 25 格 → 5 格不会整份拒绝；真正会「什么都没发生且无回执」的是 `touched == 0`（没有可搬的链，或链全部已有库存）。实测时把这两种情形分开看，必要时给一句「没有需要整理的标记」回执。
- ⬜ 抬地板的现场形状：装 21.2.1–21.2.11 时应当是**整包停在加载阶段**，报错屏点名「Mod ae2_pattern_disk only supports neoecoae [21.2.12,)」（`ModSorter.sort` 命中已安装可选依赖越界后只用 systemMods 构加载列表）。实测一次再对外说。
- ⬜ MEGA 分支（`MegaCellsCompat` + 客户端 `MegaCutoffButtonFactory`）按用户口径**保留**：它反射的是 megacells 自己的类，与 ECO 版本无关；上游真撤那侧接口时再动。
- ⬜ 上游附属对 21.2.12 的跟进：`ecomorecell` 1.0.0 按 `impl/storage/**` 编译、启动即崩，已从开发运行期摘掉；`some-useless-things` 2.4.6.2 的 `CompactL9BlockEntity` 仍命名 `impl.storage.infinite.ECOStorageHostMode`，属潜伏炸点。等各自重发再挂回。
- ⬜ 开发环境里另一处无关噪音：`useless_mod` 1.21.1-2.4.6.2 向 Data Energistics 3.3.5 注册插件时 `NoSuchMethodError`（`PatternProviderMetadata.<init>` 签名变了），每次启动报一条 ERROR。与本模组无关，但它会把「真炸点」混在固定噪音里，值得单独摘掉或升级其一。
- ⬜ 两处已知脆弱面（非本轮引入）：`CellDriveScanner.ECO_CELL_INTERFACES` 的名字匹配只剩探针缺席时的兜底（青春版没有编译期契约），上游搬树改名时那一条仍会漏认；`ECOStorageCells.getCellInventory` 取到的元件没有配对 `releaseCellInventory`。
- ⬜ 发版前置：三按钮实机通过后，才把「支持 **ECO 数字奇点物品存储矩阵**的元件管理终端大宗按钮（需 Bigger AE2）」写进 `CHANGELOG_UNRELEASED.md`——现在只有类型树证据，不足以对外宣称支持。
- ⬜ 测试缺口：`rewrite` / `sameMarker` / `dedupeByChain` 都是纯逻辑，`test` 源集已能解析 AE2 与 ECO，补断言很便宜——按本仓「手动实测为主」的取舍决定要不要补。

### N. 上游 API 接入的剩余项 — P3 ⬜
- 背景：已接入 ECO `21.2.6` 的 `api.storage.IECOStoragePriorityHost` 与青春版 `1.3.6` 的 `setCellStack`；契约在场即直连（地板 `[21.2.12,)` 保证），只剩「从驱动器走到存储控制器」那一步仍按方法名探（`CellDriveScanner#storageController`）。
- ⬜ `ICellHost` 的类型化适配：上游已把 `ICellHost` 提到 `api.storage`，可新增 `integration/neoecoae/NeoECOCellHosts.java` 做编译期类型化，让 `CellDriveScanner` 只调中立 SPI、不再按名字探；`setCellStack(null)` / `EMPTY` / `removeCell()` 三路试探可收敛成一次文档化调用。
  - **硬约束**：SPI 的实现注册必须在「ECO 在场」的类里（`NeoECOIntegration`）。`CellDriveScanner` 是无条件加载的 common 代码，它若直接 `instanceof` 上游接口，缺 ECO 时会 `NoClassDefFoundError`。
  - 青春版只能走 SPI（纯运行时可选，连 compileOnly 都没有）。
- ⬜ 提取原因：本模组已有自己的 `ExtractResult`（4 值 `OK` / `EMPTY` / `LOCKED` / `REFUSED`，后两者出声、`EMPTY` 静默）；剩下的是「改用上游枚举给出更细的原因」（上游侧条件已由 ECO #119 满足，见 `UPSTREAM_API_REQUESTS.md` 的提交记录）。
- 维护：`UPSTREAM_API_REQUESTS.md` 的提交记录表随进展更新（UTC 时间戳）。

### N. 代码整理：剩余冗长文件的逐个拆分 — P2 ⬜
- 已定规矩（`ARCHITECTURE.md`「文件规模」）：一个类只装一件能一句话讲清的事，>800 行必须拆，目标 ≤650 行；动作入口留在菜单类，干活的搬进同包 `final class`。菜单层已按功能分 `cell` / `pattern` / `assembler` 三个子包。
- 已完成样本：`cell/CellManagementTermMenu` 1784 → 653 行 + 6 个协作类；`cell/CellHostSwap` 收掉两个管理域各写一遍的「取出 → 改副本 → 放回 → 失败回填」。
- ⬜ `pattern/PatternDiskEncodingTermMenu`（2533 行，148 个方法）——建议四块：① 编码与空白样板记账（`encode`/`getAndUpdateOutput`/`refillBlankPatternSlot`/`consumeBlankPattern`）② 磁盘写入与取出（`transferToFirstWritable`/`handleExtractFromDisk`/`hasRoomFor`/`deliverExtracted`，抽 `PatternDiskTransfer`）③ 标记与分类导入（`bindPrefix`/`deriveMarkId`/`uploadMark`/`setPendingRecipeCategory`）④ 编码模式与超载行（`setOverloadedMode`/`seedOverloadedRowsFromPattern`/`withFlag`/`setAdvancedSide`）。拆前先给 `encodedPatternSlot`、`diskIndex`、`tell()`、`syncDiskList(true)` 这几处加包内访问器。
- ⬜ `logic/PatternDiskProviderLogic`（2020 行）：推送节拍、阻挡判定、输出解析三块。
- ⬜ `block/entity/BatchAssemblerBlockEntity`（1624 行）：配方执行、并行车道、产物投递三块。
- ⬜ `client/gui/CellManagementTermScreen`（1388 行）：表格、标记区、按钮与面板三块。
- ⬜ `cell/CellDriveScanner`（969 行）：扫描与描述、取放、优先级、格式判定四块；约 80 处调用点要跟着改，改完把「公共面」收敛成一个门面类。
- ⬜ `block/entity/PatternDiskAssemblerBlockEntity`（923 行）。

## 二、暂缓

### 8. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- EAE 矩阵 Pattern 槽硬过滤，磁盘配方只能间接进矩阵（高难度 IAECluster），暂缓。

### 9. 完整紧凑编码（改 `PatternDiskContents` 数据格式）— ⏸ 高风险
- 破坏存档兼容，保留后续专轮 + 迁移。

## 三、归档索引
- 已完成条目与实现细节：`CHANGELOG.md`、git 历史、`ARCHITECTURE.md` 的模块说明。
- 上游请求与状态（ECO / 青春版 / EAE+）：`UPSTREAM_API_REQUESTS.md`。
- 发版流程与踩坑：`RELEASE.md`。
