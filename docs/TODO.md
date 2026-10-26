# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | 🔄 进行中 | ✅ 完成 | ⏸ 暂缓 | ❌ 已放弃
> 优先级：P1 最高 | P2 | P3
>
> 本表只放**还没做完的事**。已完成条目不留档（看 `CHANGELOG.md` 与 git 历史）；发版流程见 `RELEASE.md`；
> 工程约束（版本基线、只用公共 API、美术资源约定）见 `ARCHITECTURE.md`；上游请求见 `UPSTREAM_API_REQUESTS.md`。

## 一、进行中

### E. ME样板磁盘供应器面板变体（`cable_pattern_disk_provider`）— 🔄
- 现状：Part 形态已实现，与方块共享 `PatternDiskProviderLogic` / `PatternDiskProviderMenu`（host 接口 `PatternDiskProviderHost`）；模型与纹理用 AE2 `cable_pattern_provider` 的本地副本。
- 剩余：
  - 面板变体无独立合成配方，只与方块形态 1:1 无序互转（`recipe/pattern_disk_provider_to_cable.json` / `recipe/cable_pattern_disk_provider_to_block.json`）。
  - 待实机：编码终端 / 样板管理终端对 Part 形态的收录（AE2 源码表明按 owner 具体类登记，Part 的 owner 即 Part 本身）。
  - 待实机：面板返还栏的 `GENERIC_INTERNAL_INV`（已注册）在装配室回送时是否正常。

## 二、待实机验证

### V1. 万象构序（OmniSequence）批量发配
- 已落地：`integration/omnisequence/` 三个类 + `availableParallelSlotsFor` + `flushReturnsAfterCpuAccounting`。`compileJava` / `compileClientJava` / `runServer` 已过，启动日志有注册确认。
- 待核：
  1. 带万象构序 CPU 的大订单是否真的走整批交付（看 CPU `waitingFor` 是否不再堆、单 tick 是否不再无预算）。
  2. backpressure 取的是 `RECHECK_NEXT_TICK`（理由：插材料不触发 `parallelSlots.invalidate()`，同 tick 重问会读回插入前的数字）。实机看「同一样板每 tick 只吃一批」是否成瓶颈；若成了，正解是给 `invalidate()` 补「缓冲写入即失效」的调用点。
  3. 记账后补送是否解掉「产物在队列里、CPU 已把等待量算成已送达」的停顿。
  4. 与 NEO ECO 同时装时两条批量路径不互斥。
- 已知未修：NeoECO 路径的首发迟滞（`BatchAssemblerParallelIntake` 调无参 `availableParallelSlots()`，空机器首次发配报 0）。要改善得让上游契约带上样板。

### V2. 自装配样板磁盘供应器面板变体（`cable_meteorite_pattern_provider`）
- 待核：① 处理样板（不可自完成）能否推给相邻机器；② 相邻机器回送到返回仓的产物能否自动清空；③「锁定到结果」能否解锁；④ 网络塞满时的滞留产物能否最终送回；⑤ 面板在样板访问终端里的收录与图标。
- 已知取舍（不是缺陷）：产物回送走服务端世界 tick 事件——不能占 `IGridTickable` 服务槽，那个槽归 AE2 的 `PatternProviderLogic`；模型的 `particle`/`back` 共 4 处仍指向 `ae2cs:` 贴图，缺 AE2CS 时不渲染、不出日志。

## 三、未开始

### G. 批处理装配室：multiplier 由多个变体拼够（对齐 AE2）— P3 ⬜
- 现状：`resolveVariant` 要求**单个 key 覆盖整个 multiplier**；缓冲不足直接判 `INPUTS_UNAVAILABLE`、样板留队列。
- AE2 语义：`CraftingCpuHelper.extractPatternInputs` 遍历同槽多个合法模板，每个抽多少算多少，允许多变体拼够一个 multiplier。
- 影响：样板要 4 把工具、缓冲里是「2 新 + 2 磨损」时，AE2 分子装配室能跑，本机不接单。
- 改动面：`resolveVariant`（单 key 判定）+ `consumeInputs`（按 key 分桶）→ 返回使用记录；余料按**实际使用的每个变体**分别记账（`IInput.getRemainingKey` 按变体算）。
- 验证：多变体混合缓冲 + 磨损工具样板，核对 ① 网络收到的产物/余料 ② CPU `waitingFor` 是否满足 ③ 机器内无残留。

### H. `AEItemKey.hasComponents()` 语义与命名相反 — P3 ⬜（待报上游）
- 现象：AE2 19.2.17 里实现为 `return stack.getComponents().isEmpty();`——组件为空时返回 `true`，与名字相反。
- 消费点：`AECraftingPattern.getTestResult` 用它决定是否走「按物品」的替换缓存（键为 `what.getItem()`）⇒ 带组件的输入反而可能命中按物品的缓存。
- 待办：整理最小复现（带组件输入 + 可替换配方）后报 AE2 上游。
- 约束：批处理装配室**不要**用该方法判组件，需要时直接用 `!stack.getComponents().isEmpty()`。

