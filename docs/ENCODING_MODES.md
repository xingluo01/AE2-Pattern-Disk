# 编码模式注册规范

给要加档位的人：编码终端里「当前在哪一档」这件事散落在**菜单、logic、屏幕、面板、样式文档、磁盘模型**六处，
中间还有几个只看代码看不出来的坑。本文是先写规范、再照着把现有三档对齐后的结果——加档请按本文的清单走。

---

## 一、档位分两类

| 类 | 是什么 | 现有 |
|---|---|---|
| **常规档** | AE2 的 `appeng.parts.encoding.EncodingMode`（**不可扩展**：`CRAFTING` / `PROCESSING` / `SMITHING_TABLE` / `STONECUTTING`） | 4 个 |
| **额外档** | 菜单上一个 `boolean` 字段（与常规档**并列**，不是 `EncodingMode` 的一员） | 高级 `advancedMode`、雕凿 `chiselingMode`、过载 `overloadedMode` |

**额外档的关键特征**：进额外档时 **`mode` 字段不变**，仍停在进入之前那个常规档。所以任何「读 `mode` 推断当前档」
的代码都是错的——编码分派必须**先判额外档、再落到 `PatternEncodingLogic.encodePattern()`**，否则会把输出栏那张
样板按上一个常规档编出去（高级/雕凿当初都在这上面栽过）。

---

## 二、档位注册表（屏幕侧的唯一事实源）

额外档在 `PatternDiskEncodingTermScreen` 里由**一张注册表**声明，互斥、下拉列表、可见性、`mode` 高亮全从它派生：

```java
private record ExtraTier(
        Blitter icon,                       // 下拉里的档位图标（states.png 一格）
        String nameKey,                     // 档位名（lang 键）
        @Nullable String patternTypeId,      // 该档编出的样板物品 id；本档拿它当「哪张盘属于我」的锚点
        @Nullable DiskEncodingModePanel panel,  // 该档的面板；null = 面板缺席，该档不进下拉
        BooleanSupplier available,          // 可用性：读菜单同步下来的字段，不自己判环境
        BooleanSupplier active,             // 当前是否停在这一档
        Consumer<Boolean> setter) {         // 怎么切进去/切出来
}

private final List<ExtraTier> extraTiers;   // 构造器里建好，顺序 = 下拉里额外档的排列顺序
private int activeTier();                   // 当前是第几个额外档；-1 = 常规档
private void closeAllExtraTiers();          // 互斥：一次收掉所有开着的
private void pickExtraTier(int index);      // 切到第 index 档
private Blitter currentModeIcon();          // 模式钮画哪个图标
```

派生规则（不要再手写这些）：

- **下拉内容**：`available` 为真且 `panel != null` 才进列表；选中态 = `active`。
- **常规档高亮**：`regular = !anyExtraTierActive()`——**不要**写 `!a && !b && !c`（加一档必漏）。
- **互斥**：由 `closeAllExtraTiers()` 统一收，**不要**在 `pickXxx` 里互相手工清（O(n²)，上一版就是漏了
  `pickMode`/`pickAdvanced` 两处：前者导致回常规档后编辑区一片空白，后者导致两块面板同坐标叠画）。
- **面板可见性**：常规面板 `visible = regular && mode == 该档`；额外档面板 `visible = 该档 active`。
- **模式钮的图标与提示语**：都取 `activeTier()` 对应的那项，不要写三元链。
- **选盘锚点是样板类型**（`tier.patternTypeId`）：磁盘属于哪一类样板，就用它去比。标记也不是另一条
  判据 —— 它是类型的另一种写法，两者用 `DiskMarkRules` 互转（见 §七的分层）。

---

## 三、加一个额外档的清单

