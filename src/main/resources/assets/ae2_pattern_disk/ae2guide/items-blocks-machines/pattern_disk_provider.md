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

样板磁盘供应器是以实体样板磁盘为后端的样板供应器。将已填充的磁盘插入其磁盘槽位，供应器便会把这些磁盘上的编码样板暴露给 ME 自动合成系统。

供应器是任务来源。它把样板数据与原料推送给相邻的兼容机器，包括高效分子装配室。供应器本身不执行合成。

合成产物可通过供应器的物品返回栏返回。供应器以磁盘为数据可信源，并在磁盘内容变化时重建其可用样板列表。

## 设置

1. 将供应器连接到 ME 网络。
2. 插入一张或多张样板磁盘。
3. 在供应器旁放置高效分子装配室或其他兼容机器。
4. 使用样板转存器将样板编码进磁盘，再插入磁盘。

## 配方

<RecipeFor id="ae2_pattern_disk:pattern_disk_provider" />
