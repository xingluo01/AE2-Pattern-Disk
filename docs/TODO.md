# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | 🔄 进行中 | ✅ 完成 | ⏸ 暂缓 | ❌ 已放弃
> 优先级：P1 最高 | P2 | P3
> 美术资源约定：相关机器美术资源先借用源机器（已复制到本项目 `src/main/resources/assets/ae2_pattern_disk/textures/`，借用清单见 README 授权表），后续补票替换。

## 一、已完成（0.1.1）

### 1. 独立创造标签页 — P1 ✅
- ✅ `AEPatternRegistries`（CreativeModeTab，11 物品 + 图标）
- ✅ lang `itemGroup.ae2_pattern_disk`（zh/en）

### 2. 磁盘纹理随配方类型变 — P1 ✅
- ✅ `PatternDiskItem.typePropertyValue()` + `InitPatternDiskProperties`（item property `pattern_type`）
- ✅ 磁盘 base 模型 overrides 按类型切 4 个类型模型（本地纹理）

### 3. 高效分子装配室 — P2 ✅
- ✅ `PatternDiskAssemblerBlockEntity`：`ICraftingMachine` + `IGridTickable` + 8 线程 `CraftUnit`（3×3 ×8）+ 加速卡
- ✅ 配方来源（接受磁盘供应器）、产物回送相邻+网络、余料、能量判定
- ✅ capability（`CRAFTING_MACHINE` + `IN_WORLD_GRID_NODE_HOST`）+ `associateBlockEntities`
- ✅ 模型/blockstate/item model/lang/数据包配方（本地纹理）
- ✅ 命名：高效分子装配室（zh）/ Efficient Molecular Assembler（en）
- ✅ 性能：`CraftUnit.craftingInv` 持久缓存；`pushOut` 相邻+网络双通道
- ✅ GUI：`PatternDiskAssemblerMenu` + `assets/ae2/screens/ae2_pattern_disk/pattern_disk_assembler.json` 布局（AE2 风格 + 本地底图）+ lang

### 4. 自装配样板磁盘供应器 — ✅（AEPD 侧自持实现）
- 定稿方案：AEPD 复制 AECS 的自装配核心（不建跨 mod API、不编译期引用 AECS 类型），设备以 `ae2cs` 在场为注册前提
- 落地：`MeteoritePatternProviderBlock` / `BlockEntity` / `SelfAssemblingPatternDiskProviderLogic` / `Menu` + `integration.ae2cs.AecsSoftDep`；
  GUI 复用 `pattern_disk_provider` 布局并挂 `UpgradesPanel`（升级槽随之显现）
- 原「在 AECS 内做磁盘兼容层」的两条 PR 已关闭（方向相反）：#75 一槽二用、#83 全面支持 + 依赖解耦

### 5. 存储性能（策略二：静态全量解析）— ✅
- ✅ 磁盘内容指纹缓存：内容未变→短路跳过全量重建（`refreshPatternsFromDisks`）
- ✅ `getTerminalPatternInventory` 缓存（未变复用 adapter）+ `clearContent` 失效
- ✅ 同 tick 多变化合并（指纹去抖天然合并）
- ✅ `CraftingTree`（产物→配方索引 + 需求链解析 `requiredOf`）——建树基础，备用

### 6. AE2WTLib 前置补齐 — ✅
- AECS 1.2.x 依赖 `de.mari_023:ae2wtlib`；dev 环境补齐 19.5.0 + `ae2wtlib_api`
- 启动自验：1.2.2 时代通过一次；依赖已换 1.3.0（`libs/ae2cs-1.21.1-1.3.0.jar`，取自 `1.21.1-main`，即 PR#82 的 head），该组合**尚未重跑自验**

## 二、已放弃 / 暂缓

### 7. 配方自适应倍增 — ❌ 已放弃
- 设计（getRequestedAmount 需求感知驱动批量发配）判断**过于理想化**，且会破坏 AE2 原版单份分子装配室的发配契约
- **已删除**供给端 `isBusy`/`pushPattern` 覆盖，恢复父类标准单份发配（倍数=1）
- 装配机仍靠 AE2 标准连续 push 天然并行（每次单份），原版装配室不受影响

### 8. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸（挂起，后续制作）
- EAE 矩阵 Pattern 槽硬过滤 → 磁盘配方只能间接进矩阵（高难度 IAECluster，暂缓）

### 9. 完整紧凑编码（改 PatternDiskContents 数据格式）— ⏸ 高风险（挂起）
- 破坏存档兼容，保留后续专轮 + 迁移

## 三、功能缺口（需求记录，未实施）

### A. ME样板磁盘供应器 GUI 工具栏 — P2 ✅（2026-09 完成）
- ✅ 左侧三切换按钮（阻挡模式 / 锁定合成 / 样板管理终端）：`PatternDiskProviderScreen` 用 `ServerSettingToggleButton`×2 + `ToggleButton` 加进左侧工具栏；服务端经 `PatternProviderLogicHost` 的 default 方法拿到 logic 的 config manager，`ConfigButtonPacket` 能正常循环设置（**至此确认真实可用**）
- ✅ 优先级按钮：本轮补 `widgets.addOpenPriorityButton()`；优先级同样经 default 方法转发到 `PatternProviderLogic`
- ✅ 子菜单回跳（补优先级按钮的配套）：AE2 的 `PatternProviderLogicHost.returnToMainMenu` 默认打开 **AE2 原生** `PatternProviderMenu`，等于把 1024 槽镜像清单当真实样板栏暴露（可取走＝免费复制、可写入＝无磁盘背书的幻影样板并随 NBT 留存）→ 已在 `PatternDiskProviderHost` 覆写 `returnToMainMenu`/`openMenu` 指回本模组菜单
- ✅ 锁定原因显示：screen JSON 早已声明 `lockReason` 位置，但客户端从未注册控件；本轮补自绘 `LockReasonWidget`（AE2 的 `PatternProviderLockReason` 构造函数要求传入 `PatternProviderScreen`，本项目不能继承它——那需要继承 AE2 的 `PatternProviderMenu`，而该菜单会把镜像出来的样板清单当真实清单暴露进 GUI）

### B. 样板管理终端取出样板逻辑 — P2 ✅
- ✅ `PatternDiskRemoveInventory` + `PatternDiskTerminalView`：取出必先 SIMULATE 确认网络空样板足额，再 MODULATE 扣除并真实移除磁盘内配方；不足则拒绝；写入一律拒绝；两形态（方块/面板）经 `getTerminalPatternInventory()` 共用
- 待实机验证（见 F③/F④ 同口径）

### C. EAE 拓展样板管理终端 — P3 ✅
- ✅ 实测结果：EAE 的拓展样板管理终端会正确扣除网络空样板并锁定槽位

### D. 方块原创纹理补票 — P3 ✅（2026-09 完成，仅剩下方 GUI 纹理来源待确认）
- 现状：转存器用 io_port 风格（`pattern_transferer_*` + `pattern_transferer*.json`，由 `powered`（= ME 节点在线）驱动 off/on）；**批处理装配室改用 `batch_assembler_grid*`（取自 XingLuo_AE2_1.21_GUIExpansion 资源包的 AdvancedAE 量子合成器一套，模型 `batch_assembler.json` / `batch_assembler_on.json`，同样由 `powered` 驱动）**；供应器用 `pattern_provider*`；高效装配室用 `molecular_assembler.png`。其中转存器/供应器/高效装配室纹理已于 2026-09 局部重绘（已非 AE2 字节副本，README 授权表按像素比对分档）；转存器的 `_top.png` 与 side/back/bottom 仍是 AE2 原文件。批处理装配室旧的一套 io_port 风格 `batch_assembler_{front,front_on,top,top_on,side,back,bottom}.png` 已被 grid 一套取代并**已删除**（不再随包分发）；该资源包与本 mod 为同一制作者，属内部流通，README 无需引用资源包
- 目标：为 4 个方块（转存器 / ME样板磁盘供应器 / 高效分子装配室 / 批处理装配室）绘制原创纹理并替换，同时同步 README 授权表（移除对应条目）
- 范围：先做借用纹理本地化与外观替换（不改现有几何资产；批处理装配室的模型 `batch_assembler.json` 为 `cube_all` 包装、`batch_assembler_on.json` 取资源包量子合成器发光壳几何），后续逐块替换为原创纹理
- 待确认：`textures/guis/pattern_modes.png`、`states.png` 与 AE2 同名件同画布但像素差异较大（~69% / ~58%），来源待作者确认后在 README 归类（现按 unconfirmed 列出）

