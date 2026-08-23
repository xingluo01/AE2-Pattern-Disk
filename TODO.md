# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | 🔄 进行中 | ✅ 完成 | ⏸ 暂缓 | ❌ 已放弃
> 优先级：P1 最高 | P2 | P3
> 美术资源约定：相关机器美术资源先借用源机器（已复制到本地路径），后续补票替换。

## 一、已完成（0.1.1）

### 1. 独立创造标签页 — P1 ✅
- ✅ `AEPatternCreativeTabs`（CreativeModeTab，6 物品 + 图标）
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
- ✅ GUI：`PatternDiskAssemblerMenu`/`Screen` + 方块 `openMenu` + JSON 布局（AE2 风格 + 本地底图）+ lang

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

### 8. 可视化磁盘内配方列表终端 — P3 ⏸
- 数据层已有 `PatternDiskContents`/`PatternClassifier`；需新终端机器 + 交互
- 待用户明确载体

### 9. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- EAE 矩阵 Pattern 槽硬过滤 → 磁盘配方只能间接进矩阵（高难度 IAECluster，暂缓）

### 10. 完整紧凑编码（改 PatternDiskContents 数据格式）— ⏸ 高风险
- 破坏存档兼容，保留后续专轮 + 迁移

## 三、执行约束
- 目标：NeoForge 21.1.248 / MC 1.21.1 / JDK 21 / AE2 19.2.8
- 只用 AE2 公共 API；美术全部复制到本地路径