| # | 位置 | 要做什么 |
|---|---|---|
| 1 | `PatternDiskEncodingTermMenu` | 加一对 `@GuiSync` 字段：`xxxMode`（是否停在该档）、`xxxModeAvailable`（是否可用） |
| 2 | 同上 | 加 `ACTION_SET_XXX_MODE` 常量 + `registerClientAction(...)` |
| 3 | 同上 | `setXxxMode(boolean)`：客户端先改本地显示字段 + 发 action，服务端写权威值进 `encodingLogic` 并做「进档初始化」 |
| 4 | 同上 | 暴露判档入口：`isXxxMode()` 或直接读字段（雕凿档就没有方法，编码路径直接读字段；与既有一致即可） |
| 5 | 同上 | 可用性：`hasXxxEncoder()`（扫升级槽的物品 id）+ 在 `broadcastChanges()` 里每 tick 回读；**不可用时立刻退档** |
| 6 | 同上 | `broadcastChanges()` 回读 `xxxMode` 与所有该档状态字段（服务端权威 → 客户端） |
| 7 | 同上 | `encode()`：**在 `patternEncodingLogic.encodePattern()` 之前**加该档分支 |
| 8 | `DiskEncodingLogic` | 字段 + getter/setter（setter 内 `saveChanges()`）+ `readFromNBT`/`writeToNBT`。**缺键要有默认值**，不能直接 `getBoolean`（会把缺失读成 false） |
| 9 | 新建 `XxxEncodingPanel` | 面板；子控件走 `populateScreen` 注册（见第四节） |
| 10 | `PatternDiskEncodingTermScreen` | 注册表加一项 + `createXxxPanel()` + 图标常量 |
| 11 | 两份 style JSON | `assets/ae2/screens/ae2_pattern_disk/pattern_disk_encoding_terminal.json` 与 `..._management_terminal.json` 都要加面板与滚动条键（无线两份靠 `includes` 继承） |
| 12 | lang 两份 | 档位名 + 该档 UI 文案 + 缺输入时的提示 |
| 13 | `docs/ARCHITECTURE.md` | 玩法/结构有变化时补一句 |

---

## 四、不变量（都是踩过的坑，不要绕开）

**`@GuiSync` 号必须跨整条继承链唯一。** 校验口径是「本类 ∪ 子类 ∪ **父链（含常量解析）**」的并集，
不是单文件 grep，也不能只看 `@GuiSync(数字)` 形态——父类用的是常量：

```
PatternDiskManagementTermMenu  →  PatternDiskEncodingTermMenu  →  MEStorageMenu  →  AEBaseMenu
   无 @GuiSync                       85–99 + 102/103              100(activeCraftingJobs)
                                                                   101(SEARCH_KEY_TYPES_ID 常量)
```

`DataSynchronization.collectFields` 沿继承链上溯收集，撞号会在**构造菜单时**抛
`IllegalStateException("... declares the same sync id twice: N")`——终端直接开不了。
本类已占 85–99 与 102/103，新档从 **104 起**。

**额外档的面板要无条件创建。** 可用性看升级槽里那枚编码器，那是每 tick 算的菜单字段，建屏时读不到。
面板挂样式键失败要 `catch`（`IllegalStateException`）——AE2 对缺失的 widget 键是**开屏那一刻抛异常**，
不是「不画」。面板缺席时该档不进下拉即可。

**子控件必须经 `populateScreen` 注册。** 复合控件的**子控件**（`AbstractWidget`）要交给
`populateScreen(addWidget, bounds, screen)` → `addRenderableWidget`，渲染、鼠标命中、悬停提示（AE2 会遍历
子控件收 `ITooltip`）才走原版那套。**自己摆位置、自己调 `button.render(...)` 是不行的**：那一下不把它们挂进
屏幕控件表，实测现象就是「按钮根本不出现」。

〔例外〕**完全自绘的非 widget 控件不受此限**：画在 `drawBackgroundLayer` 里、命中靠自己的 `onMouseDown` /
`getTooltip`（例：过载档每行那枚组件匹配开关、高级档那排方向按钮）。判据就是「是不是 `AbstractWidget`」——
不是就不用注册，也别去注册。

**`updateBeforeRender` 只对**可见**的复合控件调用**（`WidgetContainer`）。所以子控件的位置要在那里设，
同时必须在 `setVisible(false)` 里把子控件也置为不可见——否则切到别的档后它们继续浮在上面。

**可用性判据是升级槽里那枚编码器，不是「模组在不在」。** 与高级档同口径（`host.getUpgrades()` 扫物品 id）。
卡不在时那一档立即退出，否则面板会停在一个再也编不出东西的空档上。

**档位状态要跟着终端走。** 权威值存 `DiskEncodingLogic`（它随部件持久化），菜单只做每 tick 回读与同步；
客户端点一下先改本地显示字段再发 action，否则等一个往返会看到闪回。
（`mode` 与三个额外档开关不走 action 的即时生效，所以 `pickXxx` 里还要补一次本地赋值。）

