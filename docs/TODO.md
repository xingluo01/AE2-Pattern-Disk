# AE2-Pattern-Disk 需求待办表

> 状态图例：⬜ 未开始 | ⏸ 暂缓
> 优先级：P1 最高 | P2 | P3
>
> 本表只放**还没做完的事**。已完成条目不留档（看 `CHANGELOG.md` 与 git 历史）；已放弃与有意不做的取舍去找对应的提交与代码注释，
> 本表不再列；发版流程见 `RELEASE.md`；工程约束（版本基线、只用公共 API、美术资源约定）见 `ARCHITECTURE.md`；
> 上游请求见 `UPSTREAM_API_REQUESTS.md`。

## 一、未开始

### G. 批处理装配室：multiplier 由多个变体拼够（对齐 AE2）— P3 ⬜
- 现状：`resolveVariant` 要求**单个 key 覆盖整个 multiplier**（同物品的已磨损变体有一条回退，但仍是一槽一变体）；缓冲不足直接判 `INPUTS_UNAVAILABLE`、样板留队列。
- AE2 语义：`CraftingCpuHelper.extractPatternInputs` 遍历同槽多个合法模板，每个抽多少算多少，允许多变体拼够一个 multiplier。
- 影响：样板要 4 把工具、缓冲里是「2 新 + 2 磨损」时，AE2 分子装配室能跑，本机不接单。
- 改动面：`resolveVariant`（单 key 判定）+ `consumeInputs`（按 key 分桶）→ 返回使用记录；余料按**实际使用的每个变体**分别记账（`IInput.getRemainingKey` 按变体算）。
- 验证：多变体混合缓冲 + 磨损工具样板，核对 ① 网络收到的产物/余料 ② CPU `waitingFor` 是否满足 ③ 机器内无残留。

### H. `AEItemKey.hasComponents()` 语义与命名相反 — P3 ⬜（待报上游）
- 现象：AE2 19.2.17 里实现为 `return stack.getComponents().isEmpty();`——组件为空时返回 `true`，与名字相反。
- 消费点：`AECraftingPattern.getTestResult` 用它决定是否走「按物品」的替换缓存（键为 `what.getItem()`）⇒ 带组件的输入反而可能命中按物品的缓存。
- 待办：整理最小复现（带组件输入 + 可替换配方）后报 AE2 上游。
- 约束：本模组**不要**用这个方法判组件，需要时直接用 `!stack.getComponents().isEmpty()`。

### L. 高级档没有专属标记 — P3 ⬜（已知行为，非缺陷；审查时不要再当 bug 报）
- 现状：高级档不在 AE2 的 `EncodingMode` 枚举里，切进去时**不改 `mode`**（只 `setAdvancedMode(true)`），而绑盘读的正是 `menu.mode` ⇒ 高级档写出的标记是「进入高级档之前那个常规档」的标记。
- 影响：不崩不丢东西；磁盘列表里看起来像处理样板；从处理档进入时「唯一目标自动落盘」不生效。
- 选盘口径 2026-10 已重构为「以样板类型为锚点」（见 `ENCODING_MODES.md` §七），「继承标记把盘引到错误匹配项」那条路径已断。剩下的取舍是：**要不要给高级档一个固定标记**。
- 若要做，先定取舍：雕凿的 `uploadMark()` 是先短路再回退 `deriveMarkId()`，照抄会顶掉「导入过配方就写 `#<配方类别>`」那一支。高级档要不要也放弃类别信息？要保留的话，固定标记只能在「没导入过」时生效。
- 落点与顺序约束（雕凿那轮踩过的）见 `ENCODING_MODES.md` §七的「给某个档补一个专属标记」。

