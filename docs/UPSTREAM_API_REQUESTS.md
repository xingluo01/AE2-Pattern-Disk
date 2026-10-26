# 上游 API 请求（AE2 本体 / ECO / ECO Prototype / ExtendedAE Plus）

> 状态：前两份、第四份与第五份**已提交**（前两份本地 2026-10-09 02:55 / UTC `2026-10-08T18:55Z`，第四份 UTC `2026-10-25T10:10Z`，第五份 UTC `2026-10-26T16:15Z`，见文末「提交记录」）；第三份是 2026-10 发现的兜底缺陷，**尚未提交**。下面五份草稿保留原文，便于日后追进或修订时对照。
> 提出方：AE2 Pattern Disks（`ae2_pattern_disk`，作者 `xingluo01`）。
> 用例：前两份是**元件管理终端**——把网络里所有能装存储元件的东西列成一张表，并支持取出/放入元件、调整存储优先级；第三份是**样板磁盘供应器的样板上传**；第四份是**批处理装配室的极大数合成**——单次交接量被 int 容量契约封在 21.4 亿份，而 FastPath 的 long 容量面没有注册表可用；第五份是**AE2 本体的语义缺陷**——`hasComponents()` 的返回值与名字相反，会污染按物品的替换判定缓存。

## 为什么会有这些请求

本模组相关的两台 ECO 家族存储设备，**都不能进编译面**：

| 设备 | modid | 依赖方式 |
|---|---|---|
| NEO ECO AE Extension（下称「ECO 本体」） | `neoecoae` | `compileOnly`，且我们只把 `cn.dancingsnow.neoecoae.api` 当作可依赖面 |
| Neo ECO Prototype（下称「青春版」） | `neoecoprototype` | 纯运行时可选，不参与编译 |

元件管理终端要认出这两家的「元件座」与「存储主机」。因为上面两条约束，现在的做法是**按方法名反射探**（`common/menu/CellDriveScanner.java`）。它能工作，但有几处契约只能实测反推：

- 清空元件必须传 `null`——传 `ItemStack.EMPTY` 会被静默拒收（读实现才发现，注释里没写）；
- 写入成功与否没有返回值可依，只能「写完再读一次」兜底；
- 取不出元件时无法区分「没装元件」与「被锁住」，终端只能笼统报一句「该槽位为空」。

以上是前两份草稿的由来；第三份见它自己的「背景」。第四份的缺口与这两处不同，见下。

### 第四份的来由

批处理装配室通过 `ECOParallelCraftingProvider` 接收合成批次，这条路径的容量签名是 `int eco$getAvailableParallelSlots()`，于是单次交接封顶 21.4 亿份。按 9:1 逐阶压缩九层的订单（目标 2.1e9 个九重）算，底层要 8.13e17 件圆石精华、整条链约 1.0e17 次合成——按 21.4 亿份/次的上限，这一单要几千万次交接。即使摊销路径本身不吃亏，交接次数也把它拖成跑不完的量级。

`ECOFastPathDispatchProvider` 的 `Preparation.capacity` 是 `long`，覆盖这个量级没有困难。问题在发现面上：ordinary 路径有 `ECOParallelCraftingProviders.register(...)` 这个注册表，FastPath 没有对应的，`ECOFastPathFacade` 只认 `instanceof` 加两个硬编码的 mod 专用桥。本模组不能把 ECO 的类型写进总是加载的类（可选依赖，缺 ECO 时会在加载阶段炸），所以只能走注册面。

---

## 草稿 1 → `DancingSnow0517/NeoECOAEExtension`

**标题**

```text
建议把 ICellHost 提为公开集成点，并补上存储优先级的公开写入口
```

**正文**

````text
## 背景

我在做一个 AE2 附属（AE2 Pattern Disks），其中有个「元件管理终端」要把网络里所有能装存储元件的东西列成一张表，并支持取出/放入元件、调整存储优先级。为了不硬依赖 ECO，我目前只能靠方法名反射来认 ECO 的元件座与存储主机。

这样能跑，但有几处契约只能靠实测反推，想请你考虑给一个公开面。

## 1. ICellHost 建议放进 api 包

`cn.dancingsnow.neoecoae.util.ICellHost` 正好就是第三方需要的那份契约：

    public interface ICellHost {
        void setCellStack(ItemStack);
        ItemStack getCellStack();
        boolean isItemValid(ItemStack);
        default boolean canExtractCell();
    }

