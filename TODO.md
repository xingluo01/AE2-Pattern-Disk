# AE2-Pattern-Disk 下一轮需求待办表

> 状态图例：⬜ 未开始 | 🔄 进行中 | ✅ 完成 | ⏸ 暂缓
> 优先级：P1 最高 | P2 | P3
> 美术资源约定：相关机器美术资源先借用源机器（已复制到本地路径），后续补票替换。

## 一、本轮已完成（0.1.1）

### 1. 独立创造标签页 — P1 ✅ 完成
- ✅ 创建 `AEPatternCreativeTabs`（CreativeModeTab，6 物品归入 + 图标）
- ✅ lang 补 `itemGroup.ae2_pattern_disk`（zh/en）

### 2. 磁盘纹理随配方类型变 — P1 ✅ 完成
- ✅ `PatternDiskItem.typePropertyValue()` + `InitPatternDiskProperties` 注册 item property `pattern_type`
- ✅ 磁盘 base 模型 overrides 按类型切 4 个类型模型
- ✅ 纹理复制到本地路径（crafting/processing/smithing/stonecutting + certus）

### 3. 分子装配室（高效分子装配室）— P2 ✅ 完成
- ✅ `PatternDiskAssemblerBlockEntity`：`ICraftingMachine` + `IGridTickable` + 8 线程 `CraftUnit`（3×3 网格 ×8）+ 加速卡（speed 分级）
- ✅ 配方来源：接受磁盘供应器配方，`getAvailablePatterns` 合并；产物回送相邻 + 网络 + 余料（getRemainingItems）+ 能量判定
- ✅ capability 注册（`CRAFTING_MACHINE` + `IN_WORLD_GRID_NODE_HOST`）+ `associateBlockEntities`
- ✅ 模型复制 AE2 分子装配室 blockbench 几何（本地纹理）+ blockstate + item model + lang + 数据包配方
- ✅ 命名：**高效分子装配室**（zh）/ Efficient Molecular Assembler（en）
- ✅ **性能优化**：`CraftUnit.craftingInv` 持久缓存（消除每合成对象 churn）；`pushOut` 相邻+网络双通道
- 美术：先复制 AE2 纹理，后续补票定制

### 4. AECS 自装配式样板供应器兼容 — ✅ 完成（PR #75 已创建）
- AECS（ExtremelyFrozen/E, 1.21.1）补丁：`DisksMeteoritePatternProviderLogic`（一槽二用 + ModList 门控 + Codec 格式契约）+ BE + 菜单 `DiskAwarePatternSlot`
- PR：**https://github.com/ExtremelyFrozen/AE2-Crystal-Science/pull/75**
- 双 mod 运行时已验证（能同装 + 供应器可放置）

## 二、暂缓（后续排期）

### 5. 可视化磁盘内配方列表终端 — P3 ⏸
- 数据层已有 `PatternDiskContents`/`PatternClassifier`；需新终端机器 + 列表/增删交互
- 待用户明确载体（终端机器 vs 物品菜单）

### 6. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- 兼容 = 我们装配机实现 `ICraftingMachine`（已并入 #4 完成）
- EAE 矩阵 Pattern 槽硬过滤 EncodedPatternItem → 磁盘配方只能间接进矩阵（高难度 IAECluster，暂缓）

### 7. 存储瓶颈和性能优化 — ⏸ 部分
- ✅ 审计确认：无每 tick 泄漏（转存器速率闸门、供应器仅变更时重建）
- ✅ 装配机 craftGrid 容器复用（性能优化）
- ⏸ 完整紧凑编码（改 PatternDiskContents 数据格式）——高风险，破坏存档兼容，保留后续专轮 + 迁移

## 三、执行约束
- 目标：NeoForge 21.1.248 / MC 1.21.1 / JDK 21 / AE2 19.2.8
- 只用 AE2 公共 API（维持既有原则）
- 美术全部复制到本地路径（不引用源机器路径）
