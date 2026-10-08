---
navigation:
  parent: index.md
  title: 自装配样板磁盘供应器
  icon: meteorite_pattern_provider
  position: 1025
item_ids:
- ae2_pattern_disk:meteorite_pattern_provider
- ae2_pattern_disk:cable_meteorite_pattern_provider
categories:
- devices
---

# 自装配样板磁盘供应器

<Row gap="20">
  <BlockImage id="meteorite_pattern_provider" scale="8" />
  <ItemImage id="cable_meteorite_pattern_provider" scale="4" />
</Row>

一种还会自己动手的 [样板磁盘供应器](pattern_disk_provider.md)：除了提供磁盘里的样板，凡是分子装配室能做的样板，它都当场做完，产物直接送回 ME 网络。

两种形态都只在装了 **AE2 Crystal Science** 时存在，没装时游戏里没有这台设备。

## 收到样板推送时

- **分子装配室能做的样板**：扣材料、当场出产物并送回网络，不向相邻机器推送。
- **其余样板**（处理样板，以及装配室做不了的）：按普通样板供应器推送。
- **锁定优先**：红石锁定或「直到结果」期间不做自完成，锁的优先级更高。

## 能量与速度

一次自完成消耗 **50 AE** 网络电量，每张加速卡翻一倍。

- **加速卡**：最多 4 张，每张让单次能耗翻倍，并抬高每轮上限（8 → 16 → 32 → 64 → 128）。一轮就是一次回送周期，未装超频卡时约 5 tick 一轮。
- **<ItemLink id="ae2cs:overload_card" fallback="陨石超频卡" />**：最多 4 张。装了任意数量之后加速卡不再起作用，每张让每轮上限增加 **128**，并加快回送节奏——1 张每 4 tick 回送一次，2 张及以上每 tick 一次。

两种卡共用机上那四个升级槽。

## 形态、方向与上传

磁盘、方向处理与上传按钮都与 [ME样板磁盘供应器](pattern_disk_provider.md) 一致：用旋转档扳手切换推入方向，方块与面板两形态可用工作台无序合成互换，上传的样板落在机上磁盘的空位。

## 合成配方

<RecipesFor id="ae2_pattern_disk:meteorite_pattern_provider" />

<RecipeFor id="ae2_pattern_disk:cable_meteorite_pattern_provider" />
