# AE2 Pattern Disk 架构手册

给接手这套代码的人：这页讲**代码怎么组织、东西放在哪、改哪里**。

玩法说明在 `../README.md`（英文，商店页与 GitHub 首页用），平台差异在 `PLATFORM.md`（MCMOD 版在 `PLATFORM_MCMOD.md`），未完成与已知欠账在 `TODO.md`，历次改动在 `../CHANGELOG.md`，本地依赖怎么凑齐在 `LOCAL_DEPS.md`。本手册不重复它们。

（本目录 `docs/` 只放文档：本手册、平台说明两份、待办、本机路径索引，以及《编码模式注册规范》`ENCODING_MODES.md`。仓库根只留 `README.md` 与 `CHANGELOG.md` 这两个按惯例必须在根的文件。）

版本基线：NeoForge 21.1.241 / MC 1.21.1 / AE2 19.2.18 / Java 21。

---

## 1. 源集与包地图

### 源集

| 源集 | 位置 | 说明 |
|---|---|---|
| `main` | `src/main/java` | 公共与服务端。方块、方块实体、菜单、API |
| `client` | `src/client/java` | 只在客户端加载。`build.gradle` 刻意把它与 main 分开，让公共代码无法误引客户端类——这不是洁癖，是编译期防线 |
| `main/templates` | `src/main/templates` | `neoforge.mods.toml` 模板，构建期展开 |
| `generated` | `src/generated/resources` | 数据生成的输出目录（`build.gradle` 已把 `--output` 指向它并接进 main 资源）。当前为空——没跑过 datagen |
| `test` | `src/test/java` | 已在 `build.gradle` 里配置，但目录尚未创建：目前没有任何测试源文件，验证靠编译与实机 |

模块信息写在 `src/main/templates/META-INF/neoforge.mods.toml`，不要直接改构建产物。

### 包地图

| 包 | 文件数 | 职责 |
|---|---:|---|
| （根包） | 3 | 模组入口 `AE2PatternDisk`、注册表 `AEPatternRegistries`、AECS 在场才注册的 `MeteoritePatternProviderRegistrations` |
| `api` | 14 | **对外稳定面**。给"搬运磁盘"与"供应样板"的第三方模组用，包路径与签名改动等于破坏兼容 |
| `common` | 1 | capability 注册入口 `AEPatternDiskCapabilities` |
| `common/logic` | 14 | 纯逻辑。不依赖菜单、屏幕、网络，可在服务端线程之外读懂 |
| `common/menu` | 15 | 菜单与其逻辑（编码逻辑、磁盘索引、标记规则、输出运算） |
| `common/menu/slot` | 2 | 终端里的两个特殊槽 |
| `common/block` | 5 | 方块类 |
| `common/block/entity` | 8 | 方块实体 |
| `common/part` | 4 | 线缆面板形态的部件 |
| `common/pattern` | 5 | 磁盘内容的数据结构、库存适配、容量档、内容版本快照 |
| `common/item` | 2 | 磁盘物品与升级卡包装 |
| `common/recipe` | 1 | 洗掉磁盘标记的合成 `ClearDiskMarkRecipe`（见 README「Clearing a disk's mark」） |
| `common/util` | 2 | NBT 与掉落物小工具 |
| `network` | 7 | 数据包（客户端与服务端各半） |
| `integration/…` | 22 | 邻居模组适配，一个包一个邻居（10 个子包 + `package-info`） |
| `config` | 1 | 模组配置 |
| `client`（客户端源集根） | 2 | 客户端入口 `AE2PatternDiskClient`、物品显示属性 `InitPatternDiskProperties` |
| `client/gui` | 28 | 屏幕、面板、按钮、表格模型 |
| `client/integration` | 23 | JEI / EMI / IPN / JECH 与各邻居的客户端侧适配 |
| `client/sort` | 4 | 排序口径（数值序、阶层表、名字门槛） |
| `client/render` | 1 | 高效分子装配室的 BEISR |
| `mixin` | 1 | `KeySortersMixin`，在客户端源集，见 §7 |

