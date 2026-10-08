---
navigation:
  parent: index.md
  title: ME元件管理终端
  icon: cell_management_terminal
  position: 1037
item_ids:
- ae2_pattern_disk:cell_management_terminal
- ae2_pattern_disk:wireless_cell_management_terminal
categories:
- devices
---

# ME元件管理终端

<Row gap="20">
  <ItemImage id="cell_management_terminal" scale="4" />
  <ItemImage id="wireless_cell_management_terminal" scale="4" />
</Row>

把网络里的存储元件集中到一张表上的终端：每一行以一台驱动器（或 ECO 主机）开头，它里面的元件跟在同一行。

## 表格

- 行按**优先级**排列，「排序顺序」按钮可把顺序反过来。
- **Shift + 左键**背包里的元件，把它存进表格里选中的那台驱动器；**左键**表格上的元件则与平时一样取到光标上。**右键**驱动器里的元件会把它移进元件编码槽；已经选中了另一台驱动器时，则改为移进那一台。
- **左键**一台驱动器即钉住它：世界里画出虹色方框与一条连线，关掉终端也还在；再左键一次取消。
- **右键**一台驱动器即选中它——存元件、把元件移过去时的目标。选中的提示是表格里那圈黄色描边，关屏即忘。
- **中键**一台驱动器打开它的优先级界面。

## 标记区

标记区改的是**编码槽里那张元件**的分区：把元件从驱动器取出、在这里配好、再放回去。可以从 JEI 或 EMI 里拖物品进来，也可以用标记区滚动条翻页。

## 元件升级槽

那六格升级槽同样属于这张元件：卡跟着元件走，不跟着终端走。

## 按钮

打开指南、排序顺序、终端风格、分区存储、清除、复制模式——后三个与 AE2 元件工作台上的那三个相同。

## 无线形态

无线版打开同一张表，并且与网络里另外两个无线终端一样跟着物品走：自带升级卡槽、装在通用终端里时有终端切换按钮，也有打开它的热键。

## 合成配方

<RecipesFor id="ae2_pattern_disk:cell_management_terminal" />

<RecipeFor id="ae2_pattern_disk:wireless_cell_management_terminal" />