`ECODriveBlockEntity` 实现了它。但它现在在 `util` 包里，对第三方来说不算「可依赖/可实现的公开面」——我不敢把它当稳定契约来 `instanceof`，只能按方法名探（`getCellStack` / `setCellStack` / `isItemValid`）。

**希望**：把 `ICellHost`（或一个等价接口）挪到 `cn.dancingsnow.neoecoae.api`，并在 javadoc 里注明它是「元件座」的稳定集成点。

顺带两处（如果有同感）：

- **`setCellStack` 返回 `void`**，我分不清「收下了」和「静默拒收」。实测传 `ItemStack.EMPTY` 会被拒、清空得传 `null`，这一点没写在注释里，是我读实现才发现的。改成返回 `boolean`（或至少在 javadoc 写明空栈/`null` 的语义）会稳很多。
- **提取被拒的原因查询没在接口上**。实现类有 `getCellExtractionBlockReason()`（返回 `ECODriveBlockEntity.CellExtractionBlockReason`），但接口上只有 `canExtractCell()`。第三方终端取不出元件时只能说「这个槽位是空的」，说不清是「没装元件」还是「被无限盘/权限挡住了」。把原因查询提到接口上，就能给玩家一句准确的提示。

## 2. 存储优先级缺一个公开写入口

`ECOStorageSystemBlockEntity` 的相关成员（`javap -p`）：

    private int storagePriority;
    public  int getStoragePriority();                     // 公开
    private void setStoragePriority(Player, int);          // 私有
    private void changeStoragePriority(Player, int);       // 私有

结果是第三方**只能读、不能写**。AE2 本体的驱动器是 `appeng.helpers.IPriorityHost`（`getPriority` / `setPriority`），任何加号的优先级界面都能直接用；ECO 这边没有对应入口的话，我只能在终端里把「存储优先级」这一列做成只读。

**希望二选一（或者都给）**：

- 给一个公开的 `setStoragePriority(int)`；或
- 让存储主机实现 AE2 的 `appeng.helpers.IPriorityHost` —— 这样 AE2 生态里所有现成的优先级 UI 都能直接作用于 ECO，各家不必自己适配。

## 取证方式

以上签名来自 `neoecoae-21.2.1`（CurseForge file 9095600）的 `javap`，不是在源码树上读的；若有出入请以你那边为准。

如果你的设计意图是「这些属于内部实现、不打算开放」，直接说一声也行，我就继续走反射、不再追这个面。
````

---

## 草稿 2 → `reliqwq/NeoECOPrototype`

**标题**

```text
建议让 L1 驱动器实现 ECO 的 ICellHost
```

**正文**

````text
## 背景

我在做一个 AE2 附属（AE2 Pattern Disks），其中的「元件管理终端」会把网络里所有能装存储元件的东西列成一张表，并支持取出/放入元件、调整存储优先级。ECO 本体与你这边的 L1 驱动器都要能认出来。

## 现状

`SimplifyDriveBlockEntity`（1.3.1）的公开方法：

    public ItemStack getCellStack();
    public boolean   isItemValid(ItemStack);
    public boolean   hasCell();
    public boolean   insertCell(ItemStack);   // 返回布尔
    public ItemStack removeCell();            // 空/锁定时返回 ItemStack.EMPTY
    public IECOStorageCell getCellInventory();

这几乎就是 ECO 的 `ICellHost`——只差一个方法：

    // cn.dancingsnow.neoecoae.util.ICellHost（ECO 侧）
    void setCellStack(ItemStack);
    ItemStack getCellStack();
    boolean isItemValid(ItemStack);
    default boolean canExtractCell();

因为没实现它，第三方只能按方法名探（我这边就是这么做的）；而 `insertCell` 返回 `boolean`、`removeCell` 返回 `ItemStack.EMPTY` 两套口径混用，写入的成功/失败只能靠「写完再读一次」兜底。

## 建议

给 `SimplifyDriveBlockEntity` 加上 `setCellStack(ItemStack)` 并 `implements ICellHost`：

- 非空栈 → 走 `insertCell` 的既有逻辑；
- `ItemStack.EMPTY` / `null` → 走 `removeCell()`。

这样 ECO 本体与青春版在第三方眼里就是同一种东西，不必各写一套探测。