主源集 106 个 `.java`，客户端源集 59 个。

### 分层规矩

- `api` 只放第三方会写的类型（接口、数据对象、门面）。它不反向依赖 `common/menu` 或屏幕。
- `common/logic` 里放"能单独读懂、能单独讲清"的东西：`CellBuffer`、`SmoothReturnQueue`、`PatternPlanCache`、`BatchSynthesisEngine`、`ProductDelivery`、`TransfererOperations`、`AssemblerOutputResolver`、`DiskSlotVersions`。回调优先——构造参数接 `Supplier` / `Runnable`，不主动抓菜单服务。确实需要宿主时可以直接收 BE 类型（`TransfererOperations` 收 `PatternTransfererBlockEntity`，`PatternDiskProviderLogic` 收 MC `BlockEntity`），该不该留在 logic 按 §9 那三个"不"判，不按"认不认识 BE"判。**"能单独测"目前只是设计意图**：`src/test` 不存在，这一包一行测试都没有（见 §1 源集表）。
- `common/menu` 里放"必须知道菜单服务"的东西。判据很简单：一段代码要不要用 `getPlayer()`、`sendClientAction`、`notify*`、`sendPacketToClient`？要，就留在菜单。
- `integration/<邻居>` 只写那个邻居的适配，**邻居的类型不得漏进总是加载的类**（这才是红线，见 §6）。核心代码引用 `integration` 包自己写的门禁与契约类是允许的，现在是 7 个文件共 19 处 import：入口 `AE2PatternDisk`（5，五个软依赖门禁）、`AEPatternRegistries`（4，NeoForge 延迟注册必须在物品注册处拿到无线终端的类型 + EAE+ 契约）、`PatternDiskEncodingTermMenu`（6，高级/雕凿/过载/polymorph）、`PatternEncodingLogic`、`PatternDiskProviderLogic`、`PatternDiskProviderMenu`、`SelfAssemblingPatternDiskProviderLogic` 各 1。客户端侧 `client` 核心包对 `client/integration` 是 **0 处 import**，比主源集更严。
- `common/menu` 与 `common/logic` 的归属按内容判、不按"能不能单独测"判：`DiskEncodingLogic`、`PatternEncodingLogic`、`DiskIndex`、`DiskMarkRules`、`ProcessingOutputMath` 都不碰菜单服务，看着像 logic，但它们读写的是菜单的 `@GuiSync` 项与宿主接口，留在 `common/menu` 是和协议同位置，不要搬。

---

## 2. 核心概念

### 2.1 样板磁盘

`common/item/PatternDiskItem.java`——容量档见 `common/pattern/PatternDiskTier.java`（1k→4、4k→16、16k→64、64k→256、256k→1024）。

内容存在物品组件里，读写入口是 `api/PatternDiskContents.java`（不可变值对象：`patterns()`、`capacity()`、`used()`）。**取出来的 `ItemStack` 元素是共享可变的**，谁在别处改它，谁就把所有持引用的人一起改了——需要快照时按 `.copy()` 取一份。

磁盘空着时是无类型的，第一张写进去的样板决定它的类型；`api/PatternClassifier.java` 负责把 AE2 样板归成一个稳定类型键。

### 2.2 磁盘标记

标记是 `AEPatternRegistries` 注册的那个字符串组件 `DISK_PREFIX`（注册名 `disk_prefix`，声明在 `AEPatternRegistries.java:297`），记的是"这张盘归哪台机器 / 哪类配方"。有两种写法，显示与搜索会把它们归一：

- 导入过配方：`#<配方类别>`，例如 `#minecraft:crafting`
- 手动编码：`#mode:<模式>`

规则集中在 `common/menu/DiskMarkRules.java`（模式 → 标记、标记 → 可读名前的清洗）；可读名与 tooltip 那一侧在 `client/gui/PatternDiskMarks.java`。

**已知行为**：雕凿档有专属标记 `#mode:chiseling`（它不在 AE2 的 `EncodingMode` 里、也没有配方类别，标记由上传路径直给）；
而**高级档没有专属标记**，写盘时它盖的是「进入高级档之前那个常规档」的标记——这是已记录项，不是缺陷，详见 `TODO.md` 的 L 条。

