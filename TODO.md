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

### 4. AECS 自装配式样板供应器兼容 — ✅（PR #75）
- AECS 补丁：`DisksMeteoritePatternProviderLogic`（一槽二用 + ModList 门控 + Codec 契约）+ BE + 菜单 `DiskAwarePatternSlot`
- PR：https://github.com/ExtremelyFrozen/AE2-Crystal-Science/pull/75

### 5. 存储性能（策略二：静态全量解析）— ✅
- ✅ 磁盘内容指纹缓存：内容未变→短路跳过全量重建（`refreshPatternsFromDisks`）
- ✅ `getTerminalPatternInventory` 缓存（未变复用 adapter）+ `clearContent` 失效
- ✅ 同 tick 多变化合并（指纹去抖天然合并）
- ✅ `CraftingTree`（产物→配方索引 + 需求链解析 `requiredOf`）——建树基础，备用

### 6. AE2WTLib 前置补齐 — ✅
- AECS 1.2.x 依赖 `de.mari_023:ae2wtlib`；dev 环境补齐 19.5.0 + `ae2wtlib_api`
- ✅ 启动自验通过（AE2 0.1.1 + AECS 1.2.2 + AE2WTLib 均加载，无崩溃）

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
- 现状：转存器用 io_port 风格（`pattern_transferer_*` + `pattern_transferer*.json`，由 `powered`（= ME 节点在线）驱动 off/on）；**批处理装配室改用 `batch_assembler_grid*`（取自 XingLuo_AE2_1.21_GUIExpansion 资源包的 AdvancedAE 量子合成器一套，模型 `batch_assembler.json` / `batch_assembler_on.json`，同样由 `powered` 驱动）**；供应器用 `pattern_provider*`；高效装配室用 `molecular_assembler.png`。其中转存器/供应器/高效装配室纹理已于 2026-09 局部重绘（已非 AE2 字节副本，README 授权表按像素比对分档）；转存器的 `_top.png` 与 side/back/bottom 仍是 AE2 原文件。批处理装配室旧的一套 io_port 风格 `batch_assembler_{front,front_on,top,top_on,side,back,bottom}.png` 已被 grid 一套取代并**已删除**（不再随包分发）
- 目标：为 4 个方块（转存器 / ME样板磁盘供应器 / 高效分子装配室 / 批处理装配室）绘制原创纹理并替换，同时同步 README 授权表（移除对应条目）
- 范围：先做借用纹理本地化与外观替换（不改现有几何资产；批处理装配室的模型 `batch_assembler.json` 为 `cube_all` 包装、`batch_assembler_on.json` 取资源包量子合成器发光壳几何），后续逐块替换为原创纹理
- 待确认：`textures/guis/pattern_modes.png`、`states.png` 与 AE2 同名件同画布但像素差异较大（~69% / ~58%），来源待作者确认后在 README 归类（现按 unconfirmed 列出）

### E. ME样板磁盘供应器面板变体（`cable_pattern_disk_provider`）— 🔄 进行中（2026-09 记录）
- 已实现：Part（贴电缆面板）形态，与方块共享同一 `PatternDiskProviderLogic` 与 `PatternDiskProviderMenu`（host 接口 `PatternDiskProviderHost`）；零模型与纹理借用 AE2 `cable_pattern_provider`（已本地化到 `models/part/pattern_disk_provider_base.json` 与 `textures/part/pattern_disk_provider*`）
- 待办：① 面板变体无独立合成配方，仅与方块形态 1:1 无序互转（`recipe/pattern_disk_provider_to_cable.json` 与 `recipe/cable_pattern_disk_provider_to_block.json`）；② ✅ 纹理已按本项目风格重绘；③ 编码终端/样板管理终端对 Part 形态的收录已由 AE2 源码证实（网格机器按 owner 具体类登记，Part 的 owner 即 Part 本身），待实机确认；④ 面板返还栏的 `GENERIC_INTERNAL_INV` 已注册（`RegisterPartCapabilitiesEvent`，待实机验证装配室回送）；⑤ ✅ 已修：指纹混入宿主 identity salt，面板用所贴面区分同电缆多面板；⑥ ✅ 已修：两形态掉落先清空镜像再走 `logic.addDrops(...)`——返还栏与**待发送缓冲**（已投未达的物品）都会掉落，镜像本身不入掉落；⑦ ✅ 已修：导入前清空镜像；导出也不再往内存卡写镜像样板（AE2 会写 pattern inventory 进卡，导入时按空白样板计价 → 白扣玩家空白样板）；⑧ 面板物品名后缀「（面板）」已去掉，与 AE2 原版面板/方块同名的做法一致