**有参数的 action 只能用 JSON 能往返的类型。** AE2 那条 client action 路走裸 Gson、**没有 `ItemStack` 适配器**
（AE2 自用的参数类型只有 `Boolean` / `Integer` / `Long` / `String` / `ResourceLocation` / 枚举）。要传物品
不要往 action 里塞 `ItemStack`（它带着 `Holder<Item>` 与组件表）——走 AE2 给槽位准备的
`InventoryActionPacket(InventoryAction.SET_FILTER, slot.index, stack)`。

**额外档没有专属映射标记。** 这是指**写盘侧**：绑盘盖什么标记，读的是 `menu.mode`，而额外档不改 `mode`，
于是盖出来的就是「进入该档之前那个常规档」的标记（见 `docs/TODO.md` 的 L 条；那条只点名了高级档，过载档同理）。
这不是缺陷，也不要在写盘侧新造标记。

但**选盘侧不一样**：选盘的锚点是**样板类型**，标记只是它的另一种写法。认得出的标记优先，认不出就退回类型锁——所以那个「继承来的常规档标记」反查出来是常规档类型，与高级/过载档编出的类型不同，自然不匹配，不需要另写一条特例规则。见 §七。

---

## 五、配方页导入：按对照表切到匹配的那一档

从编码界面进 JEI/EMI，点配方页上的「+」时，**必须把当前档位切到这份配方匹配的那一档**——否则玩家看到的是
「配方面板在、编辑区却是另一档的形状」。对照表（实现：`DiskEncodingHelper.modeForRecipe`）：

| 配方页类别 | 匹配的档 | 说明 |
|---|---|---|
| 合成 | 合成 `CRAFTING` | 必顶能塞进 3×3 网格，否则报「配方过大」不导 |
| 切石 | 切石 `STONECUTTING` | 同时把配方 id 交给菜单（切石面板靠它列产物） |
| 锻造 | 锻造 `SMITHING_TABLE` | |
| 雕凿 | 雕凿（额外档） | **不走这张表**，见下 |
| 其余加工 | 处理 `PROCESSING` | 「无固定」：处理本身不绑唯一配方类型，一律落处理 |
| 高级 | 无匹配 | 导入只写编辑区的普通输入输出，不写方向表；不切过去 |
| 过载 | 无匹配 | 同上：不写逐行的输入/输出与匹配模式；不切过去 |
| 万象 | 万象 | 尚未接编码（类型已占位） |

规则：
- **先关掉三个额外档**再切（`closeExtraTiers`）。不关的话导入会落在另一个档的面板上，看上去像「转移到
  高级/过载档了」，实际什么都没发生。
- **没有配方本体**的（JEI 交上来的不是 `RecipeHolder`）算「其余加工」，**不是合成**——它随后的导入
  走的就是处理路径，切错了会被那一路径再改一次，看着像闪。
- **雕凿靠「输入物品 + 产物」反推**，不靠类别 id：雕凿配方不在原版 `RecipeType` 里，类别 id 又只在
  客户端能拿到，拿不到就静默失效。反推不出（缺输入/产物、或产物不在输入的候选里）就返回 `false`，
  调用方按普通路径继续导，**不要**把玩家丢进一个空面板。
- **雕凿反推必须遍历「所有输入变体 × 所有产物」**，不能只看第一格。一个雕凿类别页里同时摆着同一组的
  不同形状（普通方块 / 楼梯 / 台阶）与多个候选产物，第一个输入格里的东西未必配得上第一个产物格；而候选
  表**只列同组同形状**的，于是拿方块去反推台阶必然失败——那一页就退化成处理档、编出一枚处理样板
  （实机见过）。实现就在 `DiskEncodingHelper.selectChiselingTierForImport` 的两层循环里，不要再改回取 `get(0)`。
- **面板别把导入设的选中序号清掉**：`ChiselingEncodingPanel.updateBeforeRender` 一发现输入槽变了就把
  `selectedChiseling` 清成 -1（那个序号在新候选表里会对应别的物品）。而导入恰好是「先填输入、再设选中」
  ——不区分的话，导入后下一帧选中就被清掉：档位对、输入对，却没有选中项，点「编写样板」只能得到
  一句「需要先选候选」，等于导入没做完。所以面板用菜单的导入修订号（`getCategoryImportRevision`，
  与搜索栏自动填充同一套机制）把「导入引起的输入变化」从「玩家自己换了输入」里区分出来。
