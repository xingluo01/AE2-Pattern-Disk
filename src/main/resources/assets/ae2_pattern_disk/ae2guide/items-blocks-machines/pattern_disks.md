---
navigation:
  parent: index.md
  title: 样板磁盘
  position: 10
item_ids:
- ae2_pattern_disk:pattern_disk_1k
- ae2_pattern_disk:pattern_disk_4k
- ae2_pattern_disk:pattern_disk_16k
- ae2_pattern_disk:pattern_disk_64k
- ae2_pattern_disk:pattern_disk_256k
---

# 样板磁盘

样板磁盘将编码的 AE2 样板存储在单个物品中。磁盘在空时未定型；插入的第一张样板决定了它的样板类型。

各阶磁盘行为一致，仅在容量上有所区别：

| 磁盘 | 样板容量 |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

使用样板转存器插入或移除样板。一张已填充的磁盘可放入样板磁盘供应器，供应器会将这些磁盘上的编码样板暴露给 ME 自动合成系统。

## 样板类型

一张磁盘只能包含一种类型的样板。合成样板、处理样板、锻造样板与切石样板分别使用独立的磁盘类型锁定。

## 兼容性说明

较大的阶别可能产生较大的物品组件负载。请将其保存在受控的库存中，并在使用非常大的磁盘时验证菜单与网络同步。

## 配方

<RecipeFor id="ae2_pattern_disk:pattern_disk_1k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_4k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_16k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_64k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_256k" />
