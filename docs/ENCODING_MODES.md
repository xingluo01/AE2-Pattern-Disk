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
        @Nullable String ownMark,           // 专属标记字面量；null = 没有，继承上一个常规档的
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
- **专属标记**：只有 `ownMark != null` 的档才拦截标记判定（现有只有雕凿），其余继承常规档的。

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

**子控件必须经 `populateScreen` 注册。** 复合控件的子控件要交给
`populateScreen(addWidget, bounds, screen)` → `addRenderableWidget`，渲染、鼠标命中、悬停提示（AE2 会遍历
子控件收 `ITooltip`）才走原版那套。**自己摆位置、自己调 `button.render(...)` 是不行的**：那一下不把它们挂进
屏幕控件表，实测现象就是「按钮根本不出现」。

**`updateBeforeRender` 只对**可见**的复合控件调用**（`WidgetContainer`）。所以子控件的位置要在那里设，
同时必须在 `setVisible(false)` 里把子控件也置为不可见——否则切到别的档后它们继续浮在上面。

**可用性判据是升级槽里那枚编码器，不是「模组在不在」。** 与高级档同口径（`host.getUpgrades()` 扫物品 id）。
卡不在时那一档立即退出，否则面板会停在一个再也编不出东西的空档上。

**档位状态要跟着终端走。** 权威值存 `DiskEncodingLogic`（它随部件持久化），菜单只做每 tick 回读与同步；
客户端点一下先改本地显示字段再发 action，否则等一个往返会看到闪回。

**额外档没有专属映射标记。** 它继承「进入该档之前那个常规档」的标记——这是已记录行为（见 `docs/TODO.md` 的 L 条；那条只点名了高级档，过载档同理）
不是缺陷。除非该档的样板语义真的不同（如雕凿自己给了 `#mode:chiseling`），否则不要新造标记。

---

## 五、加一个磁盘类型

磁盘的「类型」就是**编码样板物品的注册 id**（`PatternClassifier` 取 `details.getDefinition().getId()`），
所以一个新类型必须挂在某个真实存在、且能被认成 `IPatternDetails` 的样板物品上。

1. `PatternDiskItem.KNOWN_TYPES` 加一项：`"<命名空间>:<物品 id>" → KnownType(propertyValue, nameKey)`。
   `propertyValue` **必须大于已有的每一个**（模型 `overrides` 由后往前匹配，最后命中者胜）。
2. 5 个容量模型（`models/item/pattern_disk_{1k,4k,16k,64k,256k}.json`）各补一条 `overrides`，指向新的类型模型。
3. 新建 5 个类型模型 `pattern_disk_<tier>_<type>.json`：`layer0` 底图 + `layer1` 容量 + `layer2` 类型贴图。
4. lang 两份加 `ae2_pattern_disk.tooltip.type.<type>`。

未接编码侧的类型可以只做 1–4（类型与覆盖层就位），但**没有对应物品时它永远不出现**。

---

## 六、编码模式相关的小抄

- 面板几何：`Blitter.texture(uri).src(...)` 的坐标按 **256×256** 换算（`Blitter.DEFAULT_TEXTURE_WIDTH/HEIGHT`）。
  图集是 256×256 时数字可直接照抄；不是就得改 `.src()`。
- 滚动条不在面板坐标系里：位置来自样式文档，且必须满足
  `left = 面板 left + (TRACK_X - 1)`、`bottom = 面板 bottom - TRACK_Y`。面板挪了要同步两份 JSON。
- 面板的 `setVisible` 要同步滚动条（它是屏幕级 widget，不会跟着面板藏）。