- 雕凿那一路的顺序要紧：**先切档 → 再填输入槽 → 最后选中候选号**。面板每帧看输入槽变没变，一变就把
  选中项作废重算候选（`ChiselingEncodingPanel.updateBeforeRender`），选中写在填输入之前会被那一下清掉。
- 两个入口（JEI `JeiDiskEncodeRecipeHandler` / EMI `DiskEncodePatternHandler`）都要走同一组方法，
  不要在各自里面另写一套判断。

## 六、不变量：关闭终端后仍要记得

**一条硬规则：档位与档位自己的配置，在关闭终端（卸下终端、离开世界）后必须原样恢复。**

- 权威值一律存在 **`DiskEncodingLogic`**：常规档存 `mode`，额外档存各自的 boolean（`advancedMode` /
  `chiselingMode` / `overloadedMode`），额外档自己的配置（如过载的两张行表）也存那里。
  菜单字段是 `@GuiSync`，每次开局从 logic 回读。
- 改档位时 **必须同时写 logic**（`encodingLogic.setXxxMode(...)` → 内部 `saveChanges()` → `host.markForSave()`），
  只改菜单字段会随关屏丢失。
- **`readFromNBT` / `writeToNBT` 成对**：新增一个要存的字段，两处都要加，且 `readFromNBT` 里必须写成
  `contains(...) ? ... : 默认值`——旧存档里缺键时直接 `getBoolean` 会把缺失读成 `false`，把开关反过来。
- **子类的 `onServerDataSync` 也要归一**：它是服务端字段同步回客户端后的钩子，和 `broadcastChanges`
  是两条独立的路径。只在其中一处归一，另一条路径下就会停在两个额外档同时开着的状态（两块同坐标面板叠画）。
  优先级固定为「高级 > 雕凿 > 过载」（与屏幕上注册表的排序一致），每次只关一个：关掉的那个写回 `logic`，
  权威值变假后不会再触发，收敛即停。

| 状态 | 存在哪 | 关闭终端后 |
|---|---|---|
| 常规档 | `logic.mode` | 恢复 |
| 高级 / 雕凿 / 过载档开关 | `logic.advancedMode` / `chiselingMode` / `overloadedMode` | 恢复 |
| 过载的两张行表 | `logic.overloadedSides` / `overloadedMatchModes` | 恢复 |
| 雕凿的输入方块 | `logic.chiselingInput`（NBT 键 `chiselingInput`） | 恢复 |
| 切石选中的配方 id | `logic.stonecuttingRecipeId` | 恢复（切石面板靠它列产物） |
| 高级的方向表 | 菜单字段（不进存档） | **不恢复**，重新进档时从输出栏那张样板上回读 |
| 雕凿的候选选中项 | 菜单字段（不进存档） | **不恢复**，输入一变就作废重算 |
| 高级 / 过载的可用性 | `logic` 不存，每 tick 看升级槽 | 跟着升级槽走 |
| `selectedChiseling` 等瞬时选择 | 菜单字段 | 不恢复（有意：序号在新列表里可能对应别的物品） |

## 七、上传目标：两个体系，一份分层

### 先分清两个体系

磁盘身上有两样东西管着两种完全不同的事，**它们不是两条并列的判据**：

| | 标记（`DISK_PREFIX`） | 类型锁（`PatternDiskContents.type`） |
|---|---|---|
| 谁写的 | **玩家**：右键拿工作方块打标 / Shift+右键拿搜索栏文本 / Shift+右键（空搜索栏）清掉 | **写盘路径自动**：第一枚样板入盘时锁死，清空归 null |
| 性质 | 玩家的**组织意图**，可改、可空、可与内容无关 | 数据的**客观事实**，玩家改不了 |
| 管什么 | **该写哪张盘**（分组 / 筛选） | **能不能写进这张盘**（准入） |
| 实现落点 | 搜索栏 `#` 筛选、`bindPrefix`、`tierTypeOf` | `PatternDiskContents.acceptsType`、`tryInsert` |