### E. ME样板磁盘供应器面板变体（`cable_pattern_disk_provider`）— 🔄 进行中（2026-09 记录）
- 已实现：Part（贴电缆面板）形态，与方块共享同一 `PatternDiskProviderLogic` 与 `PatternDiskProviderMenu`（host 接口 `PatternDiskProviderHost`）；零模型与纹理借用 AE2 `cable_pattern_provider`（已本地化到 `models/part/pattern_disk_provider_base.json` 与 `textures/part/pattern_disk_provider*`）
- 待办：① 面板变体无独立合成配方，仅与方块形态 1:1 无序互转（`recipe/pattern_disk_provider_to_cable.json` 与 `recipe/cable_pattern_disk_provider_to_block.json`）；② ✅ 纹理已按本项目风格重绘；③ 编码终端/样板管理终端对 Part 形态的收录已由 AE2 源码证实（网格机器按 owner 具体类登记，Part 的 owner 即 Part 本身），待实机确认；④ 面板返还栏的 `GENERIC_INTERNAL_INV` 已注册（`RegisterPartCapabilitiesEvent`，待实机验证装配室回送）；⑤ ✅ 已修：指纹混入宿主 identity salt，面板用所贴面区分同电缆多面板；⑥ ✅ 已修：两形态掉落先清空镜像再走 `logic.addDrops(...)`——返还栏与**待发送缓冲**（已投未达的物品）都会掉落，镜像本身不入掉落；⑦ ✅ 已修：导入前清空镜像；导出也不再往内存卡写镜像样板（AE2 会写 pattern inventory 进卡，导入时按空白样板计价 → 白扣玩家空白样板）；⑧ 面板物品名后缀「（面板）」已去掉，与 AE2 原版面板/方块同名的做法一致

### F. 其余记录 — P3 ⬜
- **EAE+ 往原生供应器屏幕加的那几个按钮：无 API，作罢（2026-10 记录）**：ExtendedAE Plus 给 AE2 原生供应器屏幕加的两个开关（高级阻挡、智能翻倍）与「每供应器缩放上限」输入框，全部靠 `@Mixin(PatternProviderScreen.class)` 直接注入，它仓库里没有任何按钮注册表/事件/工厂；公开面只有 `IPatternUploadMenu`（菜单侧，本项目已实现）与 `IPatternUploadTerminal`（屏幕侧，在其仓库里没有消费者，按钮不会自动出现）。
  - **服务端那半其实早就在身上**：它的 `@Mixin(PatternProviderLogic.class)` 系列命中的正是本模组供应器继承的父类，所以两个开关的行为已生效，缺的只是那三个控件。
  - **不补的理由**：补它们只能反射 EAE+ 的 `EAPSettings` / `EAPServerSettingToggleButton` / `GuiUtil` 与它的 C2S 包，属于「拿内部类当 API」——而屏幕 UI 不像投递路径那样有「探不到就退回默认行为」的天然兼底。若上游日后开放按屏幕类登记按钮的接口，再补。
- **样板清单容量 1024 待补文档**：`PatternDiskProviderLogic` 构造时把内部样板清单固定为 1024 槽（`super(mainNode, host, 1024)`）；多张磁盘的样板总数超过该上限时的行为：代码为 `i < all.size() && i < patternInv.size()` 填充镜像 → **静默截断**（超出的样板不提供给合成系统，既不报错也不提示）→ 稍后把这个上限与截断行为补进 README/指南
- ✅ **代码收敛已完成**：`BatchAssemblerBlockEntity` 原先自建了一份 `PatternDiskRemoveInventory` + 匿名 sink，现已改用与方块/面板共用的 `PatternDiskTerminalView`——核心的"取走样板要扣网络空样板并真删磁盘配方"逻辑（`PatternDiskRemoveInventory`）全仓只剩一份；NeoECO 反射适配器（`NeoECOBusTerminalView`/`NeoECOBusDisks`）仍保留自带的空样板抽取，因为那条总线不是 action host，其抽取刻意不归属任何玩家

### G. 批处理装配室：允许一个 multiplier 由多个变体拼够（对齐 AE2）— P3 ⬜（2026-09 记录）

- 现状：`BatchAssemblerBlockEntity.resolveVariant` 要求**单个 key 覆盖整个 multiplier**；缓冲不足时直接判 `INPUTS_UNAVAILABLE`、把样板留在队列。
- AE2 原生语义不同：`CraftingCpuHelper.extractPatternInputs`（19.2.17）**遍历同一输入槽的多个合法模板**，每个抽多少算多少（`remainingMultiplier -= extracted`），允许多个变体拼够一个 multiplier ⇒ 同一槽可能混用多个变体。
- 影响：样板要 4 把工具而缓冲里是「2 新 + 2 磨损」时，AE2 分子装配室能跑，本模组的批处理装配室会卡住不接单。
- 改动面：`resolveVariant`（单 key 判定）+ `consumeInputs`（按 key 分桶）→ 返回使用记录而非单个 key；余料须按**实际使用的每个变体**分别记账（`IInput.getRemainingKey` 是按变体算的）。
- 验证要求：多变体混合缓冲 + 磨损工具样板，核对①网络收到的产物/余料 ②CPU `waitingFor` 是否被满足 ③机器内无残留。

### H. （既存）`AEItemKey.hasComponents()` 语义与命名相反，可能影响 AE2 的替换判定 — P3 ⬜（待报上游，2026-09 记录）

- 现象：AE2 19.2.17 的 `AEItemKey.hasComponents()` 实现为 `return stack.getComponents().isEmpty();` —— **组件为空时返回 true**，与名字含义相反。
- 消费点：`AECraftingPattern.getTestResult` 用它决定是否走「按物品」的替换缓存（缓存键为 `what.getItem()`）。按当前实现，**带组件的输入反而可能命中按物品的缓存**，替换判定存在出错可能。
- 本项目策略：批处理装配室**不要**用该方法判组件；需要时直接用 `!stack.getComponents().isEmpty()`。
- 待办：整理最小复现（带组件输入 + 可替换配方）后报 AE2 上游。

### I. 万象构序（OmniSequence）批量发配适配 — P3 ✅（2026-09 记录，2026-10-09 实施完毕，待实机）

- **背景**：万象构序（`molecularmanipulator`）自 1.3.9 起提供 `Omni Batch Provider API v1`（运行时 ABI 1，包 `com.atir.molecularmanipulator.api.crafting`）。它让 CPU 一次分配多份**完整**配方，而非每份推一次——正是批处理装配室已有的能力形态。
- **【2026-10-09 更新】上游已补上适配器注册表，条件 mixin 那条路作废**：本模组曾向 `AyaYumi/OmniSequence-Transfinite` 提 issue #4（“批量契约没有适配器注册表，迫使可选集成走条件 mixin”，指 `CraftingCpuLogicMixin:1204` 的 `instanceof`）。维护者回复 **2.0.7 completes this API**，新增 `OmniBatchProviderAdapterRegistry`（带按样板的重载与 provider-only 便捷重载，`unregister`/`revision`，**无匹配则回落原生 `instanceof`**）。所以不再需要 mixin，也不需要把接口注入机器类。
- **本次落地的形状**：
  - 新增 `integration/omnisequence/`：`OmniSequenceSoftDep`（`ModList` 判定 + 缓存）、`OmniSequenceIntegration`（向两个注册表登记）、`OmniBatchAdapter`（`OmniBatchCraftingProvider` 的薄实现，全转调机器）。`BatchAssemblerBlockEntity` **不引用上游任何类型**，缺席时这三个类不会被加载。
  - `AE2PatternDisk` 构造期：`if (OmniSequenceSoftDep.isLoaded()) OmniSequenceIntegration.register();`（与 `AecsSoftDep` 同一时机口径）。
  - `BatchAssemblerBlockEntity` 新增 `availableParallelSlotsFor(IPatternDetails)`（按 probe 传入的样板估容），原 `availableParallelSlotsLong()` 转调它并**保持原有语义**（取 `currentDispatchPattern()`，空机器仍为 0）。
    ⚠️ **NeoECO 路径的首发迟滞并没有修**：`BatchAssemblerParallelIntake` 仍调无参的 `availableParallelSlots()`，而那条接口不传样板、只能拿机器自己的猜测，所以空机器首次发配照旧报 0。要改善它得让上游契约带上样板（或另找时机把 `lastHandedPattern` 填上）——本轮没动，别按「已修」记账。
  - 新增 `flushReturnsAfterCpuAccounting()`：登记进 `OmniPostAccountingOutputAdapterRegistry`，记账后把平滑回传队列里**已拥有**的产物补送一次（不重跑任何配方；只交出网络实际接收的量，收不下的仍留在队列里）。额度是「按累计总量算、按调用次数消耗」，所以本 tick 收了几批就会被补送几次，最多把当次额度提前花完。
  - `build.gradle`：`compileOnly` + `localRuntime` 均换 `omnisequence-transfinite-1624558:**9043224**`（2.0.7）；`neoforge.mods.toml` 新增 `molecularmanipulator` 的 `optional`/`AFTER`/`side=BOTH`/`versionRange="*"`（与 jade 同口径，不自设区间）。
  - **不覆写 `isBusy()`**（最初写过一版，自查后删）：上游明说 CPU 只对解析出的能力对象调 `prepareOmniBatch`，而 `isBusy()` 正是 AE2 决定「还要不要给这台机器派单份活」的判据——一旦按「这块样板凑不满两份」答忙，单份派发会一起停掉。