### K. 样板磁盘存储策略：`computeFingerprint` 与截断可见性 — P2 ⬜
- 已结论：不会爆内存（内容快照缓存按内容共享、弱键随盘回收）；UUID 方案不必要。
- 待办：
  - **`computeFingerprint` 可退化**：现在对整盘组件 `stack.getComponentsPatch().hashCode()` 求哈希，满盘时每次刷新遍历大量样板 NBT。因 `PatternDiskContents` 是 record 且每次写入整体替换，指纹可改成「按槽记 contents 实例、引用比较」，O(9) 而非按 NBT 哈希。低风险、高收益。
  - **截断要可见**：镜像栏容量是「磁盘槽数 × 最大档位」（9 × 1024 = 9216，`mirrorCapacity`），超出时 `refreshPatternsFromDisks` 静默截断（多余样板不提供给合成系统，不报错不提示）。
- 关联：`PatternDiskProviderLogic` 与批处理装配室共用同一套磁盘内容 API。

### L. 高级档没有专属标记 — P3 ⬜（已知行为，非缺陷；审查时不要再当 bug 报）
- 现状：高级档不在 AE2 的 `EncodingMode` 枚举里，切进去时**不改 `mode`**（只 `setAdvancedMode(true)`），而绑盘读的正是 `menu.mode` ⇒ 高级档写出的标记是「进入高级档之前那个常规档」的标记。
- 影响：不崩不丢东西；磁盘列表里看起来像处理样板；从处理档进入时「唯一目标自动落盘」不生效。
- 选盘口径 2026-10 已重构为「以样板类型为锚点」（见 `ENCODING_MODES.md` §七），「继承标记把盘引到错误匹配项」那条路径已断。剩下的取舍是：**要不要给高级档一个固定标记**。
- 若要做，先定取舍：雕凿的 `uploadMark()` 是先短路再回退 `deriveMarkId()`，照抄会顶掉「导入过配方就写 `#<配方类别>`」那一支。高级档要不要也放弃类别信息？要保留的话，固定标记只能在「没导入过」时生效。
- 落点与顺序约束（雕凿那轮踩过的）见 `ENCODING_MODES.md` §七的「给某个档补一个专属标记」。

### N. 上游 API 接入的剩余项 — P3 ⬜
- 已落地并接入：ECO `21.2.6` 的 `api.storage.IECOStoragePriorityHost` 与青春版 `1.3.6` 的 `setCellStack`；本模组侧走「接口优先、反射兜底」（`integration/neoecoae/NeoECOStoragePriority.java`）。
- ⬜ `ICellHost` 的类型化适配：上游已把 `ICellHost` 提到 `api.storage`，可新增 `integration/neoecoae/NeoECOCellHosts.java` 做编译期类型化，让 `CellDriveScanner` 只调中立 SPI、不再按名字探；`setCellStack(null)` / `EMPTY` / `removeCell()` 三路试探可收敛成一次文档化调用。
  - **硬约束**：SPI 的实现注册必须在「ECO 在场」的类里（`NeoECOIntegration`）。`CellDriveScanner` 是无条件加载的 common 代码，它若直接 `instanceof` 上游接口，缺 ECO 时会 `NoClassDefFoundError`。
  - 青春版只能走 SPI（纯运行时可选，连 compileOnly 都没有）。
- ⬜ 提取原因：本模组已按自己的 `ExtractResult` 落地 `cell_locked` / `cell_refused` / `slot_empty` 三态；剩下的是「改用上游枚举给出更细的原因」。
- 维护：`UPSTREAM_API_REQUESTS.md` 的提交记录表随进展更新（UTC 时间戳）。

### U. 批处理装配室：模板量与流体替代的后续口径 — P3 ⬜
- ⬜ 畸形槽无日志：`BatchSynthesisEngine.consumeInputs` 把 `neededFor == 0`（模板量不可用/溢出）与「真的缺料」共用 `INPUTS_UNAVAILABLE`，每 tick 重试且不出日志。仿 `noteWarned` 加一次性 debug。
- ⬜ 同族口径：`PatternDiskAssemblerBlockEntity` 的可用性预判仍按 `getMultiplier()` 判「每格 1 件」，对流体替代样板只会静默填不出格（不扣料不丢件）；与该机器的格子语义一并审视。

### Q2. 样板磁盘管理终端的离线守卫 — P3 ⬜
- `insertDisk` / `storeInventoryDisk` 用的是缓存 `hostsByName`，动作内**没有在线判据** ⇒ 离线时仍能对旧宿主下手。
- 本仓两个终端的在线判据不统一：编码/管理终端用 `node.isActive()`（覆盖断电，不覆盖无线电量），元件管理终端用 `canInteractWithGrid()`（两者都覆盖）。要不要统一到后者，等排期。