**一处前置依赖**：`ICellHost` 目前在 `cn.dancingsnow.neoecoae.util`，不在 `api` 包里。我也已经向 ECO 提了一条，建议把它提为公开集成点；那边若不动，你直接提供同名同签名的 `setCellStack(ItemStack)` 也有同样效果（我不需要真的 `instanceof` 那个接口）。

## 顺带

- **存储优先级那侧没问题**：`SimplifyStorageHostBlockEntity` 的 `getStoragePriority()` / `setStoragePriority(int)` 都是公开的，我这边已经能读写（与本体的 `setStoragePriority` 私有形成对比）。
- 驱动器到所属存储主机现在是 `getCluster().getController()`。如果有一个「我属于哪台主机」的直接访问器会省一步，非必须。
- 若愿意再让存储主机实现 AE2 的 `appeng.helpers.IPriorityHost`，AE2 生态里现成的优先级 UI 就能直接用。

## 取证方式

以上签名来自 `neo-eco-prototype` 1.3.1（CurseForge file 9098136）的 `javap`，不是在源码树上读的；若有出入请以你那边为准。
````

---

## 草稿 3 → `GaLicn/ExtendedAE_Plus`（尚未提交）

**标题**

```text
「上传到装配矩阵」按简单类名匹配供应器，会写进磁盘背书的派生样板栏
```

**正文**

````text
## 背景

我在维护 AE2 Pattern Disks（`ae2_pattern_disk`）。玩家反馈：用「上传到装配矩阵」把样板传到本模组的自装配样板磁盘供应器后，样板既不在任何磁盘上，也找不到去向；但合成 CPU 确实会把它当成可用样板派发，拆除设备后彻底消失。

## 现状

