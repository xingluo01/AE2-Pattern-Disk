---
navigation:
  parent: index.md
  title: 样板磁盘
  position: 1010
item_ids:
- ae2_pattern_disk:pattern_disk_1k
- ae2_pattern_disk:pattern_disk_4k
- ae2_pattern_disk:pattern_disk_16k
- ae2_pattern_disk:pattern_disk_64k
- ae2_pattern_disk:pattern_disk_256k
---

# 样板磁盘

样板磁盘可以把编码过的 AE2 样板存进一个物品里。空磁盘没有类型限制，插进第一张样板后就会锁定为那种样板的类型。

各阶磁盘的用法完全一样，只是容量不同：

| 磁盘 | 样板容量 |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

装样板可以用ME样板转存器，也可以在[ME样板磁盘编码终端](pattern_disk_encoding_terminal.md)里把刚编好的样板直接写进去。装满样板的磁盘可以放进ME样板磁盘供应器，供应器会把这些样板提供给 ME 自动合成系统。

## 样板类型

一张磁盘只能装一种类型的样板。合成、处理、锻造和切石这四种样板分别锁定不同的磁盘类型。

## 标记

磁盘上可以记一个**标记**，说明它属于哪台机器、哪一类配方。标记显示在[ME样板磁盘编码终端](pattern_disk_encoding_terminal.md)的磁盘列表里——悬停某张磁盘时会多出一行「标记：…」（开启 F3+H 时还会多一行原始标记 ID）——且不会改动磁盘本身的名字。

标记由[ME样板磁盘编码终端](pattern_disk_encoding_terminal.md)磁盘列表上的两个操作写入：

- **使用工作方块右键**：记下当前的配方类型。从配方查看器导入过配方时，记的是该配方所属的类别（熔炉、烟熏、切石机……各是各的）；手动填网格时就退回记编码模式（合成/处理/锻造/切石）。
- **Shift+右键**：把搜索栏里写的文本记成标记；搜索栏为空则清除该磁盘的标记。这条不依赖当前导入的配方，可以把同一类标记随手标到任意一张盘上。

编码终端会把合成、锻造、切石的模式标记归一到对应的类别名，所以「手动编的切石样板盘」和「导入过切石配方的盘」在列表里叫同一个名字，搜索也搜得到；处理模式没有唯一的类别（不同机器各有各的），手编的处理盘保留「处理样板」这个名字。

标记是编码终端磁盘列表的默认筛选依据——默认只列有标记的盘；它同时决定中键改名时用哪个机器名。

## 兼容性说明

容量越大的磁盘，物品本身附带的数据越多。请把它放在普通箱子这类容器里，用大容量磁盘时注意菜单和网络的同步情况。

## 合成配方

<RecipesFor id="ae2_pattern_disk:pattern_disk_1k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_4k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_16k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_64k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_256k" />