- **依赖联动（升 2.0.7 的必要代价）**：2.0.7 把 `appliedenhancements` 地板从 `[1.0.9,)` 抬到 **`[1.1.0,)`**，所以 `applied-enhancements-1665696:**9043227**`（1.1.0）必须同批升——只升 omnisequence 会在预加载阶段被 FML 拦住（实测：`Mod molecularmanipulator requires appliedenhancements 1.1.0 or above`）。
- **已验证**：`compileJava`/`compileClientJava` 通过；`runServer` 到 `Done`、无非环境噪声 ERROR，且启动日志出现 `[AE2-Pattern-Disk] Batch assembler registered for OmniSequence batch delivery`（证明注册表路径真的跑到了）。
- **待实机确认**：① 带万象构序 CPU 的大订单是否真的走整批交付（看 CPU `waitingFor` 是否不再堆、单 tick 是否不再无预算）；② backpressure 已定取 `RECHECK_NEXT_TICK`（理由见下一条）——实机看「同一样板每 tick 只吃一批」是否成为吞吐瓶颈，若成了，正解是给 `parallelSlots.invalidate()` 补一个「缓冲写入即失效」的调用点，让同 tick 重问拿到真值；③ 记账后补送是否真的解掉「产物在队列里、CPU 已把等待量算成已送达」的停顿；④ 与 NEO ECO 同时装时两条批量路径不互斥（两套独立钩子，预期不冲）。
- **两处按上游契约收紧的决定（审查后）**：
  - **所有权报 `PERSISTED_PROVIDER_QUEUE`**，不是 `TRANSFERRED_TO_DURABLE_TARGET`：材料进的是**本机自己的**元件缓冲（随本机的元件物品落盘），契约原文的第二支（`persisted its own queue`）正是这种情形；第一支留给「交给外部持久目标」那种。
  - **backpressure 报 `RECHECK_NEXT_TICK`**，不是 `MAY_ACCEPT_MORE`：本机容量估算按（样板, 游戏刻）记忆，而插材料**不会**触发 `parallelSlots.invalidate()`（全仓唯一调用点在配方池重建），所以同一 tick 对同一样板再问会读回插入前的数字、随后的 commit 必然投不进去（不会超卖，但白试一次、reject 原因也失真）。上游自家机器接受一批后也是 block 当前 tick。
