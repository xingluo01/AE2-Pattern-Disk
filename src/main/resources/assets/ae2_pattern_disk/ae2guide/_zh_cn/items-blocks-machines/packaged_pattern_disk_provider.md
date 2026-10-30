---
navigation:
  parent: index.md
  title: 封包样板磁盘供应器
  icon: packaged_pattern_disk_provider
  position: 1050
item_ids:
- ae2_pattern_disk:packaged_pattern_disk_provider
- ae2_pattern_disk:wireless_packaged_pattern_disk_provider
categories:
- devices
---

# 封包样板磁盘供应器

<Row gap="20">
  <BlockImage id="packaged_pattern_disk_provider" scale="8" />
  <BlockImage id="wireless_packaged_pattern_disk_provider" scale="8" />
</Row>

一台用样板磁盘当样板来源的<ItemLink id="ae2ltpp:packaged_pattern_provider" />。有有线、无线两种形态，都带九个磁盘槽，保留封包供应器把合成派发到其他模组加工系统的能力。

用对应的封包样板供应器加一张 16K 样板磁盘合成。

## 适配卡

封包供应器靠适配卡选择目标机器族。这台设备保留了那个槽位，位置在返还库存右侧。没插适配卡就什么都不派发，与原版一致。

## 样板

样板是普通的 AE2 处理样板，封包逻辑负责把它变成目标机器的流程。锁成 `ae2:processing_pattern` 的磁盘都能用；过载样板不行，因为封包宿主不是过载供应器。

磁盘内容会被镜像进供应器的样板栏，磁盘一变就重建。拆机掉落的是磁盘，不是样板的拷贝。

## 工具栏

- **频率绑定**：与原版同一个绑定界面。
- **自动返回**：关闭或自动。
- **模式**：普通或无线，形态允许切换时才生效。

无线版只差图标与标题：无线派发、连接上限与频率绑定都来自原版无线供应器的逻辑。