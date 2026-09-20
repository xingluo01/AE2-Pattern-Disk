---
navigation:
  parent: index.md
  title: ME Pattern Disk Management Terminal
  position: 1036
item_ids:
- ae2_pattern_disk:pattern_disk_management_terminal
categories:
- devices
---

# ME Pattern Disk Management Terminal

<ItemImage id="ae2_pattern_disk:pattern_disk_management_terminal" />

A cable-attached terminal that lays out **every pattern disk on the network** at once: grouped by the machine holding the disk, one row per disk, with the disk itself in the first cell and its patterns in the next 16 - a disk holding more continues on the rows below. Underneath sits the same encoding area as the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md), so a disk that turns out wrong can be re-encoded and written back on the spot.

## The table

| Part | What it shows |
| --- | --- |
| Header row | One machine: icon, name with coordinates, disk count `(n)`, and a show/hide toggle at the right end |
| Disk row | Cell 1 is the disk, cells 2-17 are the first 16 patterns stored on it (slot order); more continue on the rows below, where all 17 cells are patterns |

- **Search** works like the encoding terminal's: by disk name, or `#`-prefixed for marks.
- **Sorting**: the two AE2 sort buttons in the toolbar act on **the patterns inside each disk** - "by name" compares the name shown in the cell (the output's name), "by mod" groups by the output's mod first and then compares names within the group, and "by amount" has nothing to compare (one pattern is one item), so the disk's own order is kept. The arrow button flips ascending/descending.
- **Additional sort**: while sorting by mod an extra toggle appears (on by default, right below that mode's button). Turning it on turns on two levels at once: names that match once the numbers are removed share a group (`1k Storage Component` and `4k Storage Component` together, `1k Storage Housing` on its own), and within a group the numbers decide the order (`1k < 4k < 16k < 64k < 256k < 1M`, `4 < 16 < 64 < 256 < 1024`, with a trailing k/M/G/T/P/E counted as a power of 1024); turning it off falls back to AE2's original two levels (mod, then literal name), where `16k` comes before `1k` again. It stays hidden in the other sort modes. The same toggle also governs the encoding terminal's item grid when it sorts by mod.
- **Scroll**: the scrollbar on the right (it lights up once the table is longer than the view) - drag the handle, use the mouse wheel, or click the track above/below the handle to page; the wheel works anywhere on the screen.
- **Empty slots**: the slots the machine has no disk in are listed after that machine's disks, one cell per row. The "hide slots / show slots" button in the left toolbar folds them into a single row (with the folded count written in its top-right corner) or spreads them back out, one row per slot.
- Patterns that cannot be decoded get a red overlay, same as the encoding terminal.
- **Disk tooltip**: hovering a disk cell shows the same information the encoding terminal's disk list shows - name, used/capacity, mark (plus the raw mark id with vanilla advanced tooltips on), and the cell's four gestures.
- The table lists **every container on the current network that takes pattern disks** (both forms of this mod's provider, plus other machines registered through the API) - including ones with no disk inserted: how many it takes and how many slots are still free are visible, and pulling a disk back out does not make the group disappear. While the search box has text, machines with no matching disk are left out (otherwise a search would fill the table with empty machines).

> This screen has no network item grid, so **shift-clicking in your inventory does not send items into the ME network** (they would leave your sight with no way to get them back); quick-moving between the terminal's own slots is unaffected.

## Controls

Everything acts on the cell under the cursor:

| Input | Effect |
| --- | --- |
| Left click a disk | Takes the disk onto the cursor (whatever is stored on it goes along) |
| Shift + left click a disk | Moves the disk into your inventory |
| Right click a disk | Selects it; right click again to deselect. The selected disk is where "encode" writes (its first cell gets a highlight) |
| Right click a disk (holding a work block) | Overwrites the mark with that block's recipe type |
| Shift + right click a disk | Overwrites the mark with the search box contents (empty clears it) |
| Middle click a disk | Renames it after the work block its mark names |
| Left click a pattern | Takes it onto the cursor |
| Shift + left click a pattern | Moves it into your inventory |
| Right click a pattern | Puts it into the pattern editing slot (i.e. "keep editing this one") |
| Left click a "free slots" cell (holding a pattern disk) | Puts that disk into a free disk slot on that container; the table and its contents refresh right away |
| Shift + left click a pattern disk in your inventory | Stores it into **the container the selected disk sits in** (right-click a disk to select it first; without a selection the chat says so) |

> Which slot it lands in is decided by the server (the first free one) - the client's list can be a frame behind, so letting it name a slot would be the easier way to write to the wrong place. An occupied disk cell keeps its existing gesture (left click takes it), no swapping; "nothing to store", "no free slot", "that container is gone" and "no disk selected yet" each report in chat; a successful store only announces on the action bar, so quick repeated clicks do not spam chat.

> Patterns are materialised: taking one out of a disk spends **one blank pattern** from the ME network (the same accounting the pattern access terminal uses when you pull a row out), and a disk holding nothing to spare simply refuses; writing a pattern back to a disk returns that blank pattern to the network. A disk itself is just an item, so taking one out costs nothing.
> While you are holding a work block, right clicking marks rather than selects - put the block down (empty-handed, or holding anything else) to select a disk.
> Disks without a mark take part in name searches only; a `#` mark search has nothing to match for them, and the toggle next to the search box keeps them in regardless. With an empty search box nothing is filtered.

## Encoding area

The lower half is the pattern disk encoding terminal's: mode cycling (crafting / processing / smithing / stonecutting), the filter slots, substitution, output multipliers and the rest, encoding into **the disk you selected with a right click**. With nothing selected, a search that narrows the list down to a single disk writes straight into it. If the pattern editing slot already holds a written pattern, clicking "encode" writes that one into the selected disk instead of clearing it - although with something in the grid, encoding still replaces the pattern sitting in that slot (same as AE2's pattern encoding terminal).

## How it differs from the encoding terminal

| | ME Pattern Disk Encoding Terminal | ME Pattern Disk Management Terminal |
| --- | --- | --- |
| Disk list | 24×66 column on the left | Full-width table on top, 17 cells per row, grouped by machine |
| Panel width | 195 | 340 |
| Encoding area | Yes | Yes (identical) |

## Notes

- The table is a **read-only view**: it shows what is on the disks, but to change them use the controls above or the encoding area. Dragging a pattern into a cell does nothing.
- Contents are shown in full: a disk holding more simply takes more rows (the first row starts with the disk, every row below is 17 pattern cells).
- Contents arrive from the server on demand, so a disk that was just placed may show as just the disk for a frame or two.
