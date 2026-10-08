---
navigation:
  parent: index.md
  title: ME Cell Management Terminal
  icon: cell_management_terminal
  position: 1037
item_ids:
- ae2_pattern_disk:cell_management_terminal
- ae2_pattern_disk:wireless_cell_management_terminal
categories:
- devices
---

# ME Cell Management Terminal

<Row gap="20">
  <ItemImage id="cell_management_terminal" scale="4" />
  <ItemImage id="wireless_cell_management_terminal" scale="4" />
</Row>

A terminal that puts every storage cell in the network on one table: each row starts with a drive (or an ECO
host) and the cells inside it follow on the same row.

## The table

- Rows are ordered by **priority**; the sort-direction button flips that order.
- **Shift + left click** a cell in your inventory to store it into the drive selected in the table; **left
  click** a cell on the table picks it up onto the cursor as usual. **Right click** a cell inside a drive
  moves it to the encode slot, or - when another drive is selected - moves it onto that drive instead.
- **Left click** a drive pins it: a rainbow box and a line to it are drawn in the world, and both stay after
  the terminal is closed. Left click it again to unpin.
- **Right click** a drive selects it - the target for storing a cell and for moving one over. The selection
  is the yellow outline in the table, and it is forgotten when the screen closes.
- **Middle click** a drive opens its priority screen.

## The marker area

The marker area edits the partitions of **the cell in the encode slot** - take a cell out of a drive, set it
up here, put it back. Drag items in from JEI or EMI, or move through the partitions with the marker
scrollbar.

## Cell upgrades

The six upgrade slots belong to that same cell: the cards go with the cell, not with the terminal.

## Buttons

Open guide, sort direction, terminal style, partition storage, clear, copy mode - the last three are the ones
AE2's cell workbench has.

## Wireless form

The wireless terminal opens the same table and follows the item, like the network's other wireless terminals:
its own upgrade cards, a terminal switch button inside a universal terminal, and a hotkey.

## Recipes

<RecipesFor id="ae2_pattern_disk:cell_management_terminal" />

<RecipeFor id="ae2_pattern_disk:wireless_cell_management_terminal" />
