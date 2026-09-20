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

A cable-attached terminal that lays out **every pattern disk on the network** at once: grouped by the machine holding the disk, one row per disk, with the disk itself in the first cell and its patterns in the next 16. Underneath sits the same encoding area as the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md), so a disk that turns out wrong can be re-encoded and written back on the spot.

## The table

| Part | What it shows |
| --- | --- |
| Header row | One machine: icon, name with coordinates, disk count `(n)`, and a show/hide toggle at the right end |
| Disk row | Cell 1 is the disk, cells 2-17 are the patterns stored on it (slot order, up to 16 of them) |

- **Search** works like the encoding terminal's: by disk name, or `#`-prefixed for marks.
- **Scroll** with the mouse wheel. A disk holding more than 16 patterns shows the first 16 in its row (it is still one row per disk).
- Patterns that cannot be decoded get a red overlay, same as the encoding terminal.
- The table only lists disks on **the current network**; a broken or removed machine takes its group with it.

## Controls

Identical to the encoding terminal, and always acting on the disk under the cursor:

| Input | Effect |
| --- | --- |
| Left click a disk | Makes it this terminal's encoding target; the encode button writes into it |
| Right click a disk (holding a work block) | Overwrites the mark with that block's recipe type |
| Shift + right click a disk | Overwrites the mark with the search box contents (empty clears it) |
| Middle click a disk | Renames it after the work block its mark names |

> Disks without a mark take part in name searches only; a `#` mark search has nothing to match for them, and the toggle next to the search box keeps them in regardless. With an empty search box nothing is filtered.

## Encoding area

The lower half is the pattern disk encoding terminal's: mode cycling (crafting / processing / smithing / stonecutting), the filter slots, substitution, output multipliers and the rest, encoding into the selected disk. When a search narrows the list down to a single disk, "encode" writes straight into it.

## How it differs from the encoding terminal

| | ME Pattern Disk Encoding Terminal | ME Pattern Disk Management Terminal |
| --- | --- | --- |
| Disk list | 24×66 column on the left | Full-width table on top, 17 cells per row, grouped by machine |
| Panel width | 195 | 340 |
| Encoding area | Yes | Yes (identical) |

## Notes

- The table is a **read-only view**: it shows what is on the disks, but to change them use the controls above or the encoding area. Dragging a pattern into a cell does nothing.
- One row per disk, so a 1024-capacity disk does not stretch the table - its contents are truncated to the 16 cells of its row.
- Contents arrive from the server on demand, so a disk that was just placed may show as just the disk for a frame or two.
