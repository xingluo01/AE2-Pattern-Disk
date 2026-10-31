---
navigation:
  parent: index.md
  title: 谐振ME样板磁盘供应器
  icon: resonating_pattern_disk_provider
  position: 1060
item_ids:
- ae2_pattern_disk:resonating_pattern_disk_provider
- ae2_pattern_disk:cable_resonating_pattern_disk_provider
categories:
- devices
---

# 谐振ME样板磁盘供应器

<Row gap="20">
  <BlockImage id="resonating_pattern_disk_provider" scale="8" />
  <ItemImage id="cable_resonating_pattern_disk_provider" scale="4" />
</Row>

基于 AE2 Crystal Science 的[谐振样板供应器](ae2cs:resonating_pattern_provider.md)：
磁盘提供样板，谐振逻辑负责逐输入目标与跨维度发配。

九个磁盘槽，最多 9216 张样板。用 <ItemLink id="ae2cs:resonating_linker" /> 设置设备的 144 槽默认输入目标；
拆下设备、内存卡与面板形态都会保留这些目标。

工具栏保留样板磁盘的抽取按钮：不抽取、抽取、快速抽取。只抽取已装样板声明的输出。