### F1. 截断行为补进玩家文档 — P3 ⬜
- 镜像栏的真实上限是 9216（见 K），超出时静默截断。要把「上限与截断行为」补进 README 与游戏内指南，让玩家能察觉。

### F2. 高效分子装配室的回送方向不持久化 — P3 ⬜
- `CraftUnit.pushDirection` 不进 NBT（save/load 只存网格、样板与进度），重载后一个尚未收工的派发页退化成「只回网络」。产物不会丢，只是绕过相邻返回节点，与 AE2 契约有偏差。

### F3. ModDevMCP 的供给方式清理 — P3 ⬜
- `build.gradle` 的 `localRuntime 'dev.vfyjxf:moddevmcp:0.1.6'` 仍在，而本机 mavenLocal 并无该版本（靠 composite-build 替换），它与新增的 `files('libs/moddevmcp-0.3.jar')` 可能让 dev 运行时出现两份副本。单独一轮处理，并在本地跑一次 `runClient` 确认无重复 mod 载入。

### F4. GUI 纹理来源待确认 — P3 ⬜
- `textures/guis/pattern_modes.png`、`states.png` 与 AE2 同名件同画布但像素差异较大（约 69% / 58%），来源待作者确认后在 `README.md` 的授权表里归类；现按 unconfirmed 列出。

### F5. 配置界面补节 `.button` 键 — P3 ⬜
- 11 条配置节缺 `.button` 键（NeoForge 配置屏不补则回退平台自带的「编辑 / Edit」字样），`ae2_pattern_disk.configuration.title` 同理。键形态的坑见 `ARCHITECTURE.md` §10 的「加一个配置项」。

## 四、暂缓

### J. 极大数（远超 2.1G）兼容性优化 — P2 ⏸
- 现象：极大订单导致世界线程阻塞、autosave 无法写入。
- 已完成：**逐份循环的 per-tick 预算**（`OPERATIONS_PER_RUN` + `operations <= 0` break，剩余份数自动留回队列）随批处理装配室的双通道改造落地。
- 剩余线路：
  - **P0 余项**：`addAdditionalDrops` 的拆堆循环加保护（方块被破坏时 O(amount/64)，1e9 份 = 1500 万次循环 + 1500 万 `ItemStack`）。
  - **P1 表示层 long 化**：`cachedSlots` int→long；`assembled` int→long；抽 `availableParallelSlotsLong()`、int 版转调并钳制（NeoECO 侧行为不变）。
  - **P2 对外契约扩展**：万象构序 long 路径（`OmniBatchAdmission.maxCrafts()` 本为 long）；可选接 `OmniBigIntegerCraftingProvider`。NeoECO 路径受上游 int 签名所限。
  - **P3 审计补齐**：long 算术全量溢出审计；网络包与 NBT 的计数类型；其他 block entity 同类风险。
- 验收口径：任意份数下单 tick 处理量恒定、峰值 ≤ 50ms；摊销路径仍 O(1)；NeoECO 路径行为逐项不变；接近 `Long.MAX_VALUE` 的输入不崩、不截断、不丢、不复制。

### 8. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- EAE 矩阵 Pattern 槽硬过滤，磁盘配方只能间接进矩阵（高难度 IAECluster），暂缓。

### 9. 完整紧凑编码（改 `PatternDiskContents` 数据格式）— ⏸ 高风险
- 破坏存档兼容，保留后续专轮 + 迁移。

## 五、已放弃 / 有意不做

### 7. 配方自适应倍增 — ❌ 已放弃
- 需求感知驱动批量发配过于理想化，且会破坏 AE2 原生单份发配契约。供给端的 `isBusy`/`pushPattern` 覆盖已删除，恢复父类标准单份发配。

### 给原生供应器屏幕补 EAE+ 的那三个控件 — ❌ 不做
- ExtendedAE Plus 靠 `@Mixin(PatternProviderScreen.class)` 直接注入，没有按钮注册表/事件/工厂。补它只能反射内部类当 API，且屏幕 UI 没有「探不到就退回默认行为」的兼底。服务端那半（两个开关的行为）早已生效，缺的只是控件。上游若开放「按屏幕类登记按钮」的接口，再补。

### PAT 会话内看不到会话内新上传的样板 — 有意
- 「会话内行数冻结」是 AE2 的会话契约，不改成动态同步（那条路会崩服务端）。已写进 `PatternDiskTerminalView` javadoc 与 README。

### 编码终端输入槽的「顺序可见化」（1..N 序号）— 有意不做
- 按钮效果对玩家不透明时容易被当成坏的，但序号绘制要先解决 81 格分页视图的表现。

### 高级档补专属标记 — 未定取舍，见 L

## 六、归档索引
- 已完成条目与实现细节：`CHANGELOG.md`、git 历史、`ARCHITECTURE.md` 的模块说明。
- 上游请求与状态（ECO / 青春版 / EAE+）：`UPSTREAM_API_REQUESTS.md`。
- 发版流程与踩坑：`RELEASE.md`。