**但这里要分清两侧**（同一句话在两侧的结论相反，合写必然误导）：

- **写盘侧**（绑什么标记）：确实继承，承上述的已记录行为。
- **匹配侧**（选目标盘）：锚点是**样板类型**，不是标记字面量。标记是类型的另一种写法（{@code DiskMarkRules}
  负责互转），有标记就反查它代表哪类样板、没标记就用盘自己的类型锁。于是那句「继承来的标记」在高级/过载档下
  反查出来是**常规档**类型（如 {@code ae2:crafting_pattern}），而当前档编的是
  {@code advanced_ae:adv_processing_pattern}、两边不同，自然不匹配——不需要再写一条「某档不认标记」的特殊规则。
  细节与完整分层见 `ENCODING_MODES.md` §七。

### 2.3 宿主、视图、序列号

一台"支持磁盘的机器"实现 `api/IPatternDiskHost.java`（有磁盘槽的库存）。它的终端视图由 `api/PatternDiskHostView.java` 拼装：自己的行在前，盘内样板在后。

跨模组还有一条路：`api/DiskHostCollector.java` 让别的模组把自家宿主交给本模组的编码终端，注册口是 `PatternDiskApi.registerDiskHost`，存储实现 `api/PatternDiskHostRegistry.java`。

**序列号（serial）**是终端与机器之间的通用货币：客户端只认序号，不认槽位。`common/menu/DiskIndex.java` 负责"网格里的磁盘槽 ↔ 稳定序列号"这层映射，`common/pattern/DiskSlotVersions.java` 记内容版本快照，用来回答"自上次看过之后变没变"。

### 2.4 空白样板记账

往磁盘里写一张样板，欠网络一张空白样板。`api/BlankPatternSink.java` 是网络那一侧的契约（扣取与退还），终端在取盘/回流时与它对账。只显示不扣取的槽是 `common/menu/slot/NetworkBlankPatternSlot.java`。

---

## 3. 设备逐个说

### 3.1 ME 样板磁盘供应器

方块 `common/block/PatternDiskProviderBlock.java` + 方块实体 `PatternDiskProviderBlockEntity` + 面板 `common/part/PatternDiskProviderPart.java`（416 行）。两形态共享一个宿主契约 `PatternDiskProviderHost` 与一份逻辑 `common/logic/PatternDiskProviderLogic.java`——出样板、收回产物那条路只写了一遍。

**自装配变体**：`MeteoritePatternProviderBlockEntity` 配 `SelfAssemblingPatternDiskProviderLogic`（378 行），能在供应器内部直接做完的合成不往外推。它整套注册在 `MeteoritePatternProviderRegistrations.java` 里，条件是 AE2 Crystal Science 在场（软依赖入口 `integration/ae2cs/AecsSoftDep.java`）。面板形态 `common/part/MeteoritePatternProviderPart.java`（本包最大的文件）共用同一份宿主契约与逻辑，自己只多两件事：自装配产物的回送由服务端的世界 tick 事件驱动（部件没有方块实体那种每 tick 回调，而网格的 `IGridTickable` 服务槽归 AE2 的供应器逻辑），贴附面顶替「推入方向」里的方位参照。

### 3.2 样板转存器

`PatternTransfererBlockEntity` 留了生命周期、库存与 NBT、模式与菜单入口；四种搬运（盘→盘移动、盘→盘复制、编码样板→盘、输入槽反查）都在 `common/logic/TransfererOperations.java`（285 行）。模式枚举在 `common/pattern/TransferMode.java`。

### 3.3 高效分子装配室

`PatternDiskAssemblerBlockEntity`（891 行）跑 8 个并发执行单元。一个单元是 `common/block/entity/CraftUnit.java`：3×3 网格 + 输出槽 + 进度。产物往哪走、送不掉怎么记账在 `common/logic/ProductDelivery.java`。

### 3.4 批处理装配室