- **保留的契约要点**（写适配器时踩过的，实现已按此写）：`prepareOmniBatch` 只能只读或做**可回滚**的预留；`admission` 用后即弃、由 Omni 总是 `close`；`maxCrafts() < 2` 视为不参与（也可返回 `null`）；`commit` 同步且**只能** `accept`/`reject` 二选一，`accept` 前整批材料必须已在持久目标内，禁止部分接收；**不得用 `probe × craftCount` 校验交付**（AE2 替代输入会改变 key 或比例，`OmniBatchRequest.inputs` 才是权威）；`CAPACITY_CHANGED` 只压当前 tick、下 tick 可重试，其余原因会让该供应器/样板在当前作业内退回单份；`accept` 之后抛异常仍算已接收（防 CPU 重复注入）。
- **向上游回报时值得问的两句**：① `Ownership` 那两个值是否只作遥测？若「进了自己的存储元件」在他们看来也算 durable target，我们可以改回第一支；② 同一 tick 是否可能并发持有两个 admission（艾琳未逐行核 62 KB 的 `MolecularBatchCraftingExtractor`——那才是真正消费 `Receipt`/`Rejection` 的地方；即便会并发，本机的失败形态只是「第二次 commit 被拒 + 本 tick 被抑制」，全有或全无 + 回滚保证不超卖）。
- **已向上游回报（2026-10-09）**：[issue #4 comment](https://github.com/AyaYumi/OmniSequence-Transfinite/issues/4#issuecomment-6067840971)，内容：已实现且无需 mixin，并请他们判两件事——① `Ownership` 那个值是否只作遥测（如果「进自己的存储元件」也算 durable target，我们就改回第一支）；② 同一 tick 能否并发持有两个 admission（我们未能从 62 KB 的 `MolecularBatchCraftingExtractor` 确认）。另告知 2.0.7 抬 `appliedenhancements` 地板是本次唯一迁移代价。issue 保持 OPEN，等他们回。
- **参考来源**：`AyaYumi/OmniSequence-Transfinite` 分支 `1.21.1-neoforge`（2026-10-09 时 `9e9e0ee`，`mod_version=2.0.8`），文档 `docs/omni-batch-provider-api.md`；本机克隆在 `~/.pi/tmp/omniseq-t`（**临时目录，勿依赖**）。
  ⚠️ **`git fetch` 不改工作区**：2026-10-09 核对时它停在 `bd72375`（2.0.5-fix 树）而远端已到 `9e9e0ee`——要么先 `git checkout 1.21.1-neoforge`，要么用 `git show origin/1.21.1-neoforge:<路径>` 读；直接读工作区会得出「注册表不存在」这类错误结论（本轮真踩过）。


### J. 极大数（远超 2.1G）兼容性优化线路 — P2 ⏸（2026-09 记录，已审计待实施）

- **故障现象（实测）**：极大订单导致世界线程阻塞、autosave 无法写入（数据上行卡死）。
- **根因**：`runBatch()` 的 javadoc 自述 “with no per-tick ceiling”。摊销路径 `assembleBatchOnce(chunk)` 是 O(1)，无上限本身没问题；但同一循环里还住着逐份路径 `while (remaining > 0) { assembleOnce(...) }`（`:1267`）——一旦 `canAmortise` 覆盖不满整批（多变量混料、某槽候选拼不满、留容器槽），剩余份数就在**单个 tick 内**逐份跑完。每份 `assembleOnce` 是一次完整合成（`resolveInputs` → `consumeInputs` → 产出 map → 记账），量级数十微秒 ⇒ 剩 1 万份约 0.5 秒、100 万份约 50 秒。
- **2.1G 的真实角色**：不是根因（`canAmortise` / `chunk` / `remaining` 全程 long，逐份路径的触发与它无因果）；但 `availableParallelSlots()` 返回 int 使超大订单被 CPU 切成多批交接，**每批各触发一次无预算循环**，因此“2.1G → 份数极大 → 卡死”这条链真实存在，2.1G 是放大器而非源头。
- **全链路风险清单（已审计）**：

| # | 位置 | 风险 | 等级 |
|---|---|---|---|
| 1 | `:1267` 逐份循环 | 无 per-tick 预算，单 tick 阻塞 | **致命** |
| 2 | `:2169` `addAdditionalDrops` 拆堆循环 | 方块被破坏时 O(amount/64)，1e9 份 = 1500 万次循环 + 1500 万 ItemStack | 中 |
| 3 | `:345`/`:749` `cachedSlots` int | 容量缓存上限 2.1G | 中 |
| 4 | `:693` `availableParallelSlots()` 返回 int | 对外容量上限；NeoECO 上游签名同 | 中（上游所限） |
| 5 | `:1251` `assembled` int | 统计计数器截断（纯显示） | 轻 |
| 6 | `:312` `MAX_ADVERTISED_PARALLEL_SLOTS` int | 系统属性可覆盖，但仍受 int 限 | 中 |
| 7 | 溢出防护 `canAmortise` / `availableParallelSlots` | saturating 已到位 | 安全 |
| 8 | `drainOutputs` | `OUTPUT_RETURN_TICKS` 平滑回送，每 tick 限量 | 安全（**可作修复范本**） |

- **优化线路**（顺序有依赖，不可颠倒）：
  - **P0 安全**：① 逐份循环加 per-tick 预算——照 `drainOutputs`/`OUTPUT_RETURN_TICKS` 的既有模式，预算耗尽 break，剩余份数由既有 `if (remaining <= 0) it.remove(); else entry.setValue(remaining);` 自动留回队列（**无需重写队列语义**）；② `addAdditionalDrops` 拆堆循环加保护。**未做 P0 前放宽上限只会加重症状**（单 tick 一次吃进更多份）。
  - **P1 表示层 long 化**：`cachedSlots` int→long；`assembled` int→long；抽 `availableParallelSlotsLong()`、int 版转调并钳制（NeoECO 侧行为不变）。
  - **P2 对外契约扩展**：万象构序 long 路径（`OmniBatchAdmission.maxCrafts()` 本为 long）；可选接入 `OmniBigIntegerCraftingProvider` 走 BigInteger（现已确认该契约存在）。NeoECO 路径无法突破（上游 int 签名）。
  - **P3 审计补齐**：long 算术全量溢出审计；网络包与 NBT 的计数类型；其他 block entity 同类风险（`PatternDiskProviderLogic` 1024 槽上限、`PatternDiskAssemblerBlockEntity`）。
- **验证**：P0 用单 tick 耗时监控（不超 50ms 预算）+ 极大 chunk 模拟测试；P1/P2 需回归 NeoECO 路径行为不变。
- **关联**：P2 的 long 收益依赖万象构序适配（见条目 I，当前 ⏸）。

#### 需求落地补充（2026-09）：把两条需求转成可验收指标

需求原文：① 极大数的合成不会出现性能瓶颈；② 兼容性良好。两者都不是可测陈述，下面全部转成可验收形式。

| 需求 | 可验收指标 |
|---|---|
| ① 无性能瓶颈 | 单 tick 耗时与订单份数**解耦**：任意份数下逐份阶段的单 tick 成本有**常量上界**（默认 64 份 ≈ 3ms），tick 峰值不超 50ms 预算 |
| ① 无性能瓶颈 | 订单份数从 1e5 变到 1e9，单 tick 处理量**恒定**（不随 `remaining` 增长，也不随之下降） |
| ① 无性能瓶颈 | 摊销路径仍为 O(1)：一份与 1e9 份单 tick 成本同阶，不得退回逐份 |
| ② 兼容性良好 | NeoECO 路径行为逐项不变（`eco$getAvailableParallelSlots` 仍返回 int，交接语义不变） |
| ② 兼容性良好 | 不引入 mixin、不新增硬依赖；未设系统属性时默认值安全 |
| ② 兼容性良好 | 接近 `Long.MAX_VALUE` 的输入不崩溃、不截断、不丢数据、不复制 |

**兼容性矩阵**（“兼容性良好”逐维度拆开，每维一条保障）：

| 维度 | 保障措施 |
|---|---|
| NeoECO | adapter 签名保持 int；批量交接语义与现在逐条对齐 |
| 万象构序 | 维持现状不接；将来接需走 P2 条件 mixin（见条目 I） |
| AE2 | 仅用公共 API；全库无 CPU Mixin（`CraftingCpuLogic` 零引用） |
| 第三方附属 | 无 mixin、无反射新增、无新增硬依赖 |
| 配置 | 新增参数默认值保守（不改变现有手感）；照 `logEffectiveTuning` 模式记录被覆盖的值 |
| 存档 | NBT 格式不变（`cachedSlots`/`assembled` 均为运行时缓存，不参与序列化） |

**风险与回退**：

| 风险 | 缓解 |
|---|---|
| 预算过小 → 大订单跳多 tick，手感变慢 | 可配置；默认量级与既有 `OUTPUT_RETURN_TICKS` 平滑逻辑一致 |
| 预算过大 → 慢机器仍卡 | 叠加时间预算兜底（P0 的备选项） |
| long 化引入行为回归 | 逐项对拍 NeoECO 路径；保留 int 版查询方法 |
| 拆堆保护影响掉落完整性 | 分批掉落而非丢弃 |

**验收清单（DoD）**：① 1e9 份订单单 tick 峰值 ≤ 50ms；② 单 tick 处理量与份数解耦；③ NeoECO 批量交接回归通过；④ 极端值不溢出/不截断；⑤ `compileJava` + `compileClientJava` 通过；⑥ 数据完整性（无丢失/复制）。

#### 可用范本：项目内已有的两处预算控制

P0 不应新造范式，同文件里已有两处“每 tick 限量”的先例，照抄即可：

- `drainOutputs`（`:1387`）：`rate = 总量 / OUTPUT_RETURN_TICKS`，每 tick 只回送一份额度；
- 同类思路还体现在 `shortRunStreak` / `IDLE_RESET_WINDOWS` 的窗口退避上。

### K. 样板磁盘存储策略审视（内存风险与 1024 截断）— P2 ⬜（2026-09 记录，已审视待决定）

- **问题**：单盘上限 1024，一个标准样板磁盘供应器是否因 1024×9 而爆内存？是否需要改为 UUID 加载策略？
- **结论**：① **不会爆内存**（当前缓存设计正确）；② **UUID 方案不必要**；③ 真实缺陷是 **1024 截断**（功能而非内存）；④ 真实热点是 **`computeFingerprint`**。
- **存储链路（实测）**：
  - 磁盘 `ItemStack` → `DataComponent: PatternDiskContents { type, List<ItemStack> patterns }`（上限 1024，写路径整体替换 record）
  - `PatternDiskProviderLogic` 构造 `super(mainNode, host, 1024)` ← **固定 1024 槽**（不是 1024×9）
  - `refreshPatternsFromDisks()`：`computeFingerprint` → 收集所有磁盘的样板 → 写前 1024 个进 `patternInv` → `updatePatterns()`
  - 解码：`PatternClassifier.decodedStored()` → `WeakHashMap<PatternDiskContents, DecodedPatterns>`
- **内存量化（估算，非实测）**：

| 项 | 数量 | 量级 |
|---|---|---|
| 磁盘内容 DataComponent | 9 × 1024 ItemStack | ~2–5 MB |
| `patternInv` 镜像 | 1024 个副本 | ~0.4 MB |
| 供应器解码结果 | 1024 个 `IPatternDetails` | ~1–3 MB |
| 解码缓存 | **9 个条目**（按盘）× ≤1024 | ~9–28 MB |

  - **弱键** ⇒ 缓存与磁盘同生命周期，磁盘移走后条目可被 GC，无无上限增长；
  - **按键共享** ⇒ 多台供应器插相同内容的磁盘只解一份，内存**不随机器数线性增长**，而是随**不同磁盘内容的数量**增长；
  - 真正累积场景：大量**内容各不相同**的满盘同时存在（全服磁盘内容总量决定规模）——这一点 UUID 也救不了。
- **UUID 方案否决理由**：目标问题（避免全量材料化驻留）已被内容快照缓存解决；UUID 会引入全局注册表、UUID→内容 的持久映射与存档迁移，且 AE2 的 `IPatternDetails` 本来就必须在应答 CPU 前解码，UUID 只是把解码推迟。
- **优化空间**：
  1. ⚠️ `computeFingerprint`（`:100`）对整盘组件 `stack.getComponentsPatch().hashCode()` 求哈希——满盘时相当于每次刷新遍历 9216 个样板的 NBT。因 `PatternDiskContents` 是 record 且**每次写入整体替换**，指纹可退化为“按槽记录 contents 实例、用引用比较”，O(9) 而非按 NBT 哈希。低风险、高收益。
  2. **1024 截断至少应可见**：当前是静默丢弃（第 2–9 张盘的样板不提供给合成系统，既不报错也不提示），玩家无从察觉超越。详见条目 F。
  3. 内存层面**无实质优化空间**（现状正确）。
- **关联**：条目 F（1024 截断行为待补文档）、批处理装配室与供应器共用同一套磁盘内容 API。

### L. 高级档没有专属标记，继承「进入本档之前那个常规档」的 — P3 ⬜（2026-10 记录，**已知行为，非缺陷**）

- **现状**：高级档（`advancedMode`，也就是物品 tooltip 里那个「高级处理」类型）不在 AE2 的 `EncodingMode` 枚举里，
  切进它时 **不改 `mode`**（`PatternDiskEncodingTermScreen.pickAdvanced()` 只 `setAdvancedMode(true)`），而绑盘用的
  `deriveMarkId()` 读的正是 `menu.mode` ⟹ **高级档下写出的标记就是「进入高级档之前那个常规档」的标记**。
  从处理档进高级档，写出的就是处理档的标记（`#mode:processing`，或导入过配方时那个 `#<配方类别>`）。
- **影响**：不崩、不丢东西；但有两层后果——① 磁盘列表/搜索里那张盘看起来像「处理样板」，玩家看不出它是在高级档下写的；
  ② 从处理档进入时，`matchesCurrentType` 会跟着恒为 false（`categoryForMode(PROCESSING)` 本来就是 null，
  `PatternDiskEncodingTermScreen:657-660`），于是「唯一目标自动落盘」这个便利也不生效——只能靠搜索栏筛盘。
  另：继承来的 `#mode:processing` 会让该盘在搜索栏筛出它（或玩家直接点它）时成为写入目标，
  若其内容是高级处理样板，而在处理档写普通处理样板，写入会被类型锁拒绝并给回执
  （`disk_refused.type_locked`）——那是正常拒绝，不是异常。（处理档下「唯一目标自动落盘」本来就不生效，
  见上一条后果。）
- **⚑ 2026-10 补充（选盘口径已重构，本条只剩写盘侧成立）**：选盘现在以**样板类型**为锚点——标记先
  反查成它代表哪类样板，没标记就用类型锁（见 `docs/ENCODING_MODES.md` §七）。于是「继承来的标记会把盘引到
  错误匹配项」这条路径自然断掉：继承标记反查出来是**常规档**类型，与高级/过载档编出的类型不同，不是匹配。
  所以“给高级档补一个专属标记”仍是未做的取舍（仍是只加在写盘侧），但不再是“匹配便利不生效”的理由。
  别再按本条的旧叙述把选盘改回“某档不认标记”那种特例规则。
- **为何暂不做**：给高级档一个固定的 `#mode:adv_processing`，面与雕凿那一轮完全相同：`DiskMarkRules`（常量与显示名）、
  `uploadMark()` 分支、`PatternDiskMarks` 识别、`matchesCurrentType` 匹配、中英文案。雕凿已于 2026-10 补上专属标记
  （`#mode:chiseling`），**高级档当时按用户要求未动**。
- **⚠ 若要做，先定一个取舍**：雕凿的 `uploadMark()` 是**先短路再回退** `deriveMarkId()`，所以它天然顶掉了
  「导入过配方就写 `#<配方类别>`」那一支。高级档照抄就会把那一支也顶掉——而它存在的理由正是
  「把同一台机器下的不同类别分开」（见 `deriveMarkId` 的 javadoc）。所以先决定：高级档要不要也放弃类别信息？
  （要保留的话，得让固定标记只在「没导入过」时生效。）
- **若要做，照雕凿那一套改**，但注意四个位置约束（雕凿那轮踩过）：
  · 常量放 `DiskMarkRules`，形状与 `CHISELING_MARK` 一致；**`modeMarkId` 不需要重载**（枚举拿不到高级档）。
  · 分支**只加在上传路径**（`uploadMark()`），**不要改 `deriveMarkId()`**——右键打标有自己的语义。
  · `PatternDiskMarks.displayName` 的识别分支必须落在 `parseMode` 返回 null **之后**、`literal` 回落**之前**。
  · `matchesCurrentType` 的分支必须在 `categoryForMode` 早退**之前**，否则从处理档进入时 `category == null` 会先 return false，分支成死代码。
  · 别忘了 `PatternDiskMarks` 直接引用 `DiskMarkRules` 的常量，别再抄一份字面量。
- **⚠ 审查提示**：这是**已记录项**，不是新发现的缺陷。后续审查/代码检阅时不要再把它当 bug 报一遍。

### N. 上游 API 请求（ECO / ECO Prototype）— P3 ⬜（2026-10 记录）

- **由来**：元件管理终端要认出 ECO 本体与青春版的「元件座 / 存储主机」，而这两家都进不了编译面（青春版是纯运行时可选；本体虽 compileOnly，但可依赖面只有 `cn.dancingsnow.neoecoae.api`，驱动器与存储主机那一层不在其中）。于是 `common/menu/CellDriveScanner.java` 现在全走**按方法名反射探**。能跑，但三处契约只能实测反推：清空要传 `null`（`ItemStack.EMPTY` 被静默拒收）、写入成败无返回值可依（靠回读兜底）、取不出来时无法区分「没装元件」与「被锁住」。
- **已备好的草稿**：`docs/UPSTREAM_API_REQUESTS.md`。两份可直接粘贴。**状态：已提交** 2026-10-09（UTC `2026-10-08T18:55Z`）——ECO [#119](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/119)、青春版 [#5](https://github.com/reliqwq/NeoECOPrototype/issues/5)（提交账号 `xingluo01`，两条均 `OPEN`、各带我方 1 条补充评论）。两个仓库的既有 issue **以中文为主**（青春版 open 全中文；ECO open 14 条里 13 中文 + #103 英文），故正文用中文。
- **提交后补的评论（提高采纳率，要点已存档于文档）**：① 告诉 ECO 「`ICellHost` 可新增 `api` 接口 + 旧接口 `extends` 它」以保二进制兼容；② 说清**我们只需要读写那个 int，不需要上游提供界面**，且 `IPriorityHost extends ISubMenuHost`（还得 `returnToMainMenu` + `getMainMenuIcon`、而它界面是 LDLib）⇒ 预期产出是「公开 `setStoragePriority(int)`」，不是 `IPriorityHost`；③ 提取原因可用中性返回替代提嵌套枚举。给青春版那条则提醒 `setCellStack` 若直接委派 `insertCell` 会变成「静默忽略替换」。
- **请求清单**（签名均取自实际 jar 的 `javap`，CF file 9095600 / 9098136）：
  - 【本体】把 `util.ICellHost`（`setCellStack`/`getCellStack`/`isItemValid`/`canExtractCell`）提到 `api` 包并注明是稳定集成点；`setCellStack` 改返 `boolean`（或写清空栈/`null` 语义）；把 `getCellExtractionBlockReason()` 从实现类提到接口上。
  - 【本体】存储优先级缺公开写入口：`ECOStorageSystemBlockEntity` 只有 `getStoragePriority()` 公开，`setStoragePriority(Player,int)` / `changeStoragePriority(Player,int)` 都是 `private`；且该类**未**实现 `IPriorityHost`。请求给公开 `setStoragePriority(int)`，或实现 `appeng.helpers.IPriorityHost`。
  - 【青春版】`SimplifyDriveBlockEntity` 已有 `getCellStack`/`isItemValid`/`insertCell`/`removeCell`/`hasCell`/`getCellInventory`，**只差一个 `setCellStack` 就能 `implements ICellHost`**。请求加上它（前置依赖：本体先把 `ICellHost` 提公开；或仅提供同名同签名方法，无需真 `instanceof`）。
  - 【青春版】存储优先级那侧已经够用：`SimplifyStorageHostBlockEntity` 的 `get/setStoragePriority(int)` 均公开。仅可选请求：驱动器→主机的直接访问器（现为 `getCluster().getController()`）、存储主机实现 `IPriorityHost`。
- **如果上游提供了 API，本地该怎么改**（按请求逐项对应，未提供则保持现状）：
  1. **`ICellHost` 进 `api`（本体）**：新增 `integration/neoecoae/NeoECOCellHosts.java` 做编译期类型化适配，核心 `CellDriveScanner` 只调接口、不再按名字探；`clearCellStack` 的三路全试（`setCellStack(null)` → `setCellStack(EMPTY)` → `removeCell()`）收敛成一次文档化调用；删除 `invokeItem(owner, "insertCell", …)` 与 `Boolean.TRUE.equals(...)` 那两条探针。
     **怎么做到「不加载」**（这是本条的关键，不能只写一句“适配器不能硬依赖”）：`CellDriveScanner` 是无条件加载的 common 代码，只要它出现 `instanceof NeoECOCellHosts`，解析该类时就会连带解析其父接口 `ICellHost` → 缺 ECO 时 `NoClassDefFoundError`（`Class.forName(...).cast()` 同理会抛，且必须包 try）。做法照本仓既有分层：①在本项目定义中立 SPI（如 `common/menu/CellHostAccess`，方法签全用 `Object`/自有类型：`handles(owner)` / `extract(owner)` / `insert(owner, stack)` / `readPriority(owner)` / `writePriority(owner, v)`），`CellDriveScanner` 只持一个 SPI 字段且**优先**走它；②在 `NeoECOIntegration.apply()`（只在 ECO 在场时被它实例化）里注册实现；③`NeoECOCellHosts` 内部才 `instanceof ICellHost`。这样对 `ICellHost` 的引用被封在“ECO 在场”这一条件下。
     顺带：若 `CellDriveScanner` 直接引用 `ICellHost`，`compileOnly` 面就不再只是 `api`（等于承认 `util` 也是依赖面），与本 issue 的立论矛盾。
  2. **`setCellStack` 返 `boolean`**：取放的成功判定可以直接用返回值；回读那一层仍保留（防第三方），但不再是唯一判据；`CellDriveScanner` 里「null vs EMPTY」那段踩坑注释可删。
  3. **提取原因提到接口**：`CellManagementTermMenu` 取不出来时按原因给回执（新增 lang 键：被锁定 / 被无限盘占用 / 不允许取出…），替代现在统一的「该槽位为空」。**前置**：这条比看起来重——`CellExtractionBlockReason` 是实现类的**嵌套枚举**，要上接口得先把枚举提到 `api`（或改用中性返回，如 `@Nullable Component`）。上游若不提供，就维持统一回执。
  4. **优先级写入口公开（或实现 `IPriorityHost`）**：**两条上游选项都是零代码改动**——按名字探的 `getMethod("setStoragePriority", int.class)` 只看可见性是否 public，不看它原来是不是 private，所以上游把它改成 public 会自动命中（同理，实现 `IPriorityHost` 会命中 `canSetStoragePriority` 的 `owner instanceof IPriorityHost` 分支）。要改的只有**文案**：`notice.priority_unsupported`、`CellDriveScanner` / `CellManagementTermMenu` 的注释、本文件里「本体只读」的说法。**真正需要动探针的只有一种情形**：上游给的是别的名字/别的签名（例如 `setPriority(int)`，或走接口式入口）。
     另一件值得知道的事：`IPriorityHost` 对上游而言不只是两个方法——它 `extends ISubMenuHost`，还要 `returnToMainMenu` + `getMainMenuIcon`，而 ECO 的存储界面是 LDLib、没有 AE2 菜单可回。所以**更容易被接受的是「公开 `setStoragePriority(int)`」**；`IPriorityHost` 只是为了让生态里其它终端也能直接复用（我们自己的优先级屏宿主是面板部件，不需要上游提供屏）。
  5. **青春版实现 `ICellHost`**：与第 1 条同一条路，但**约束更紧**——青春版连 compileOnly 都没有（纯运行时可选），因此**只能走第 1 条那个 SPI 注册路线**（在 `NeoECOIntegration` 里注册，因该类的加载本就以 ECO 在场为前提），否则就永远保持名字探。删掉 `insertCell`/`removeCell` 两条名字探针与 `invokeItem` 助手后，`storageController` 的 `getCluster().getController()` 链在拿到直接访问器后可简化。
     两个易错点：①若上游把 `setCellStack` 实现为「非空 → `insertCell`」，那我们清格应传 **`ItemStack.EMPTY`**（不是 `null`，除非对方明确接受 null）；②**回读必须保留**——接口的 `setCellStack` 是 void，且占用时可能静默忽略替换（ECO 自己的 `setCellStack` 是无条件替换，`insertCell` 在占用时返回 false，两者语义不同）。建议请上游把“替换还是忽略”写进 javadoc。
  6. **无论哪条先落地，都要同步的收尾**：
     - `docs/UPSTREAM_API_REQUESTS.md` 的「提交记录」表保持最新（首个版本已回填：ECO #119 / 青春版 #5）；后续若再提新需求或修订措辞，一并回填；两张表都记 **UTC 时间戳**（GitHub 时间线是 UTC，本轮是 `2026-10-08T18:55Z`，对应本地 10-09 02:55）。
     - 条目状态字段（`OPEN` / 已回复 / 已实现 + 版本号）随进展更新。
     - **“两条独立落地”的组合态**：只落一条时，`invokeItem` 助手与 `clearCellStack` 的三路都**不能**删，每条方案都要写清“另一条未落地时的回退形状”。
     - 青春版**不追求**编译期类型化（那要给它加 compileOnly，与它当前的定位不符）：它那侧最多走到 SPI 实现。
     - `docs/ARCHITECTURE.md` 的集成表若列了可选集成面，同步。
- **已检索并确认上游无等价 API**（免后来者重查）：ECO 全仓 `IPriorityHost` **0 命中**、`command/` 无 priority、`api/`（含 `api/storage/`、`api/integration/`）无 `Priority`/`CellHost` 类型；唯一能写优先级的就是 `private void setStoragePriority(Player,int)`，且只被自家 LDLib 的 `StorageHostActionUI.Config` lambda 驱动。青春版无 `setCellStack`（只差这一个方法就能 `implements ICellHost`；其 `ICellHost.canExtractCell()` 是 `default → true`）。
- **顺带核实（2026-10-09，结论：原怀疑已证伪，勿再按 bug 处理）**：曾怀疑 `NeoECOIntegration` 的类 javadoc（「NEO ECO 编译期就依赖本模组的 `PatternDiskApi`」）与上游不符——**javadoc 属实**。上游 `neoecoae` 21.2.1 的 jar 里含 `cn/dancingsnow/neoecoae/integration/ae2pattern/`（`AepdPatternDiskBackend`、`PatternDiskIntegration`、`MachineDiskHostAdapter`、`PatternDiskSupport` 等），release tag `21.2.1` 源码侧同样有 `AepdPatternDiskBackend.java`（`import io.github.lounode.ae2pattern.api.PatternDiskApi`），且其 `mods.toml` 把 `ae2_pattern_disk` 声明为可选依赖。**磁盘侧不是回归。**
  当时为什么会误判（教训，值得记）：①`unzip` 抽取**静默失败**（目标目录里 0 个文件）而没查产物，于是随后的 `grep -r` 扫的是空目录 → 假阴性；②第二发又改成直接 `grep` jar 字节，而 zip 条目是压缩的，class 常量池里的字符串不以明文存在 → 必然搜不到。**结论：搜 jar 内容必须先解包并核对产物存在，不要用 `grep` 直接扫 `.jar`。**

## 四、执行约束
- 目标：NeoForge 21.1.241 / MC 1.21.1 / JDK 21 / AE2 19.2.18（编译口径取 `gradle/libs.versions.toml` 的 `ae2 = "19.2.18"`，`build.gradle` 走 `libs.ae2`；`gradle.properties` 中的 `ae2_version` 仍是未使用的历史键，现值恰好也是 19.2.18）
- 只用 AE2 公共 API；机器美术资源统一放本项目 `assets/ae2_pattern_disk/textures/`，不直接引用 `ae2:` 纹理（借用的复制件见 README 授权表；零件/物品显示模型仍继承 `ae2:item/display_base`、`ae2:part/display_off`、`ae2:item/cable_interface`）

## 五、发布配置（CI，2026-09 记录）
- 已就绪：`.github/workflows/release.yml`（推 `v*` tag → 构建 → Modrinth → CurseForge → GitHub Release）、`build.gradle` 接入 `com.modrinth.minotaur` 2.9.0、仓库 secret `MODRINTH_TOKEN`（PAT，勾 `VERSION_CREATE` + `VERSION_WRITE` + `PROJECT_WRITE`）
- 发布记录 **2026-09-16**：项目 `ae2-pattern-disk`（id `rtvtr6bT`）已提交审核（`requested_status=approved`，通过前仍为 draft）
  - **0.2.0** 已发布：version id `he2tuTEn`，357,560 bytes，loaders `neoforge` / game version `1.21.1` / release，已声明 required 依赖 AE2（`XxWD5pD3`）
  - 发布方式：本地 `MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=ae2-pattern-disk ./gradlew modrinth`（CI 未就绪时的可用后备路径）
  - **CurseForge 0.2.0** 已上传：fileId `8896981`，357,560 bytes，`isAvailable=False`（CF 新项目首个文件强制人工审核）
- **0.2.1（CI 首发验证）** ✅ 2026-09-16：推 `v0.2.1` 后 workflow **一次跑通**（15/15 步骤 success，零重试）
  - Modrinth：version id `2yxVhpjq`，357,441 B，已带 required 依赖 AE2（`XxWD5pD3`）——证明 `modrinth { dependencies }` 生效（0.2.0 那条是手工 PATCH 的）
  - CurseForge：上传步骤绿（该 Action 失败会 setFailed），文件进入人工审核（`latestFiles` 暂为空，与 0.2.0 同）
  - GitHub Release：`v0.2.1`，附件 `ae2_pattern_disk-0.2.1.jar`，正文为完整 47 行 changelog——证明 `--match 'v[0-9]*'` 与 `body:` 两处修复生效（否则会只剩 1 行）
  - 结论：**发版流程定型** —— `git tag vX.Y.Z && git push origin vX.Y.Z`，Modrinth/CF/GitHub 三平台全自动
- **0.2.3（2026-09-17）** ✅ 三端一次跑通：run [35246426042](https://github.com/xingluo01/AE2-Pattern-Disk/actions/runs/35246426042)，15/15 步骤 success，零重试
  - 内容：批处理装配室自适应供料窗口（上一批 <8 件 → 短窗 1 tick；每 32 次小批运行整窗探针；久停 8 窗口清零分类）＋缓存栏闲置退回＋被 CPU 索要的键优先返回；编码终端配方输入槽补可合成「+」角标；README/指南口径同步、批处理装配室纹理改按作者自有 ARR 表述
  - Modrinth：`Publish to Modrinth` 步骤绿（version 已创建）；项目仍为 draft，未认证访问公开 API `/v2/project/ae2-pattern-disk` 与 `/v2/project/rtvtr6bT` 均 404，属预期（对照 fabric-api 返回 200，确认是 draft 不可见而非网络问题）
  - CurseForge：`project_id: 1698612`、`file_path: build/libs/ae2_pattern_disk-0.2.3.jar`，步骤绿（新文件仍进人工审核）
  - GitHub Release：`v0.2.3`，附件 `ae2_pattern_disk-0.2.3.jar`（362,017 B），正文 = `v0.2.2..v0.2.3` 的 5 条 commit subject
  - `fail_on_unmatched_files: true`：未触发（产物名与 `-Pmod_version=0.2.3` 匹配，证明该保护不误杀正常产物）
  - **itsmeow 步骤实际表现已核实**：仍输出 `##[warning]Node.js 20 is deprecated … forced to run on Node.js 24: itsmeow/curseforge-upload@v3.1.2`，**上传功能正常** → 与 9/23 时间线判断一致（属「声明不受支持」，非「不可用」）
  - 观察（非阻塞）：minotaur 的 `:modrinth` 任务在配置缓存下报 `invocation of 'Task.project' at execution time is unsupported`（`Configuration cache entry discarded with 5 problems`），但 BUILD SUCCESSFUL、发布正常；若日后 Gradle 收紧，可加 `--no-configuration-cache` 或升级 minotaur
  - 网页侧仍待人工（API 改不了）：Modrinth/CF 项目正文补一句「代码 LGPL-3.0，批处理装配室方块纹理与模型为作者自有 ARR」；Dependencies 标 AE2 required、Client/Server 环境标记、gallery 截图
- **CF 双 API 混用（踩坑记录，缺一不可）**：
  - **上传**走传统接口 `POST https://minecraft.curseforge.com/api/projects/{数字id}/upload-file`，header `X-Api-Token`
    - token 是**网站账户 token**（UUID 形式，在 `curseforge.com/account/api-tokens` 生成）——不是 console 的 API key
    - 路径**只认数字 project id**，用 slug 会 302 到错误页
  - **元数据查询**走 Eternal API `https://api.curseforge.com/v1/...`，header `x-api-key`
    - key 是 **console 的 API key**（`$2a$10$…` bcrypt 串，在 `console.curseforge.com` 生成）
  - **两者不通用**：拿 API key 去上传会报 `API token is malformed`；两个接口的版本编号体系也不同
  - upload-file 的 `gameVersions` 必须是**数字 id**，且只在传统接口 `GET /api/game/versions` 里有：
    `1.21.1=11779`、`NeoForge=10150`、`Client=9638`、`Server=9639`
    （少环境组会报 `must select at least one version from the environment group of versions`；
      Eternal 那边的 1.21.1 是 89/12735 等，传进去会被拒为 invalid dependency）
- **itsmeow/curseforge-upload（CI 用的 Action）行为**：`game_versions` 接受名称/slug，但只查表转 id，
  **不会自动补环境组**、**匹配不到会静默丢弃** → CI 里已改为直接写数字 id（`release.yml`）。
  另已加 `relations: 'applied-energistics-2:requiredDependency'`（用 CF 的项目 slug：该 Action 只发 slug，
  CF 文档里数字 id 属可选的 projectID 字段），让 CF 页也标出“需要 AE2”；
  0.2.0 那份手工上传的文件没有这条，下一个版本的 CI 会带上。
- 变量/密钥已全部配齐：vars `MODRINTH_PROJECT_ID` / `CURSEFORGE_PROJECT_ID` / `NEOECOAE_JAR_URL` / `MODDEVMCP_JAR_URL`；
  secrets `MODRINTH_TOKEN` / `CURSEFORGE_TOKEN`
- **CI 待验证**：`mod_version` 已上调 `0.2.1`（0.2.0 在两平台都已存在，重推旧 tag 会失败）；推 `v0.2.1` 即可整链路验证
- **CI 构建前置（已解决）**：`build.gradle` 的 `implementation('dev.vfyjxf:moddevmcp:0.3')` 属 compileClasspath，
  但该构件只在本机可解析（`settings.gradle:18-39` 的 composite-build 替换 / mavenLocal 里的 `0.3`），
  Central / NeoForged / modmaven / BlameJared 均无此构件 → CI 上 `compileJava` 必然失败。
  现已改为 `implementation files('libs/moddevmcp-0.3.jar')`，由 CI 按变量 `MODDEVMCP_JAR_URL` 下载（`libs/*.jar` 被 gitignore）。
- **私有 jar 供给（已解决）**：两个 maven 取不到的 jar——`neoecoae-21.2.0-beta5.jar`（4,863,005 B，sha256 `07a6eedd…`，从 fork 分支构建，版号与官方同名但内容不同；2026-09-22 为修一处构造期 NPE 重建过一次，哈希随之变化，`release.yml` 里那条校验已同步更新）
  与 `moddevmcp-0.3.jar`（583,588 B，sha256 `ce22ff72…`）——托管于本仓库 release `deps-v1`，对应
  `NEOECOAE_JAR_URL` / `MODDEVMCP_JAR_URL`。注意该 release 的 tag 落在 `v0.2.1^` 上，故 changelog 步骤已加
  `--match 'v[0-9]*'`，避开它被 `git describe` 当成上一个版本而把发布说明截成一行。
- **ModDevMCP 供给方式待清理（不影响发版）**：`build.gradle` 里 `localRuntime 'dev.vfyjxf:moddevmcp:0.1.6'` 仍在，
  而本机 mavenLocal 并无 `0.1.6`（靠 composite-build 替换），与新增的 `files()` 可能让 dev 运行时出现两份副本；
  建议单独一轮处理，并在本地跑一次 `runClient` 确认无重复 mod 载入。
- **CI action 运行时（2026-09）**：Node20 于 **2026-09-23** 从 runner 移除，已把四个声明 node20 的 action 全部升到 node24：
  `actions/checkout@v4→v7`、`actions/setup-java@v4→v6`、`gradle/actions/setup-gradle@v4→v6`（并加 `cache-provider: basic`
  指回“基于 GitHub Actions cache 的开源实现”，避开 v6 默认的专有缓存组件 ToU；**注**：v6 的 `cache-read-only` 在非默认分支下
  恒为 true，而本仓库只有 tag 触发的 workflow ⇒ 这项配置**没有实际缓存收益**，纯属许可/兼容考虑）、
  `softprops/action-gh-release@v2→v3`；另给 release 步骤加了
  `fail_on_unmatched_files: true`（产物路径写错时硬失败，而不是静默生成没有附件的 release）。
  - **实跑验证**（临时 workflow_dispatch，`github.ref_name=main`，run `35134483389`）：10/10 步骤 success；
    这三条已升级 action 上的 “Node 20 is being deprecated” 警告**消失**（`itsmeow` 步骤仍为 node20，
    真实发版时仍会输出该警告，直到 9/23）；`cache-provider: basic` 生效
    （日志 “Basic Caching: This build uses the basic open-source caching provider”）；Gradle 正常执行（9.5.0）。
    验证用 workflow 已删除，不进发布链路。
  - **注意该 run 的路径与发版不同**：它从默认分支触发 ⇒ `cache-read-only=false`（**可写**缓存），
    而真实 tag 发版走 `true`（**只读**、不写缓存、无收益也无失败）。
  - **备选方案（仅在 `-D` 覆盖失效时启用）**：临时验证中发现 `-Dorg.gradle.java.home=...` 在某上下文未生效
    （`--version` 通过、`tasks` 报 `invalid org.gradle.java.home: C:/Java/jdk-21`），而用
    `sed -i "s|^org.gradle.java.home=.*|org.gradle.java.home=$JAVA_HOME|" gradle.properties` 直接替换可通。
    但 `release.yml` 现用的 `-D` 写法已被 v0.2.1 真实发版证明有效（15/15），**不要改动该路径**；
    若某天失效（表现为硬失败 `invalid org.gradle.java.home`），可切换到 sed 写法。
  - **「下次正式发版后」的收尾项（v0.2.3 已完成）**：见上方 0.2.3 记录 —— run 链接已补、`fail_on_unmatched_files` 未触发、itsmeow 仍输出 Node 20 弃用警告但功能正常。
  - **遗留**：`itsmeow/curseforge-upload@v3.1.2` 仍是 node20，上游最后提交 2024-04、无更高版本可升。预案（推荐序）：
    ① **fork 后改 `runs.using: node24` 并 pin commit SHA**（首选：上游自 2024-04 起零活动，等不到合并）；
    ② 自写 curl 直连 CF 传统上传接口（最彻底解耦，该接口与数字 id 本项目已在用，见上方记录）；
    ③ 换 `Kir-Antipov/mc-publish`（**用前必须核凭据类型**：它走 Eternal API 的 `x-api-key`，
    与我们现在的站点 UUID token 不是同一种，另需核版本参数语义）；
    ④ 给上游提只改 `runs.using: node24` 的 PR（礼节性动作，不作为阻塞路径，合并概率极低）。
  - **时间线基线**：GitHub 自 **2026-06-16** 起让 runner 默认跑 Node24（node20 声明被自动迁移执行），
    **2026-09-23** 才是“移除 Node20 / opt-out 失效”。v0.2.1（9/16）已带着该 action 成功跑过一次
    ⇒ 它在 Node24 下**已实测可用**；9/23 的风险是“声明不受支持”而非“首次不可用”，下次发版核对即可。
- 网页侧待办（API 改不了，需人工）：项目 Dependencies 标 AE2 为 required、Client/Server 环境标记（现为 unknown）、gallery 截图
- 依赖项对照：AE2 = `ae2`（id `XxWD5pD3`）、GuideME = `guideme`（id `Ck4E7v7R`）
- 发版注意：首次建议先开 `build.gradle` 的 `debugMode = true` 干跑 Modrinth 再发正式版；重跑同一 tag 时 Modrinth 会因版本号已存在失败、CurseForge 不去重会再传一份，中断后优先改版本号重发
- **CHANGELOG 未发布段需发版前改名**：`## [未发布]` 必须在发版前改成 `## [<版本号>] - <日期>`——CI 的 `awk` 是按 `[版本号]` 匹配取段的，取不到会静默回退成 commit log 拼接，发布说明会变成一堆提交标题。
- **高效分子装配室的回送方向不持久化**：`CraftUnit.pushDirection` 不进 NBT（save/load 只存网格、样板与进度），重载后一个尚未收工的供应器派发页会退化成「只回网络」。产物不会丢，只是会绕过相邻返回节点，与 AE2 契约有偏差；若要严格对齐，需把方向随 CraftUnit 一起存。

### M. 自装配样板磁盘供应器面板变体（`cable_meteorite_pattern_provider`）— ✅（2026-10 记录）
- 已实现：`common/part/MeteoritePatternProviderPart.java`，与方块共享 `MeteoritePatternProviderHost`、`SelfAssemblingPatternDiskProviderLogic`、`MeteoritePatternProviderMenu`；注册、模型、物品模型、lang、创造页与两向互换配方都在 `MeteoritePatternProviderRegistrations` / `AE2PatternDisk` 的 AE2CS 门禁内。
- 与方块形态的两处差异：
  - **产物回送走服务端世界 tick 事件**（`LevelTickEvent.Post` → `pumpCraftedContents`；面板在 `addToWorld`/`removeFromWorld` 里登记进一张弱引用表）。**不能占 `IGridTickable` 服务槽**：那个槽归 AE2 的 `PatternProviderLogic`（它在自己构造器里 `addService(IGridTickable.class, new Ticker())`，返回仓注入网络、待发送缓冲重试、「锁定到结果」的解锁回调全挂在那个 Ticker 的私有 `doWork()` 上），而 `addService` 是同类唯一实例（覆盖语义）。第一版面板抢了这个槽，被审查抓出：面板的字段初始化器先建逻辑、构造器体随后覆盖，于是供应器的推送/回送那条路静默失效。
  - **贴附面顶替「推入方向」里的方位参照**（顺序固定：贴附面 → 全向 → 其余五面 → 回贴附面，换档在动作栏报一声）。
- 已知取舍：
  - 面板模型 `models/part/meteorite_pattern_provider_base.json` 继承 `ae2:part/pattern_provider_base`，但把五个贴图变量**全部**改指本项目自己的文件（正面 `block/meteorite_pattern_provider`；侧面/状态面/背面/粒子用 `part/pattern_disk_provider_*`，即 AE2 那几张的本地副本、已在 README 授权表内），不再引用 `ae2:`/`ae2cs:` 贴图；物品模型同口径，因此图标与放置后的侧面是同一张。代价：背面用的是 `pattern_provider_back`，与最初那份模型文件里指定的 `ae2cs:block/meteorite_pattern_provider/back` 相差 14% 像素（同一张灰背板）；要换成后者，把那张复制为 `part/meteorite_pattern_provider_back.png` 并改两处指向即可。
  - 升级库存的卡的上下限按**方块物品**算（`SelfAssemblingPatternDiskProviderLogic` 里 `UpgradeInventories.forMachine(BLOCK, 4, ...)`），面板不单独登记速度卡/超频卡；两形态同为 4 张，行为一致，但以后若要让两形态上限不同，得把机器物品改成构造参数。副作用：按物品反查「谁吃这张卡」的地方（升级卡提示、JEI/EMI 升级卡页）只会列出方块形态。
  - `GENERIC_INTERNAL_INV` 已在 `AE2PatternDisk#registerPartCapabilities` 里按面板类补登记（AE2CS 缺席时那段不执行）。
  - 两形态 1:1 互换会把「推入方向」重置回贴附面：方块方向存在 AE2 的 `directionMap`（blockstate），面板方向存在自己的 NBT `pushDirection`，互换时两者不同步（行为可接受，仅记录）。
- 待实机确认：① 处理样板（不可自完成）能否推给相邻机器；② 相邻机器回送到返回仓的产物能否自动清空；③「锁定到结果」能否解锁；④ 网络塞满时的滞留产物能否最终送回；⑤ 面板在样板访问终端里的收录与图标。