**标记管「该写哪张」，类型管「能不能写」。** 类型不该参与「选哪张」的偏好判断——它的天然位置是写入时的
准入。把两者 OR 成两条判据会打架（锁了 A 类的盘可能因为挂着 B 类标记被选中），只好再补一条「类型否决标记」
的规则去拦；那是结，不是理。

### 分层：组 → 序 → 试

选盘分三层，一层只干一件事：

1. **组（标记体系）**：这张盘归到哪一类样板？`DiskMarkRules` 负责标记 ↔ 类型互转：
   - 有标记：`patternTypeForMark(mark)` 反查它代表哪类样板（玩家的意图优先）；
   - 没标记：用它锁定的类型（类型当**默认值**）。两者都拿不到 → null（空盘、认不出的自定义标记、万象）。
2. **序（挑哪张）**：组内按**剩余空间降序**——写进还剩得多的那张（大容量盘优先填）。平手时保持显示名序
   （稳定，不会每次刷新换一张盘）。想改成沿列表顺序把一张填满再下一张，写 `@order`。
   〔副作用〕大容量盘只要还剩空间就一直被选中，所以同组里的小盘可能长时期不动——要轮着填就写 `@order`，
   或直接点那一张盘。
3. **试（准入，类型体系）**：列表交给菜单的 `transferToFirstWritable`，它按上面的顺序**逐张** `tryInsert`，
   第一张能收的就收了。容量 / 类型锁 / 主产物互斥都在那里判，不在选盘时不预判（预判得先有编好的样板，
   而候选是编码之前算的）。

```java
protected long[] tierGroupByUsage()   // 组 + 序：组内按剩余空间降序，capCandidates 后返回
private    boolean isInCurrentTierGroup(DiskEntry)  // 组：tierTypeOf(盘) == currentPatternTypeId()
private    String  tierTypeOf(DiskEntry)            // 标记反查优先，没标记用类型锁兜底
```

### 两个终端的算法

共用上面这条分层，区别只在「候选从哪来」：

- **编码终端**（`updateDiskEntries`）：写了 `@order` → 列表顺位；否则恒 `tierGroupByUsage()`。
- **管理终端**（`buildAutoDiskCandidates`，每帧在 `super.updateBeforeRender()` 之后覆盖一次）：右键选中的盘
  + 同容器其他盘 → 否则写了 `@order` 时表格行序 → 否则 `tierGroupByUsage()`（与编码终端同口径）。

**筛选与策略正交**（`DiskEntryFilter.Query`）：`preferOrder` 是**写入策略**，`isActive()` 不看它，所以
「筛出哪几张」与「写哪张」互不干涉。`tierGroupByUsage` 遍历的就是**已过滤**的 `diskEntries`，于是
「筛出两张、在这两张里挑剩余多的」天然成立——这正是导入场景要的行为。策略词只从搜索文本**末尾**剥
（标记与盘名都可能含空格，按空格切 token 会把 `#铁 厂` 拆坏），打标时也要剥（`stripModifiers`），
免得 `@order` 被判进磁盘标记里。

**档位 → 该档编出的样板类型**（`ExtraTier.patternTypeId`；常规四档由模式推，不另写表）：

| 档位 | 样板类型 id |
|---|---|
| 合成 / 切石 / 锻造 / 处理 | `ae2:<模式名小写>_pattern` |
| 雕凿 | `rechiseledae:chiseling_pattern` |
| 高级 | `advanced_ae:adv_processing_pattern` |
| 过载 | `ae2lt:overload_pattern` |

这些 id 与 `PatternDiskItem.KNOWN_TYPES` 的键对齐——那张表是「类型 → tooltip 名字」的权威出处，
新增类型时两处一起加。

### 坑

- **别把两个体系写回成 OR**：那是本文档前几版的实际错误，症状是必须补一条「类型否决标记」的补丁，
  而且高级/过载档下「锁了类型但没标记」的盘会被排除（它们没有专属标记，推不出兜底值）。
  锚点是**样板类型**（两边共有的量与准入依据），标记只是它的另一种写法。
- **标记认三种写法，别只认一种**：`#mode:<模式>`（模式标记）、`#<配方类别>`（导入过配方）、
  `#mode:chiseling`（雕凿的固定字面量）。`patternTypeForMark` 三种都转。