全项目最复杂的一台。`common/block/entity/BatchAssemblerBlockEntity.java`（1313 行）是编排者，真正的重活拆在 `common/logic`：

| 类 | 干什么 |
|---|---|
| `BatchSynthesisEngine`（516 行） | 一条作业怎么兑现成产物：摊销块与单件慢路径 |
| `BatchPatternAnalyser` | 分析新入队样板的线程池（速度卡定尺寸）与失败上报 |
| `BatchRecipePool` | 九张盘上的样板汇总成一份对 CPU 可见的池子 |
| `PatternPlan` / `PatternPlanCache` | 把样板解析成执行计划并缓存 |
| `CellBuffer` | 机器私有的元件缓冲：材料与产出都从这里中转，不直接进网络 |
| `SmoothReturnQueue` | 产物摊到若干 tick 交出去，不一次砸进网络 |
| `ParallelSlotProbe` | "这次交付能装下几份完整配方" |
| `BatchWorkState` | 对外报告的工作状态（12 档） |

速度卡买的是分析线程数（`1 << 卡数`），不是执行并行度：元件与网络都不是线程安全的，所有实际访问留在服务端 tick 线程。窗口逻辑（批窗口、短窗口、空闲复位）是 BE 里那几行 `tickingRequest` / `runBatch` 的编排，没有单独成类——它和队列共享可变状态，抽出去只会变成跨对象协议。

### 3.5 编码终端与管理终端

编码终端 = 菜单 `PatternDiskEncodingTermMenu`（2324 行）+ 屏幕 `client/gui/PatternDiskEncodingTermScreen.java`（1091 行）。菜单里塞了四套常规编码模式、磁盘列表协议、写盘/取盘、标记与命名。屏幕侧有 4 个常规档面板（`CraftingEncodingPanel` / `ProcessingEncodingPanel` / `SmithingTableEncodingPanel` / `StonecuttingEncodingPanel`）、磁盘列表面板 `DiskListPanel`，以及**三个额外档**（高级 `AdvancedEncodingPanel` / 雕凿 `ChiselingEncodingPanel` / 过载 `OverloadedEncodingPanel`）——额外档的图标、可用性、互斥与可见性由屏幕里一张注册表 `List<ExtraTier>` 统一派生，加档流程见 `ENCODING_MODES.md`。

管理终端继承编码终端的两侧（菜单加 `PatternDiskManagementTermMenu`，屏幕加 `PatternDiskManagementTermScreen`），多出来的是"按机器分组的整表"：行模型 `DiskTableRowBuilder` / `DiskTableRowModel`，盘内样板的搜索与排序 `DiskPatternView`，粘贴过滤 `DiskEntryFilter`。

### 3.6 无线终端

两个无线终端共用 `WirelessPatternDiskTerminalItem`，差的是菜单类型。登记全在 `integration/ae2wtlib/WirelessTerminalRegistrations.java`。

---

## 4. 关键数据流

### 4.1 写一张样板进磁盘

1. 屏幕发客户端动作（名字即协议，见 §5）。名字前缀与磁盘序号一起走 `sendClientAction`。
2. 服务端在菜单里校验：目标盘还在不在（`DiskIndex.refOf`）、容量、类型锁、前方有没有空白样板欠账。
3. 写成功后向客户端回执（聊天栏或终端提示），并触发磁盘清单刷新。
4. 自动落盘目标由屏幕在"过滤完之后"算出来（`setClientAutoDisks`），早一帧算就可能指到此刻已经看不见的盘上。

### 4.2 取一张样板出磁盘

取件要先欠后还：先向网络借一张空白样板，再动磁盘。借不到就拒绝取件（`api/BlankPatternSink`）。AE2 样板访问终端的"交换"协议会先清空行、再在放不下时把原样板写回，本模组把这套当成"取出 + 还原"处理。

`api/PatternDiskRemoveInventory.java`（530 行）就是这层可写视图。它有一个已知限制：终端按住空格拖整片区域时，那条循环读到的每个行都是副本，于是会在不收费的情况下白送副本、还把被操作的那一行真的取出来——修它要换掉菜单里的那段循环，在此之前"一行一行取"是唯一划得来的路。

