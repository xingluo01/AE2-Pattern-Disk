# 上游 API 请求（ECO / ECO Prototype）

> 状态：**已提交**（本地 2026-10-09 02:55 / UTC `2026-10-08T18:55Z`，见文末「提交记录」）。下面两份草稿保留原文，便于日后追进或修订时对照。
> 提出方：AE2 Pattern Disks（`ae2_pattern_disk`，作者 `xingluo01`）。
> 用例：**元件管理终端**——把网络里所有能装存储元件的东西列成一张表，并支持取出/放入元件、调整存储优先级。

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

下面两份草稿就是把这几处换成公开契约。

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

## 取证清单

所有签名来自实际 jar 的 `javap`，不是源码树：

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

## 提交记录

| 仓库 | issue | 提交时间 | 状态 |
|---|---|---|---|
| `DancingSnow0517/NeoECOAEExtension` | [#119](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/119) | 2026-10-08T18:55Z（本地 10-09 02:55） | OPEN（我方补 1 条评论，上游未回复） |
| `reliqwq/NeoECOPrototype` | [#5](https://github.com/reliqwq/NeoECOPrototype/issues/5) | 2026-10-08T18:55Z（本地 10-09 02:55） | OPEN（我方补 1 条评论，上游未回复） |

提交账号：`xingluo01`。正文用中文（两个仓库既有 issue 以中文为主，见上方取证清单）。

**为什么没给 issue 加标签**：标签需要仓库写权限；两条都是新提交，交由维护者自行归类。

### 补充评论（同日提交后补发，各一条）

目的：把「最小改动形状」讲明白，提高被采纳的概率（建议来自艾琳审查）。

| issue | 评论要点 | 链接 |
|---|---|---|
| ECO #119 | ① **`ICellHost` 不必真搬**——`api` 新增接口 + 让 `util.ICellHost` `extends` 它（改 1 处而非 4 处，且保二进制兼容）；② 我们只需要**读写存储主机上那个 int**，不需要上游提供界面；`IPriorityHost extends ISubMenuHost` 还要 `returnToMainMenu` + `getMainMenuIcon`，而它存储界面是 LDLib、无 AE2 菜单可回 ⇒ **公开 `setStoragePriority(int)` 才是真正需要的**；③ 提取原因若不愿把嵌套枚举提 `api`，中性返回（如 `@Nullable Component`）或只写进 `canExtractCell()` 的 javadoc 也够 | [comment-6067195947](https://github.com/DancingSnow0517/NeoECOAEExtension/issues/119#issuecomment-6067195947) |
| 青春版 #5 | ECO 的 `setCellStack` 是**无条件替换**，而 `insertCell` 在占用时返回 `false` ⇒ 直接委派会变成「静默忽略替换」，第三方分不清「清掉了」与「没清掉」；请在 javadoc 写清「替换还是忽略」。另：清格我们传 `ItemStack.EMPTY`（不传 `null`） | [comment-6067195902](https://github.com/reliqwq/NeoECOPrototype/issues/5#issuecomment-6067195902) |
