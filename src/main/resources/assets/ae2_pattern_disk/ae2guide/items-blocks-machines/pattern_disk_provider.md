---
navigation:
  parent: index.md
  title: 样板磁盘供应器
  position: 20
item_ids:
- ae2_pattern_disk:pattern_disk_provider
categories:
- devices
---

# 样板磁盘供应器

样板磁盘供应器是一款直接使用实体样板磁盘的样板供应器。把装满样板的磁盘插进它的磁盘槽里，它就会把磁盘里的编码样板提供给 ME 自动合成系统。

它只负责送任务，不负责合成。它会向旁边兼容的机器推送样板和材料，比如高效分子装配室。

做好的成品可以送回供应器的物品返回栏。供应器以磁盘里的样板为唯一依据，磁盘内容一变，它就会重新整理可以提供的样板。

## 使用方法

1. 把供应器连上 ME 网络。
2. 插入一张或多张样板磁盘。
3. 在旁边放一台高效分子装配室或其它兼容机器。
4. 先用样板转存器把样板写进磁盘，再把磁盘插进去。

## 合成配方

<RecipeFor id="ae2_pattern_disk:pattern_disk_provider" />