### F. 其余记录 — P3 ⬜
- **样板清单容量 1024 待补文档**：`PatternDiskProviderLogic` 构造时把内部样板清单固定为 1024 槽（`super(mainNode, host, 1024)`）；多张磁盘的样板总数超过该上限时的行为：代码为 `i < all.size() && i < patternInv.size()` 填充镜像 → **静默截断**（超出的样板不提供给合成系统，既不报错也不提示）→ 稍后把这个上限与截断行为补进 README/指南
- ✅ **代码收敛已完成**：`BatchAssemblerBlockEntity` 原先自建了一份 `PatternDiskRemoveInventory` + 匿名 sink，现已改用与方块/面板共用的 `PatternDiskTerminalView`——核心的"取走样板要扣网络空样板并真删磁盘配方"逻辑（`PatternDiskRemoveInventory`）全仓只剩一份；NeoECO 反射适配器（`NeoECOBusTerminalView`/`NeoECOBusDisks`）仍保留自带的空样板抽取，因为那条总线不是 action host，其抽取刻意不归属任何玩家

## 四、执行约束
- 目标：NeoForge 21.1.241 / MC 1.21.1 / JDK 21 / AE2 19.2.17（编译依赖口径；`gradle.properties` 中的 `ae2_version=19.2.8` 为未使用的历史键）
- 只用 AE2 公共 API；机器美术资源统一放本项目 `assets/ae2_pattern_disk/textures/`，不直接引用 `ae2:` 纹理（借用的复制件见 README 授权表；零件/物品显示模型仍继承 `ae2:item/display_base`、`ae2:part/display_off`、`ae2:item/cable_interface`）

## 五、发布配置（CI，2026-09 记录）
- 已就绪：`.github/workflows/release.yml`（推 `v*` tag → 构建 → Modrinth → CurseForge → GitHub Release）、`build.gradle` 接入 `com.modrinth.minotaur` 2.9.0、仓库 secret `MODRINTH_TOKEN`（PAT，勾 `VERSION_CREATE` + `VERSION_WRITE` + `PROJECT_WRITE`）
- 待配置（缺一不可，配齐后即可用测试 tag 跑全链路）：
  - Variable `MODRINTH_PROJECT_ID` —— Modrinth 项目建成后的 slug 或 8 位 id（预检 `ae2-pattern-disk` / `ae2_pattern_disk` 均未被占用）
  - Variable `CURSEFORGE_PROJECT_ID` —— CurseForge **数字**项目 id（项目须先在站点人工创建；首个文件要过人工审核才对玩家可见）
  - Variable `NEOECOAE_JAR_URL` —— `libs/neoecoae-21.2.0-beta3.jar` 的直链（CI 构建取用；建议挂成 GitHub Release asset）
  - Secret `CURSEFORGE_TOKEN` —— CurseForge 账号 API token
- 依赖项对照：AE2 = `ae2`（id `XxWD5pD3`）、GuideME = `guideme`（id `Ck4E7v7R`）
- 发版注意：首次建议先开 `build.gradle` 的 `debugMode = true` 干跑 Modrinth 再发正式版；重跑同一 tag 时 Modrinth 会因版本号已存在失败、CurseForge 不去重会再传一份，中断后优先改版本号重发
