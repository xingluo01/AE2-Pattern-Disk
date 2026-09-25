---
navigation:
  parent: index.md
  title: 样板磁盘
  icon: pattern_disk_1k
  position: 1010
item_ids:
- ae2_pattern_disk:pattern_disk_1k
- ae2_pattern_disk:pattern_disk_4k
- ae2_pattern_disk:pattern_disk_16k
- ae2_pattern_disk:pattern_disk_64k
- ae2_pattern_disk:pattern_disk_256k
---

# 样板磁盘

<Row gap="20">
  <ItemImage id="pattern_disk_1k" scale="4" />
  <ItemImage id="pattern_disk_4k" scale="4" />
  <ItemImage id="pattern_disk_16k" scale="4" />
  <ItemImage id="pattern_disk_64k" scale="4" />
  <ItemImage id="pattern_disk_256k" scale="4" />
</Row>

样板磁盘把编码后的 AE2 样板存放在单个物品中。空置的磁盘不限定样板类型；写入第一张样板后即锁定为该类型，此后只接受同类型样板。

五级磁盘的功能完全相同，仅容量不同：

| 磁盘 | 样板容量 |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

样板可由 [ME样板转存器](pattern_transferer.md) 写入磁盘，也可在 [ME样板磁盘编码终端](pattern_disk_encoding_terminal.md) 中把刚编码的样板直接写入。装满样板的磁盘放入 [ME样板磁盘供应器](pattern_disk_provider.md) 后，其中的样板即提供给 ME 自动合成系统。

## 样板类型

一张磁盘只接受一种样板类型。合成、处理、锻造、切石四种样板各自锁定对应的磁盘类型；装了 AdvancedAE 时，它的高级处理样板同样锁定并显示为独立类型。

下表按「类型 × 容量」列出每一种组合的外观：行是样板类型及其来源，列是磁盘容量。五种容量的底图完全相同，只有容量层随容量变，右上角的角标随类型变，所以看角标颜色就知道盘里装的是哪一类样板。

| 类型 | 来源 | 1k | 4k | 16k | 64k | 256k |
| --- | --- | :-: | :-: | :-: | :-: | :-: |
| 未定型（空盘） | — | ![](/assets/pattern_disk_types/pattern_disk_untyped_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_256k.png) |
| 合成 | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_crafting_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_256k.png) |
| 处理 | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_processing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_256k.png) |
| 锻造 | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_smithing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_256k.png) |
| 切石 | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_256k.png) |
| 高级处理 | AdvancedAE | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_256k.png) |

## 标记

标记记录一张磁盘所属的机器或配方类别。标记显示在 [ME样板磁盘编码终端](pattern_disk_encoding_terminal.md) 的磁盘列表中：悬停磁盘时多出一行「标记：…」，开启 F3+H 时另有一行原始标记 ID。写入标记不会改动磁盘名称。

标记由编码终端的磁盘列表写入，共两种方式：

- **手持工作方块右键**：记录鼠标上工作方块所属的配方类别，例如工作台、切石机、锻造台、熔炉、烟熏，不依赖当前导入的配方。
  - 鼠标上没有持有所属类别的工作方块时，改为记录刚导入配方的类别；两者都没有时不写入标记。
  - 判定依据是配方查看器的机器表（EMI 用其登记的工作站，JEI 只用催化剂）；无法识别的方块会给出提示，本次右键不写入标记。
- **Shift+右键**：把搜索栏中的文本记为标记；搜索栏为空则清除标记。该方式不依赖当前导入的配方，可把同一标记写入任意磁盘。

编码终端把合成、锻造、切石的标记归一到对应类别名，因此手动编码的切石样板盘与导入过切石配方的磁盘在列表中同名，搜索结果一致。处理模式没有唯一类别，各机器互不相同，因此手动编码的处理样板盘保留「处理样板」这一名称。

标记同时是编码终端磁盘列表中 `#` 搜索的筛选依据，并决定中键重命名时所使用的工作方块名称。

## 兼容性

磁盘容量越大，物品携带的数据越多。建议存放在普通箱子一类容器中，避免长时间停留在大容量磁盘的物品界面里。

## 合成配方

<RecipesFor id="ae2_pattern_disk:pattern_disk_1k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_4k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_16k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_64k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_256k" />
