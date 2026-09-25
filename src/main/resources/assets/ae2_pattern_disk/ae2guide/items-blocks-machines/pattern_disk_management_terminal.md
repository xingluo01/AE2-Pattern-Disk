---
navigation:
  parent: index.md
  title: ME Pattern Disk Management Terminal
  icon: pattern_disk_management_terminal
  position: 1036
item_ids:
- ae2_pattern_disk:pattern_disk_management_terminal
categories:
- devices
---

# ME Pattern Disk Management Terminal

<ItemImage id="pattern_disk_management_terminal" scale="4" />

A terminal mounted on a network cable that lists every pattern disk on the network in one table: grouped by the machine holding the disk, one row per disk, with the disk itself in the first cell and its patterns in the next 16, continuing on the rows below. Underneath sits the same encoding area as the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md), so any disk can be re-encoded and written back on the spot.

## The table

| Part | What it shows |
| --- | --- |
| Header row | One machine (containers sharing a name merge into one row): icon, name, disk count `(n)` |
| Disk row | Cell 1 is the disk, cells 2-17 are the first 16 patterns on it (slot order); more continue on the rows below, where all 17 cells are patterns |

- **Search**: the box in the lower left follows the encoding terminal's rule: by disk name, or by mark when prefixed with `#`; with JECH installed names and marks match by pinyin and initials.
- **Sorting**: the two AE2 sort buttons act on the patterns inside each disk. "By name" compares the name shown in the cell (the output's name); "by mod" groups by the output's mod first and then compares names; "by amount" has nothing to compare and keeps the disk's own order. The arrow flips ascending and descending.
- **Additional sort**: while sorting by mod an extra toggle appears, acting on the patterns inside each disk, with the same rule as the toggle of that name in the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md).
- **Scroll**: the table scrolls when it is longer than the view.
- **Empty slots**: the slots a machine has no disk in are listed after that group's disks, one cell per row. The button in the left toolbar folds them into a single row (showing how many slots it stands for) or spreads them out again, one row per slot.
- Patterns that cannot be decoded get a red overlay, as on the encoding terminal.
- **Disk cell tooltip**: hovering a disk cell shows the same information as the encoding terminal's disk list - name, used and total capacity, mark (plus the raw mark ID with advanced tooltips on) - and the gestures that cell accepts.
- The table lists every container on the network that takes pattern disks, including both forms of this mod's provider and machines from other mods, and including machines with no disk inserted, which show their slot count and how many are free; pulling a disk back out does not remove the group. While the search box has text, machines with no matching disk are left out.
- A header shows the icon and name of the machine the container is attached to or points at, the same rule AE2's pattern access terminal uses; a named provider shows that name, and one attached to nothing - or to several different machines at once - shows the container itself. Containers sharing a name merge into one row, so the table prints no coordinates.

> This screen has no network item grid, so **shift-clicking** in your inventory does not send items into the ME network: they neither enter the network nor can be retrieved, and they do not land in the crafting grid or the processing slots either (those are encoding slots, set by clicking them or dragging in from JEI). Shift + left click handles pattern disks only (storing them into the container the selected disk sits in); an already encoded pattern still returns to the pattern editing slot.

## Controls without hints

These controls carry no on-screen hint:

| Input | Effect |
| --- | --- |
| Left click a pattern cell | Takes that pattern onto the cursor |
| Shift + left click a pattern cell | Moves that pattern into your inventory |
| Right click a pattern cell | Puts that pattern into the pattern editing slot for further editing |
| R / U (with the cursor over a pattern cell) | Shows the recipe of that cell's primary output through the recipe viewer (JEI, EMI, REI) |

A pattern cell shows a disk pattern's primary output, so R/U queries the primary output's recipe. Only pattern cells answer R/U: disk cells, empty cells, header rows and content that has not arrived yet do not; a pattern that cannot be decoded is not handed to the recipe viewer, so R/U does nothing on that cell (it already carries a red overlay). The keys a disk cell or an empty slot cell accepts are in their hover tooltips.

Notes:

- Patterns are materialised: taking one out of a disk spends a blank pattern from the ME network, the same accounting the pattern access terminal uses; with none in the network it cannot be taken out. Writing it back returns that blank pattern to the network. A disk itself is an ordinary item and costs nothing to take out.
- When storing a disk, the container's first free slot is used automatically, and an occupied disk cell is never swapped. A successful store announces on the action bar; nothing to store, a full container, a container no longer on the grid, or no disk selected each report the reason in chat.
- Unmarked disks take part in name searches only and never appear in a `#` search; the toggle beside the search box keeps them listed regardless.

## Encoding area

The lower half matches the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md) and writes onto the disk selected with a right click; when that disk cannot accept the pattern, the write moves on to the other disks inserted in the same container (including empty disks, and including disks the search has filtered out). With no disk selected, a search with text writes in table row order. If the pattern editing slot already holds a written pattern, pressing Encode writes it into the selected disk instead of clearing it; with something in the grid, encoding still replaces the pattern sitting in that slot.

## Differences from the encoding terminal

| | ME Pattern Disk Encoding Terminal | ME Pattern Disk Management Terminal |
| --- | --- | --- |
| Disk list | Column on the left | Full-width table on top, grouped by machine |
| Encoding area | Yes | Yes (same) |

## Notes

- The table is a read-only view: it shows disk contents, and changing them takes the controls above or the encoding area. Dragging a pattern into a cell does nothing.
- Contents are shown in full: a disk holding more takes more rows, with the disk in the first cell of its first row and 17 pattern cells on every row below.
- Contents come from the server on demand, so a disk just placed may show as the disk alone for a frame or two.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_management_terminal" />