### 4.3 磁盘清单同步

- 服务端扫网格里的宿主与磁盘槽，分配或沿用序列号（`DiskIndex`），先比一次内容版本快照；没变就不发。
- `DiskListPayload` 带上全部磁盘，`DiskHostListPayload` 带上"哪台机器装了哪些盘"，管理终端再加一层按需内容推送：客户端把它当前视口里的序号报上来（`VisibleDisksPayload`），服务端只回这些盘的内容（`DiskContentPayload`）。
- 行数在终端打开期间是冻结的：AE2 每会话按固定槽位数同步，所以被取走的行是就地清空，而不是从表里消失。

### 4.4 批处理的一次执行

推料 → 入窗口（`CellBuffer` 收材料）→ 静默窗口到期 → `runBatch` 逐条计划：先试摊销块（一个槽位一个变体的最大块），剩下的走单件；产出进 `SmoothReturnQueue`，按 tick 额度回网络；网络正在索要的 key 跳过平滑直接交。任何一步失败都回滚材料，作业留在队列。

能量在扣料之后、记账之前扣：材料能还，能量不能。

---

## 5. 网络协议

`network` 包共 7 个类，6 个是 payload，另有一个纯客户端的动画状态：

| 类 | 方向 | 语义 |
|---|---|---|
| `DiskListPayload` | 服务端→客户端 | 网格里所有盘的完整列表 |
| `DiskHostListPayload` | 服务端→客户端 | 按机器分组的磁盘清单（含空槽数） |
| `DiskContentPayload` | 服务端→客户端 | 客户端正在看的那几张盘的内容 |
| `AssemblerAnimationPayload` | 服务端→客户端 | 某个装配单元完成一次合成 |
| `VisibleDisksPayload` | 客户端→服务端 | 表格当前视口里的序号 |
| `TerminalViewStatePayload` | 客户端→服务端 | 隐藏空槽、选中盘、搜索范围、附加排序 |
| `AssemblerAnimationStatus` | 仅客户端 | 动画状态，不上网 |

菜单里还有两套**字符串协议**，改名字等于改协议：

- `registerClientAction("...")` 的动作名（`encode`、`clear`、`setMode`、`transferToDisk`、`extractFromDisk`、`bindPrefix`、`renameDisk`、`refreshDiskList`、`neoecoae:uploadPattern` 等）。客户端与服务端靠这些字符串配对，注释里写着"名字即协议"的地方不要动。
- `@GuiSync(n)` 的**注解值 n 就是协议键**。AE2 在菜单构造期按注解值反射注册同步项，同一个菜单里重复的 id 会当场抛异常；字段本身可以搬位置，n 不能改、也不能被第二个字段复用。

磁盘清单里的序列号也是协议的一部分：它必须在一个终端会话内稳定，客户端拿它说话，服务端拿它找盘。

---

## 6. 集成层

适配邻居的方式统一为：**按 mod id 门禁 + 每个特性各自守卫**。判据写在 `build.gradle` 的注释里——把"这个模组在场"和"这个模组的这个版本有这个特性"分开，落后版本的邻居不该让机器启动失败。

| 邻居 | 服务端 | 客户端 | 干什么 |
|---|---|---|---|
| AE2 WTLib | `integration/ae2wtlib` | `client/integration/ae2wtlib` | 两个无线终端 |
| NEO ECO AE Extension | `integration/neoecoae` | `client/integration/neoecoae` | 上传按钮、整批并行入口 |
| ExtendedAE Plus | `integration/extendedae_plus` | `client/integration/extendedae_plus` | 往供应器上传样板的链路 |
| AE2 Crystal Science | `integration/ae2cs` | — | 自装配供应器的整套注册 |
| Jade | `integration/jade` | — | 显示机器状态 |
| Polymorph | `integration/polymorph` | `client/integration/polymorph` | 多结果配方由玩家选 |
| JEI / EMI / IPN / JECH | — | `client/integration` | 配方导入、幽灵物品、整理、拼音搜索 |

