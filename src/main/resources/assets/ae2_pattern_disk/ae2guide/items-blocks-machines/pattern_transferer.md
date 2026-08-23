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

样板转存器负责在 AE2 空白样板与样板磁盘之间转移编码样板。

输入槽用于放入编码样板或已填充的磁盘。应用槽用于放入目标样板磁盘。当一张编码样板被提取时，产生的空白样板会被返回到所连接的 ME 网络。

一张磁盘一次只接受一种样板类型，并拒绝同属主输出的重复样板。样板转存器支持加速卡以加快转存周期。

## 配方

<RecipeFor id="ae2_pattern_disk:pattern_transferer" />
