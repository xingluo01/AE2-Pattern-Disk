---
navigation:
  parent: index.md
  title: 样板转存器
  position: 30
item_ids:
- ae2_pattern_disk:pattern_transferer
categories:
- machines
---

# 样板转存器

样板转存器负责把编码样板在 AE2 空白样板和样板磁盘之间互相转移。

输入端放编码样板或已经装满样板的磁盘；目标槽放你要写入的样板磁盘。从磁盘取出样板后，腾出的空白样板会被送回连接的 ME 网络。

一张磁盘一次只接受一种样板类型，并且会拒绝和当前主输出重复的样板。样板转存器支持加速卡，插上后能加快转存的速度。

## 合成配方

<RecipeFor id="ae2_pattern_disk:pattern_transferer" />