客户端那边有个反复出现的写法：`ClientExtendedAEPlusCompat` 这类**把"带契约的邻居在场"变成一屏**的类。理由是编译期可选依赖的类型名一旦出现在总是加载的类里，缺那个模组的玩家就会在加载阶段炸掉；把名字关进只在门禁通过后才碰的类，缺了就只是一屏。

NEO ECO 的类型名与方法名集中在 `NeoECOTypes.java`，改版本时先看它。

---

## 7. 资源、混入与访问

- `assets/ae2_pattern_disk`：`lang`（含 `_zh_cn`）、`models`、`blockstates`、`textures/guis`（终端贴图按 512 网格切片）
- `data/ae2_pattern_disk`：`recipe`、`loot_table`、`tags`
- `assets/ae2/screens/ae2_pattern_disk/`：终端布局 JSON（每台机器一份，当前 10 份，改布局先改这里）
- `ae2guide` 与 `ae2guide/_zh_cn`：GuideME 指南，中英各一份——**两份要同步改**
- `ae2_pattern_disk.mixins.json` + `src/client/java/.../mixin/KeySortersMixin.java`（**客户端源集**）：AE2 物品网格"按 mod"排序在组内比字符串，于是 16k 排在 1k 前；混入在比较器构造处插一脚。只在当前屏幕是本模组终端、"数值排序"开着、且档位是 mod 时生效；`require = 0` 加 `required: false`，AE2 改签名时它只是静默失效
- `accesstransformer.cfg` / `mods_accessor.cfg`：访问 AE2 的非公开成员

---

## 8. 构建与开发

```bash
./gradlew compileJava compileClientJava   # 改完先跑这个
./gradlew assemble                        # 全量（含 client）
./gradlew runClient                       # 开发客户端
```

依赖的凑法（`libs/*.jar` 不在版本控制内）：

- AE2 / GuideME：在 `gradle/libs.versions.toml` 里声明，`compileOnly`（AE2 另加 `accessCompileOnly` 与 `clientCompileOnly`），运行时由整合包提供。
- JEI / EMI：`clientCompileOnly`，只是编译期 API。AE2WTLib / Jade / Polymorph / IPN 经 maven 或 CurseForge 自动解析，不需要本地 jar。
- NEO ECO：`compileOnly` 与开发运行期都用 CurseForge 发布件 `curse.maven:neo-eco-ae-extension-1460639:9095600`（21.2.1，2026-10-08 发布，已带本模组要的那半——并行接收注册表与报告式上传）。不再需要本地 jar；`21.2.1` 同时是运行期 `Neo ECO Prototype` 声明的版本地板。
- ExtendedAE Plus：需要 `libs/extendedae_plus-1.6.2-dev.jar`（用 `-PeaePlusJar=<路径>` 可换别的构建）。缺它时构建会回退到商店上的发布件，而那个版本与 JEI 19.56 不兼容、进世界会崩——做 EAE+ 相关的事之前先确认手上是本地 dev jar。
- AECS：放到 `libs/ae2cs-1.21.1-1.3.0.jar`，只作运行时。

---

## 9. 维护者要守住的东西

**协议面，只搬逻辑不搬标识**

- 客户端动作名、`@GuiSync` 的注解值、NBT key 字面量、序列号语义、`api` 包的签名。每台设备自己的键见它的 `saveAdditional` / `writeToNBT`：批处理装配室是 `cell0..8`、`disks`、`upgrades`、`fastBatchMode`、`pendingOutputs`；转存器是 `inv`、`mode`、`patternAccumulator`；自装配是 `crafted_contents`。

**线程边界**

- 一切元件访问、网格调用、世界访问在服务端 tick 线程。
- 唯一允许出线程的是样板分析，池子尺寸由速度卡定，线程是 daemon 且最低优先级，关闭挂在 `setRemoved` 上（漏了就是每台被拆的机器留一组线程）。

**状态一致性**