- **判不出类型的不算**：空盘既没标记也没锁类型——否则每张空盘都会来抢。
- **但「标记认不出」不等于「这张盘不算」**：认不出的标记不屏蔽类型兜底（`tierTypeOf` 里那句 `byMark != null`）。
  否则两种正常盘会进不了组：导入一条熔炉配方会把 `#minecraft:smelting` 写进标记，而**处理档没有规范类别**
  （`categoryForMode` 返回 null）反查不出类型；玩家自定义的文本标记同理。它们的类型锁很可能正好能收当前样板。
- **额外档优先于 `mode`**：雕凿/高级/过载档下菜单的 `mode` 仍停在「进入本档之前那个常规档」（已记录行为），
  所以 `currentPatternTypeId()` 必须先问 `activeTier()`。
- **两个终端的「算目标」是两条路，别只改一条**：管理终端是子类，且每帧覆盖一次 `setClientAutoDisks`，
  基类算出的值对它无效。`tierGroupByUsage` 在基类，两侧共用；改口径时请确认两条路都走到。
- **搜索栏只筛盘、不再选策略**：`DiskEntryFilter.Query.preferOrder` 是写入策略，`isActive()` 不看它。
  导入配方（JEI/EMI 点「+」）会把搜索栏自动填上 `#<配方类别>`，这不是副作用，而是**组合技的起手**——
  紧接着 Shift+右键一张盘，就用搜索栏里的文本给它打标（{@code onDiskShiftRightClick} → {@code bindSearchMark}），
  一步把新拿到的、还没标记的盘归到刚导入的那个类别下。
  **不要**把「自动填搜索栏」当成缺陷去拆：拆了组合技就断了。填空之后写盘依旧按剩余空间挑
  （筛出哪几张 → 在这几张里挑还剩得多的），两个行为互不干涉。
- **一张候选都没有时静默**：没有任何盘属于本组时，`autoDisks` 为空，菜单里 `if (autoDisks.length > 0)`
  整段跳过——不写盘也不提示，样板留在编码槽。这是「没找到目标」而非写盘失败，属已知行为。
  筛出空盘也没用：空盘不属于任何一组，想写它就直接点它。
- 〔已知缺口〕**雕凿的配方类别文本不是一个被认得的标记**：雕凿自己写的是 `#mode:chiseling`（识），
  但 Rechiseled 在 JEI 里的类别 id 是 `rechiseled:chiseling`——它会被导入自动填进搜索栏，玩家若此时
  Shift+右键拿它打标，存的标记反查不出类型（四常规档的类别里没这一条）。补它得把上游那个类别 id 写进这里
  （可选模组的耦合），而后果只在一小段路径上（搜索栏清空后、且那张盘只带这个标记），所以暂不补。

## 八、档位跟随样板输出栏

往样板输出栏里放一枚已编码样板时，终端会**切到与它对应的那一档**
（`PatternDiskEncodingTermMenu.syncTierWithPatternOutput`，在服务端 `broadcastChanges` 里看槽内容的变化）。
理由很直接：放进去就是要接着编辑它（或者是回来改它），而终端可能停在另一个档上——雕凿样板停在合成档、
高级处理样板停在处理档，面板显示的东西与槽里那枚样板对不上。

它与「进档时从这张样板摊东西」是同一套口径，只是方向相反：切过去之后编辑区已经按那枚样板摆好，
玩家不用再手点一次候选。

| 槽里那枚样板的类型 | 切到 | 切过去时顺带做什么 |
|---|---|---|
| `ae2:crafting_pattern` / `ae2:processing_pattern` / `ae2:smithing_table_pattern` / `ae2:stonecutting_pattern` | 对应常规档 | 无（常规档的编辑区与输出栏无关） |
| `advanced_ae:adv_processing_pattern` | 高级档 | 摊输入输出 + 读回每格的面（`setAdvancedMode` 自己会做） |
| `rechiseledae:chiseling_pattern` | 雕凿档 | 按 `EncodedChiselingPattern` 的「输入 → 产物」反推，摆输入并定好选中（`seedChiselingFromPattern`，用**服务端**那份候选表） |
| `ae2lt:overload_pattern` | 过载档 | 摊每行的物品与「输入/输出」标记 |
| `useless_mod:omniversal_pattern` | —— | 认不出，不动（编码侧还没接） |

那些类型 id 全部取自 `PatternDiskItem` 的那组常量，不要再写字面量。

坑：

