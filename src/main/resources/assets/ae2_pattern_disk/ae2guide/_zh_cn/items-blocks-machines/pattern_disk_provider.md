---
navigation:
  parent: index.md
  title: ME样板磁盘供应器
  position: 1020
item_ids:
- ae2_pattern_disk:pattern_disk_provider
- ae2_pattern_disk:cable_pattern_disk_provider
categories:
- devices
---

# ME样板磁盘供应器

ME样板磁盘供应器是一款直接使用实体样板磁盘的样板供应器。把装满样板的磁盘插进它的磁盘槽里，它就会把磁盘里的编码样板提供给 ME 自动合成系统。

它只负责送任务，不负责合成。它会向旁边兼容的机器推送样板和材料，比如高效分子装配室。

做好的成品可以送回供应器的物品返回栏。供应器以磁盘里的样板为唯一依据，磁盘内容一变，它就会重新整理可以提供的样板。

## 使用方法

1. 把供应器连上 ME 网络。
2. 插入一张或多张样板磁盘。
3. 在旁边放一台高效分子装配室或其它兼容机器。
4. 用ME样板转存器或[ME样板磁盘编码终端](pattern_disk_encoding_terminal.md)把样板写进磁盘，再把磁盘插进去。

## 面板形态

贴着 ME 线缆安装时，它是面板形态：磁盘槽、界面、样板逻辑和返还栏接口都与方块形态相同，区别是它只向所贴的那一面推送样板，更适合紧凑布线。

两种形态可以互相转换：在工作台里无序合成，一个换一个，不额外消耗材料。

## 上传按钮（ExtendedAE Plus / AE2:Utility）

装有 ExtendedAE Plus 时，它那个「把样板上传到供应器」的按钮在本供应器上也能用。供应器没有属于自己的样板槽位，上传的样板会直接写进机上磁盘的空位里；走同一个上传 API 的 **AE2:Utility** 同理（它的自动上传最后也是调 EAE+ 的写入）。

样板进盘时，那张被释放出来的空白样板会退回 ME 网络——这是本模组一贯的账（反过来，从盘里把样板取出来要扣一张）。网络不在或塞不下时干脆不写：宁可让这次上传失败（样板还在发起方手里），也不吞掉那张空白样板。

## 合成配方

<RecipesFor id="ae2_pattern_disk:pattern_disk_provider" />

<RecipeFor id="ae2_pattern_disk:cable_pattern_disk_provider" />