- 批处理窗口那一组字段（`queue`、`lastInputGameTime`、`lastActivityGameTime`、`lastRunJobs`、`shortRunStreak`、`idleFlushDone`、`returnStalled`、`lastHandedPattern`）默认只在服务端 tick 线程读写。拆解时不要复制成两份，要共用同一个持有者。`fastBatchMode` 不算——它是会存进存档的设置。

**日志**

- "同一个原因只报一次"是规矩，不是可选项。产物卡住时每 tick 都会重试，无条件打日志会刷屏；诊断卡住的机器要知道的是原因本身。相关实现见 `ProductDelivery.report`。

**拆解边界（什么时候该抽，什么时候别抽）**

抽出去的东西要能独立读懂、不反向依赖上层。判据是三个"不"：不用菜单服务（`getPlayer()`、`sendClientAction`、`notify*`、`sendPacketToClient`）、不拿 `GuiGraphics`、不是 AE2 契约方法（`writeToStream` / `readFromStream` / `saveAdditional` / `loadTag` / `tick` / `broadcastChanges` / `quickMoveStack` / `initializeContents`）。

反过来说，下面这些**故意不抽**，动它们之前先想想：

- 批处理的入队窗口与输出回送：队列被 NBT、取消、执行、回送等多处共享，抽出去是把共享可变状态换成跨对象协议。
- 编码菜单的写盘 / 取盘 / 空白样板记账：与 `getPlayer()`、回执、`ExtractRequest` 强耦合，抽完只剩同名转发。
- 编码菜单里的"待处理客户端输入"：字段经 `registerClientAction` 字符串协议读写，名字表必须留在菜单。
- 管理屏的几何与命中测试：持有屏幕可视状态，抽走得把 `offsetX`、`mouseX`、`scrollOffset`、可见行全打包成参数对象。

**注释里带原因的，别当废话删**

`CellBuffer` 的"全有或全无"、`SmoothReturnQueue` 的额度与优先通道、`ProductDelivery` 的"失败只报一次"、`PatternPlanCache` 的余料约定、`DiskPatternView` 的"缓存拿引用做键"——这些都是踩过坑写下来的。改动前先读完整段。

---

## 10. 常见改动指引

**加一台新设备**：`AEPatternRegistries` 注册（方块、物品、方块实体、菜单）→ `common/block` 方块类 → `common/block/entity` 方块实体 → `common/menu` 菜单 → `client/gui` 屏幕 → `data/ae2/screens` 布局 JSON → `assets/.../lang` 两份语言文件 →（可选）`integration/jade/provider` 状态显示。

**加一个邻居适配**：新建 `integration/<邻居>`，客户端侧放 `client/integration/<邻居>`；门禁写在独立的小类里（照 `AecsSoftDep` 或 `ClientExtendedAEPlusCompat` 的写法），不要让编译期可选类型的名字漏进总是加载的类。

**加一个磁盘容量档**：`PatternDiskTier` → 物品注册 → lang 两份 → `../README.md` 的容量表 →（若档位参与排序）`client/sort/SortTiers` 的配置。

**改终端布局**：`assets/ae2/screens/ae2_pattern_disk/*.json` 是唯一布局源，屏幕代码只按它给的位置摆放。能进文档的不止坐标：控件尺寸（`widgets.*` 的 width/height，面板的 `getBounds` 读它）、贴图切片（`images`，管理终端的表头带/六条行带/尾饰带/空槽格都在那里）、文字颜色（`palette`，`style.getColor(PaletteColor.X)`）都归文档。管理终端的行高、表头高、尾饰高与格距是从 `images` 切片的 srcRect 反推的，不再抄写常量——但同一份文档里 `terminalStyle` 的 header/row/bottom 与 `images` 的对应带高必须一致（前者算面板总高，后者反推行数）。控件在代码里必须按 id 注册（`widgets.add`）：文档有键而代码没注册，那块永远不出现；代码注册而文档没键，开屏即抛 `IllegalStateException`。

**动 API**：`api/package-info.java` 写明这个包是稳定面。改签名等于破坏第三方兼容，改之前先在 `../CHANGELOG.md` 记一笔。