### N. 上游 API 接入的剩余项 — P3 ⬜
- 背景：已接入 ECO `21.2.6` 的 `api.storage.IECOStoragePriorityHost` 与青春版 `1.3.6` 的 `setCellStack`，本模组侧走「接口优先、反射兜底」（`integration/neoecoae/NeoECOStoragePriority.java`）。
- ⬜ `ICellHost` 的类型化适配：上游已把 `ICellHost` 提到 `api.storage`，可新增 `integration/neoecoae/NeoECOCellHosts.java` 做编译期类型化，让 `CellDriveScanner` 只调中立 SPI、不再按名字探；`setCellStack(null)` / `EMPTY` / `removeCell()` 三路试探可收敛成一次文档化调用。
  - **硬约束**：SPI 的实现注册必须在「ECO 在场」的类里（`NeoECOIntegration`）。`CellDriveScanner` 是无条件加载的 common 代码，它若直接 `instanceof` 上游接口，缺 ECO 时会 `NoClassDefFoundError`。
  - 青春版只能走 SPI（纯运行时可选，连 compileOnly 都没有）。
- ⬜ 提取原因：本模组已有自己的 `ExtractResult`（4 值 `OK` / `EMPTY` / `LOCKED` / `REFUSED`，后两者出声、`EMPTY` 静默）；剩下的是「改用上游枚举给出更细的原因」。
- 维护：`UPSTREAM_API_REQUESTS.md` 的提交记录表随进展更新（UTC 时间戳）。

### F3. ModDevMCP 的供给方式清理 — P3 ⬜
- `build.gradle` 的 `localRuntime 'dev.vfyjxf:moddevmcp:0.1.6'` 仍在，而本机 mavenLocal 并无该版本（靠 composite-build 替换），它与 `compileOnly files('libs/moddevmcp-0.3.jar')` 并存，可能让 dev 运行时出现两份副本。单独一轮处理，并在本地跑一次 `runClient` 确认无重复 mod 载入。

### F4. GUI 纹理来源待确认 — P3 ⬜
- `textures/guis/pattern_modes.png`、`states.png` 与 AE2 同名件同画布但像素差异较大（约 69% / 58%），来源待作者确认后在 `README.md` 的授权表里归类；现按 unconfirmed 列出。

### F5. 配置界面补节 `.button` 键 — P3 ⬜
- 11 条配置节缺 `.button` 键（NeoForge 配置屏不补则回退平台自带的「编辑 / Edit」字样），`ae2_pattern_disk.configuration.title` 同理。键形态的坑见 `ARCHITECTURE.md` §10 的「加一个配置项」。

## 二、暂缓

### J. 极大数（远超 2.1G）兼容性优化 — P2 ⏸
- 现象：极大订单导致世界线程阻塞、autosave 无法写入。
- 剩余线路：
  - **对外契约扩展**：万象构序 long 路径（`OmniBatchAdmission.maxCrafts()` 本为 long）；可选接 `OmniBigIntegerCraftingProvider`。NeoECO 路径受上游 int 签名所限。
  - **审计补齐**：long 算术全量溢出审计；网络包与 NBT 的计数类型；其他 block entity 同类风险。
- 验收口径：任意份数下单 tick 处理量恒定、峰值 ≤ 50ms；摊销路径仍 O(1)；NeoECO 路径行为逐项不变；接近 `Long.MAX_VALUE` 的输入不崩、不截断、不丢、不复制。

### 8. EAE 大型分子装配室兼容 / 容器变体 — P3 ⏸
- EAE 矩阵 Pattern 槽硬过滤，磁盘配方只能间接进矩阵（高难度 IAECluster），暂缓。

### 9. 完整紧凑编码（改 `PatternDiskContents` 数据格式）— ⏸ 高风险
- 破坏存档兼容，保留后续专轮 + 迁移。

## 三、归档索引
- 已完成条目与实现细节：`CHANGELOG.md`、git 历史、`ARCHITECTURE.md` 的模块说明。
- 上游请求与状态（ECO / 青春版 / EAE+）：`UPSTREAM_API_REQUESTS.md`。
- 发版流程与踩坑：`RELEASE.md`。
