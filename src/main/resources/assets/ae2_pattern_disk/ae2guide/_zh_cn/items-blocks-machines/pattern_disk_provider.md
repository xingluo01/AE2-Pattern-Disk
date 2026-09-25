---
navigation:
  parent: index.md
  title: ME样板磁盘供应器
  icon: pattern_disk_provider
  position: 1020
item_ids:
- ae2_pattern_disk:pattern_disk_provider
- ae2_pattern_disk:cable_pattern_disk_provider
categories:
- devices
---

# ME样板磁盘供应器

<Row gap="20">
  <BlockImage id="pattern_disk_provider" scale="8" />
  <ItemImage id="cable_pattern_disk_provider" scale="4" />
</Row>

ME样板磁盘供应器用实体样板磁盘替代样板槽。把装有样板的磁盘插入磁盘槽，其中的编码样板即提供给 ME 自动合成系统；其余行为与 <ItemLink id="ae2:pattern_provider" /> 相同，包括向相邻机器推送样板与材料、以及从物品返回栏收回产物。

供应器以磁盘内容为唯一依据，磁盘内容变化后即重新整理其提供的样板。

## 使用方法

用 ME样板转存器或 [ME样板磁盘编码终端](pattern_disk_encoding_terminal.md) 把样板写入磁盘，再把磁盘插入供应器，并将其放在目标机器旁。

## 面板形态

贴着 ME 线缆安装时为面板形态，对应 AE2 样板供应器的扁平变体：磁盘槽、界面、样板逻辑与物品返回栏接口均与方块形态相同；未调整推入方向时只向所贴附的一面推送（AE2 的扁平变体只能单向推送，本模组的面板可切换到全向）。两种形态可通过工作台无序合成互换。

## 推入方向

方块形态对应 AE2 样板供应器的普通与定向变体，用旋转档扳手点击某一面切换；面板形态用旋转档扳手依次切换贴附面（默认）、全向与其余五个面。

- 全向表示六个方向均推送，定向表示只推送指定面。
- 面板形态没有方向箭头模型，切换档位后在动作栏提示当前档位。

## 上传按钮

安装 ExtendedAE Plus 后，其「将样板上传到供应器」的按钮在本供应器上可用。供应器没有独立样板槽，上传的样板直接写入机上磁盘的空位；AE2:Utility 使用同一上传方式，表现相同。

样板写入磁盘时释放的空白样板退回 ME 网络，与从磁盘取出样板时扣除一张空白样板互为对应的记账规则。网络不可用或无法容纳时不写入：本次上传失败，样板留在发起方，空白样板不被消耗。

## 合成配方

<RecipesFor id="ae2_pattern_disk:pattern_disk_provider" />

<RecipeFor id="ae2_pattern_disk:cable_pattern_disk_provider" />