`ExtendedAEPatternUploadUtil.findAllMatrixPatternInventories(IGrid)`（`develop/1.21.1`，`82368cf`）末尾有一段按类名匹配的分支：

    if (className != null && className.contains("MeteoritePatternProvider")) {
        ...
        InternalInventory inv = logic.getPatternInv();   // 直接写镜像栏
        result.add(new MatrixInventoryTarget(inv, inv, null, false));

这段是给 AE2 Crystal Science 的自装配供应器（`io.github.lounode.ae2cs.common.block.entity.MeteoritePatternProviderBlockEntity`）写的，那边的 `getPatternInv()` 就是真实的样板库存，写法没问题。

问题是**匹配用的是简单类名子串**。本模组也有名为 `MeteoritePatternProviderBlockEntity` / `MeteoritePatternProviderPart` 的设备（自装配样板磁盘供应器），于是同样被命中；而那台设备的 `getPatternInv()` 只是磁盘展开出来的**派生副本**——真身在样板磁盘的物品组件里，终端读写的入口是 `getTerminalPatternInventory()`。

于是 `uploadPatternToMatrix(...)` 的 `target.insertInventory().addItems(...)` 写进了派生副本：

- 样板没进任何磁盘，却会被 `PatternProviderLogic#updatePatterns()` 解码成可用样板注册进网络；
- 设备拆除时派生副本被清空且不进掉落，玩家等于凭空丢一张样板；
- 磁盘内容一变动，重建就把这张样板抹掉，玩家无从追查。

## 建议

二选一（任一都能修掉）：

1. **按命名空间判定**：改成 `machineClass.getName().startsWith("io.github.lounode.ae2cs.")`。简单类名撞名在模组生态里是常态，子串匹配会把同名的第三方设备一起吃掉。
2. **统一走 `getTerminalPatternInventory()`**：AE2 给 `PatternContainer` 的这份契约本来就是写样板的入口，磁盘背书的设备会在那里把样板落到磁盘上；对 AE2CS 自己的供应器也没有行为变化。

如果以后还要认「能存样板但不是装配矩阵」的设备，建议直接认 `PatternContainer`，让设备自己决定样板落到哪里。

## 取证方式

- 命中行：`ExtendedAEPatternUploadUtil.java:993-1014`（分支）、`:813` 与 `:896`（`addItems` 写入）、`:870` `uploadPatternToMatrix` 入口；触发入口 `CreateAndUploadPatternC2SPacket.java:136`（Ctrl+Q / JEI-EMI 书签）。
- 读的是 `develop/1.21.1` 的 `82368cf`（2026-10-09），不是发布 jar；若已改过请以你那边为准。
- 本模组侧已把 `getPatternInv()` 改成只读，升级后这一路径会明确报「上传失败」而不是静默丢样板；上游改了之后两边都正常。
````

---

## 草稿 4 → `DancingSnow0517/NeoECOAEExtension`（已提交）

**标题**

```text
建议给 FastPath 加一个和 ECOParallelCraftingProviders 同形的 provider 注册表
```

**正文**

````text
## 背景

我在维护 AE2 Pattern Disks（`ae2_pattern_disk`），其中一台「批处理装配室」通过你的 provider 契约接收合成批次。

普通并行路径已经在用了：`ECOParallelCraftingProviders.register(...)` 注册一个 adapter，`eco$getAvailableParallelSlots()` + `eco$pushPatternBatch(...)` 都在跑。这部分没有问题。

卡住的是容量。`eco$getAvailableParallelSlots()` 返回 `int`，所以单次交接最多 21.4 亿份。我这边常见的订单是 9:1 逐阶压缩九层，目标 2.1e9 个顶层物品——整条链约 1.0e17 次合成，按这个上限要几千万次交接。即使摊销路径本身不吃亏，交接次数也把它拖成跑不完的量级。

`ECOFastPathDispatchProvider.Preparation.capacity` 是 `long`，量级上没有这个限制，这是我想走的路径。

## 现状

问题在发现面。`ECOFastPathFacade` 认 provider 的方式是：

    public static boolean supports(ICraftingProvider provider) {
        return provider instanceof ECOFastPathDispatchProvider
            || ECOExtendedAEPlusMatrixBridge.supportsProvider(provider)
            || ECOUselessBatchProviderBridge.supports(provider);
    }

    public static ECOFastPathDispatchProvider resolveProvider(ICraftingProvider provider) {
        if (provider instanceof ECOFastPathDispatchProvider nativeProvider) return nativeProvider;
        var matrix = ECOExtendedAEPlusMatrixBridge.adapt(provider);
        return matrix != null ? matrix : ECOUselessBatchProviderBridge.adapt(provider);
    }

也就是说：要么 provider 类自己 `implements ECOFastPathDispatchProvider`，要么命中那两个写死的桥。ordinary 那边的 `ECOParallelCraftingProviders` 是通用注册表（第三方类不能实现你的接口，所以由集成方交一个 adapter 进来），FastPath 没有对应物。

我的机器方块实体在 `ae2_pattern_disk` 的总是加载的类里。ECO 对我是可选依赖，把 `implements ECOFastPathDispatchProvider` 写上去就等于强制依赖——缺 ECO 时加载阶段直接炸。这不是能靠 try/catch 挡住的。

## 建议

给 FastPath 补上发现面。三种形状，改动量从小到大：

1. **复用既有注册表（最小）**。`ECOParallelCraftingProviders.find(...)` 已经是公开入口；adapter 返回的对象本来就允许**同时**实现 `ECOParallelCraftingProvider` 与 `ECOFastPathDispatchProvider`——返回类型不限制运行期实现。只要 `ECOFastPathFacade` 在那几处接口测试里、`instanceof` 判空之后多查一次注册表并对结果再 `instanceof ECOFastPathDispatchProvider`，整条链路就通了：改一个文件、约四行，不改任何公开签名。
2. **新建 `ECOFastPathProviders`**，照抄 `ECOParallelCraftingProviders` 的形状（`register(Class<? extends ICraftingProvider>, Adapter<E>)` + `find(Object)`），facade 里多一处查询。比第 1 种多一个公开类、一张表、一套缓存。
3. **放宽 `Adapter` 的返回类型**（不推荐）。`find()` 的返回值被普通派发直接当 `ECOParallelCraftingProvider` 用，放宽要么加类型参数、要么在普通派发侧再加分支——改动面最大，而且动的是已经在跑的那条公共契约。

三种都以 `find` 命中为准，未注册的 provider 行为完全不变。另外，`ECOFastPathFacade.prepareAllocated`（`ECOFastPathFacade.java:103-105`）也直接 `instanceof ECOFastPathDispatchProvider`；如果那条入口也要对第三方 provider 生效，第 1 种里「那几处」要把这处算进去。

## 我这边会怎么用

注册一个 adapter，返回的对象实现 `ECOFastPathDispatchProvider`：

- `eco$prepareFastPath(context)` 不消耗任何资源，只读 `context` 与自己的元件缓冲，返回 `Preparation(capacity = 元件还能装下的完整配方数, statefulCalculator = null, statefulCalculatorRequired = false, dispatch = ..., supportsExactInputs = false)`；算出来装不下一份（capacity < 1）时直接返回 `null`，因为 `Preparation` 的构造器对非正容量会抛，而那个抛出去会被上游吞成一行 debug 日志。后三个参数就是 `Preparation` 四参重载的默认值，写四参也一样；
- `dispatch` 里把 `batch.inputTotal()` 的材料真实搬进元件缓冲，全成功才返回 true；任何一个 key 装不下就返回 false（不做部分接受）；
- `statefulCalculator` 我确实没法用——`ECOVerifiedFastPathRecipe.trusted(...)` 是包私有的，recipe 只能由你自己的 fastpath 缓存签发。如果以后想让我们这类第三方 provider 也能表达「可复用晶体整批只抽一次」的语义，那需要另一个面；这一条不阻塞，`null` 加 `required = false` 就能走线性路径。

`eco$prepareExactFastPath` 我暂时用不上：我的订单在 long 范围内（整条链 1.0e17 次合成，远低于 9.22e18）。但如果注册面通了，后续接 BigInteger 订单只是实现一个方法。

## 一处需要注意的地方

`ECOBatchDispatchContext.inputs()` 是**单份**口径（每个输入槽一行、代表一份配方），`Batch.inputTotal` 是**总量**。两者名字接近但不可缩放：前者只用来算「一份要什么」，后者才是这次交接要搬走的量。`Batch` 的集合被输入租约持有用于回滚，我不会改动它们。

## 取证方式

以上行号与签名读的是 `main` 分支：`ECOFastPathFacade.java:37-41`（`supports`）与 `:44-48`（`resolveProvider`）、`ECOParallelCraftingProviders.java:43-49`（`Adapter` 的返回类型）、`ECOFastPathDispatchProvider.java:43`（`Preparation.capacity` 是 `long`）与 `:54-57`（非正容量直接抛）、`ECOParallelCraftingProvider.java:10`（`int eco$getAvailableParallelSlots()`）。若已改过请以你那边为准。

如果你的设计意图是「FastPath 只给已验证的自家与个别合作方使用」，直接说一声也行，我就停在 ordinary 路径、不再追这个面。
````

---

## 草稿 5 → `AppliedEnergistics/Applied-Energistics-2`（已提交）

**标题**

```text
hasComponents() 的返回值与名字相反，会污染按物品的替换判定缓存
```

**正文**

````text
## 现象

`AEKey#hasComponents()` 的 javadoc 是「true if this key has *any* components attached」，但两个实现都写反了：

- `AEItemKey.java:232`：`return stack.getComponents().isEmpty();`
- `AEFluidKey.java:162`：同上

于是「没有组件」的 key 返回 true，「带组件」的返回 false。

## 影响

`AECraftingPattern.getTestResult` / `setTestResult`（`AECraftingPattern.java:306-326`）与 `AEStonecuttingPattern.java:206-219`
用它决定「能不能按 what.getItem() 缓存这个输入的合法性」：

- 本意：带附加组件的键不进按 `Item` 的缓存（不同组件变体的合法性可能不同）；1.20.5 之前这里写的是 `what.hasTag()`。
- 实际：`getComponents()` 返回的是「物品原型组件 + patch」的**完整**集合，对几乎所有物品非空，
  于是 `hasComponents()` 几乎恒为 `false` —— 判定结果对所有键（含带组件变体）都按 `getItem()` 进出缓存。

后果：同一物品的不同组件变体之间会串用判定结果（错判）。修复应基于 `getComponentsPatch()`，
而不是把 `getComponents().isEmpty()` 改写成 `!getComponents().isEmpty()`（后者会让几乎所有键都
「有组件」，缓存全失效）。

## 复现

1. 一个 `canSubstitute` 的配方，某个槽位接受物品 A；
2. 用 A 的两个不同组件变体 A1 / A2（如不同附魔程度），其中一个对该槽位合法、另一个不合法；
3. 先判合法那个、再判不合法那个，看第二次是否直接复用第一次的结果（缓存键只有 `Items.A`，不含组件）。

如果你们认为缓存本来就该按 Item 共享（即 javadoc 才是写错的），那也只需把注释改成「返回 true 表示没有组件」——两个入口至少得对齐一处。

## 取证方式

以上行号读自本地克隆 `D:/Temp/Applied-Energistics-2` 的 `1.21.1` 分支 @ `db17504`（该克隆没有 `main`）；javadoc 与实现的分歧以该提交为准。
````

## 取证清单

前三份的签名来自实际 jar 的 `javap`；第四份读的是上游 `main` 分支源码（本地克隆 `D:/Temp/NeoECOAEExtension`，`api/me/provider` 的七个文件与 21.2.10 逐个比对一致；未联网核对 blob sha）；第五份读的是 AE2 本地克隆（`D:/Temp/Applied-Energistics-2`，`1.21.1` @ `db17504`）。三类来源的区别在表内分别标出。

| 事实 | 来源 |
|---|---|
| `ICellHost` 在 `cn.dancingsnow.neoecoae.util`，四个成员（`setCellStack` 返回 `void`） | `neoecoae-21.2.1`（CF file 9095600） |
| `ECODriveBlockEntity implements ICellHost`，另有 `getCellExtractionBlockReason()` | 同上 |
| `ECOStorageSystemBlockEntity`：`getStoragePriority()` 公开，两个 setter 私有 | 同上（`javap -p`） |
| `ECOStorageSystemBlockEntity` **未**实现 `IPriorityHost` | 同上 |
| `SimplifyDriveBlockEntity`：`getCellStack` / `isItemValid` / `insertCell` / `removeCell` / `hasCell` / `getCellInventory`，**未**实现 `ICellHost` | `neo-eco-prototype` 1.3.1（CF file 9098136） |
| `SimplifyStorageHostBlockEntity`：`get/setStoragePriority(int)` 均公开 | 同上 |
| `SimplifyStorageCluster`：`getController()` / `getDrives()` 公开 | 同上 |
| 两个仓库既有 issue 以**中文为主** | GitHub 列表：青春版 open 全中文；ECO open 14 条里 13 中文（#103 为英文） |
| 上游**没有**现成的等价 API（已逐项找过） | ① ECO 全仓 `IPriorityHost` 0 命中；`command/` 无 priority；`api/`（含 `api/storage/`、`api/integration/`）无 `Priority` / `CellHost` 类型。② 唯一能写优先级的是 `private void setStoragePriority(Player,int)`，且只被自家 LDLib 的 `StorageHostActionUI.Config` lambda 驱动。③ 青春版无 `setCellStack` |
| ECO 仓库地址取自其 `mods.toml` 的 `issueTrackerURL`；青春版 `mods.toml` **未**声明 tracker，仓库为 `reliqwq/NeoECOPrototype` | 两个 jar 的 `META-INF/neoforge.mods.toml` + GitHub 检索 |
| 上游 `21.2.1` **确实**编译期依赖本模组的 `PatternDiskApi`（与 `NeoECOIntegration` 的 javadoc 一致） | 该 jar 里有 `cn/dancingsnow/neoecoae/integration/ae2pattern/`（`AepdPatternDiskBackend` 等）；`mods.toml` 把 `ae2_pattern_disk` 列为可选依赖 |
| `ECOParallelCraftingProviders.Adapter` 的返回类型被写死为 `ECOParallelCraftingProvider` | `ECOParallelCraftingProviders.java:43-49`（`main`） |
| `ECOFastPathFacade.supports` 只认 `instanceof` + 两个硬编码桥，无注册表 | `ECOFastPathFacade.java:37-41`（`main`）；`resolveProvider` 在 `:44-48`；`prepareAllocated` 在 `:103-105` 也硬测该接口；`ECOBatchCapacityProvider.java` 是它的 `@Deprecated` 旧名 |
| FastPath 容量是 `long`，ordinary 容量是 `int`，且非正容量在构造器就抛 | `ECOFastPathDispatchProvider.java:43` / `:54-57`；`ECOParallelCraftingProvider.java:10`（`main`） |
| `ECOVerifiedFastPathRecipe.trusted` 是包私有，第三方无法构造 `ECOStatefulBatchCalculator` | `crafting/execution/fastpath/ECOVerifiedFastPathRecipe.java:54`（`main`） |
| `ECOJobAwareCraftingProvider` 在 21.2.10 才有，本地克隆（`ecba648`）里没有 | 上游 `api/me/provider` 目录页 vs 本地目录列举 |
| **AE2**：`hasComponents()` 的 javadoc 说「true if this key has *any* components attached」，实现却是 `stack.getComponents().isEmpty()` | `AEKey.java:351-354`（javadoc 与抽象声明）、`AEItemKey.java:232`、`AEFluidKey.java:162`（本地克隆 `D:/Temp/Applied-Energistics-2`，`1.21.1` @ `db17504`） |
| **AE2**：该方法的两个消费点就是 `getTestResult` / `setTestResult`，缓存键只取 `what.getItem()`（不含组件） | `AECraftingPattern.java:306-326`、`AEStonecuttingPattern.java:206-219`（同上） |
| **AE2**：`ItemStack#getComponents()` 返回「物品原型 + patch」的**完整**组件集合（因此 `isEmpty()` 对几乎所有物品为 `false`）；判「有无附加组件」应看 `getComponentsPatch()` / `isComponentsPatchEmpty()` | `ItemStack.java:228-242`、`PatchedDataComponentMap.java:184-200`（NeoForge 21.1.241 源码 jar，`build/moddev/artifacts/neoforge-21.1.241-sources.jar`） |

## 提交记录

| 仓库 | issue | 提交时间 | 状态 |
|---|---|---|---|
| `DancingSnow0517/NeoECOAEExtension` | [#119](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/119) | 2026-10-08T18:55Z（本地 10-09 02:55） | **已实现并已关闭**：ECO **21.2.6**（2026-10-16 发布）含 `api.storage.IECOStoragePriorityHost` 与提到 `api.storage` 的 `ICellHost`；维护者 Yang120231 于 2026-10-09 回「已添加」并标记 completed |
| `reliqwq/NeoECOPrototype` | [#5](https://github.com/reliqwq/NeoECOPrototype/issues/5) | 2026-10-08T18:55Z（本地 10-09 02:55） | **已实现**（issue 仍 OPEN）：青春版 **1.3.6**（2026-10-16 发布）的 `SimplifyDriveBlockEntity` 已 `implements util.ICellHost` 并补 `setCellStack`；作者 reliqwq 于 2026-10-09 回「随下一个版本发出」 |
| `DancingSnow0517/NeoECOAEExtension` | [#134](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/134) | 2026-10-25T10:10Z（本地 10-25 18:10） | **待处理**：请 FastPath 补一个与 `ECOParallelCraftingProviders` 同形的 provider 注册面，让第三方能在不硬依赖 ECO 的前提下拿到 `long` 容量。未实现前本模组的批处理装配室停在 ordinary 并行路径 |
| `AppliedEnergistics/Applied-Energistics-2` | [#9040](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/9040) | 2026-10-26T16:15Z（本地 10-27 00:15） | **待审**：`hasComponents()` 改成基于 `getComponentsPatch()`（`isComponentsPatchEmpty()`），修掉「带组件变体按 Item 共享判定缓存」与 `AEFluidKey#toString` 的同一错误；附回归测试，未本地构建（本机拿不到 Gradle 8.12 发行包） |

提交账号：`xingluo01`。正文用中文（两个仓库既有 issue 以中文为主，见上方取证清单）。

**为什么没给 issue 加标签**：标签需要仓库写权限；两条都是新提交，交由维护者自行归类。

### 补充评论（同日提交后补发，各一条）

目的：把「最小改动形状」讲明白，提高被采纳的概率（建议来自艾琳审查）。

| issue | 评论要点 | 链接 |
|---|---|---|
| ECO #119 | ① **`ICellHost` 不必真搬**——`api` 新增接口 + 让 `util.ICellHost` `extends` 它（改 1 处而非 4 处，且保二进制兼容）；② 我们只需要**读写存储主机上那个 int**，不需要上游提供界面；`IPriorityHost extends ISubMenuHost` 还要 `returnToMainMenu` + `getMainMenuIcon`，而它存储界面是 LDLib、无 AE2 菜单可回 ⇒ **公开 `setStoragePriority(int)` 才是真正需要的**；③ 提取原因若不愿把嵌套枚举提 `api`，中性返回（如 `@Nullable Component`）或只写进 `canExtractCell()` 的 javadoc 也够 | [comment-6067195947](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/119#issuecomment-6067195947) |
| 青春版 #5 | ECO 的 `setCellStack` 是**无条件替换**，而 `insertCell` 在占用时返回 `false` ⇒ 直接委派会变成「静默忽略替换」，第三方分不清「清掉了」与「没清掉」；请在 javadoc 写清「替换还是忽略」。另：清格我们传 `ItemStack.EMPTY`（不传 `null`） | [comment-6067195902](https://github.com/reliqwq/NeoECOPrototype/issues/5#issuecomment-6067195902) |