- **只比物品身份，不比组件**：档位只由样板物品类型决定，同物品的另一枚样板（不同配方）不需要重新切档。
- **开屏第一帧只建基线**（`outputTierBaselineSet`）：存档里本来就停着一枚样板（玩家上次没拿走），那是现状
  而不是「刚放进去」，据此改档会盖掉玩家存下来的档位。
- **被拿走 / 被清成空白样板时不动档位**：玩家可能正要往这个档里写东西，替他把档切走只会碍事。
- **额外档连可用性一起判**：切过去的前提是升级槽里有那枚编码器，否则只会是一屏空的隐藏面板。
- **已在档内也要重推**：换的是另一枚同类样板，编辑区得跟着它变（所以雕凿档总是跑 `seedChiselingFromPattern`，
  高级/过载档总是再调一次自己的 setter——那两个 setter 自己会重摊一遍）。
- **切回常规档时，关额外档不跟 `mode` 比较挂钩**：三个额外档与常规档并列，而额外档下 `mode` 停在进入本档
  之前那个常规档——人在高级档、放进去的却是合成样板时 `mode` 本就等于 `CRAFTING`。先关额外档、后判
  `mode` 要不要变；合成那一步不拆开。
- **切档本身不改输出栏**，所以那次检测不会自我触发；唯一的例外是**编码自己写进输出栏**那一枚
  ——所以写完要立即调 `adoptOutputPatternAsBaseline()` 把基线跟上。不跟上的话，高级档补面编出的那张
  普通处理样板会在下一帧把人踢出高级档。
- **检测必须留在 `super.broadcastChanges()` 之后**：AE2 在那一句里先发 `@GuiSync` 数据、再发槽刷新，
  两者同 tick 按序下发。挪到它之前，客户端会先收到槽、后收到 `chiselingInputRevision`，雕凿面板那一下
  就会把程序设的选中序号当成「玩家换了输入」清掉。
- **面板别把这一步设的选中序号清掉**：雕凿档会连着设输入与选中，而面板一发现输入变了就把选中作废
  （见 §五）。菜单用一个 `@GuiSync` 的输入修订号（`chiselingInputRevision`）把「程序设的输入」与
  「玩家换的输入」分开，面板据此跳过清空——配方导入那条路也用同一个修订号。

## 九、加一个磁盘类型

磁盘的「类型」就是**编码样板物品的注册 id**（`PatternClassifier` 取 `details.getDefinition().getId()`），
所以一个新类型必须挂在某个真实存在、且能被认成 `IPatternDetails` 的样板物品上。

1. `PatternDiskItem.KNOWN_TYPES` 加一项：`"<命名空间>:<物品 id>" → KnownType(propertyValue, nameKey)`。
   `propertyValue` **必须大于已有的每一个**（模型 `overrides` 由后往前匹配，最后命中者胜）。
2. 5 个容量模型（`models/item/pattern_disk_{1k,4k,16k,64k,256k}.json`）各补一条 `overrides`，指向新的类型模型。
3. 新建 5 个类型模型 `pattern_disk_<tier>_<type>.json`：`layer0` 底图 + `layer1` 容量 + `layer2` 类型贴图。
4. lang 两份加 `ae2_pattern_disk.tooltip.type.<type>`。

未接编码侧的类型可以只做 1–4（类型与覆盖层就位），但**没有对应物品时它永远不出现**。

---

## 十、编码模式相关的小抄

- 面板几何：`Blitter.texture(uri, 真实宽, 真实高).src(...)`，`.src()` 一律按 **PNG 像素坐标**写。单参的
  `texture(uri)` 内部就是 `texture(uri, 256, 256)`（`Blitter.DEFAULT_TEXTURE_WIDTH/HEIGHT`），UV 按它折算
  ——**图不是 256×256 时必须显式给尺寸**，否则取样会落到错误位置（通常是一小块空白或杂色），表观是
  「控件整个不见了」。踩过三回：管理终端
  512×512 底图、高级档 16×16 方向按钮、过载档 64×64 复选框（复制新贴图前先看真实像素尺寸）。
- 滚动条不在面板坐标系里：位置来自样式文档，且必须满足
  `left = 面板 left + (TRACK_X - 1)`、`bottom = 面板 bottom - TRACK_Y`。面板挪了要同步两份 JSON。
- 面板的 `setVisible` 要同步滚动条（它是屏幕级 widget，不会跟着面板藏）。
