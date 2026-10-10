# AE2 Pattern Disk

An addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds high-capacity pattern disks, a disk-backed pattern provider, a self-assembling provider, a pattern transferer, a pattern disk encoding terminal, a pattern disk management terminal, a cell management terminal, and two stronger molecular assemblers.

## Features

### Pattern Disks

Store encoded patterns on a disk instead of leaving them in a provider's slots, in five tiers:

| Disk | Patterns |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

A disk is untyped while empty — the first pattern you insert fixes its pattern type, and it accepts only that type afterwards.

A disk can also carry a **mark**, recording which machine or recipe type it belongs to. Marks show up in the encoding terminal's disk list, never rename the disk itself, and the list filters on them with `#`.

### ME Pattern Disk Provider

A pattern provider backed by physical pattern disks: insert disks into its slots and it exposes their encoded patterns to the ME autocrafting system. It is a task source — it does not craft anything itself — and accepts returned products through its return inventory.

It comes in two forms: the in-world **block**, and a **cable panel part** that attaches to a cable like AE2's own cable pattern provider. Both keep the same nine disk slots and the same return inventory.

### Meteorite Pattern Disk Provider

A pattern provider that also does some of the work itself: besides exposing the patterns on the disks it holds, any pattern a molecular assembler could run is crafted on the spot and its products are sent straight back to the network. Everything else is pushed to adjacent machines like an ordinary pattern provider.

It also comes as a block and as a cable panel part, and the two forms convert into each other. This machine only exists with **AE2 Crystal Science** installed.

### ME Pattern Transferer

Moves encoded patterns between AE2 blank patterns and pattern disks. Input slots accept encoded patterns or populated disks; destination slots hold the target disks. Blank patterns produced by extraction are returned to the connected ME network. Supports speed cards.

### ME Pattern Disk Encoding Terminal

A panel that mounts on an ME cable and ties pattern encoding to pattern disks. It scans the whole ME network for pattern disks; pick one and you can write the pattern you just encoded onto it, or review and tidy the patterns it already holds.

Crafting, processing, smithing and stonecutting encoding modes, cycled with the mode button, and blank patterns come straight from the ME network instead of the slot. A **wireless** version offers a portable version of the same screen.

### ME Pattern Disk Management Terminal

The second panel for the same cable, built for looking after disks rather than making patterns. It lists **every pattern disk on the network, grouped by the machine holding it**, and lets you move disks and their patterns in and out, search the list, sort it, and fold a machine's free slots into a single cell.

It has a **wireless** version too, with the same screen.

### ME Cell Management Terminal

A terminal for the network's storage cells: it lays them out in a table grouped by drive, with the rows in each drive's storage-priority order, and lets you take cells in and out of place and edit a cell's partitions.

It likewise has a **wireless** version, opening the same table.

### Efficient Molecular Assembler

A parallel molecular assembler with **eight independent execution threads**. It accepts crafting jobs pushed by AE2 pattern providers and runs them concurrently. Each thread owns a 3×3 molecular assembler grid, an output slot, and independent progress. Accepts up to **five AE2 Speed Cards**.

Each page also has an optional pattern slot: inserting an encoded crafting pattern there turns that page into a self-executing unit, pulling its own inputs from the ME network and pushing products to adjacent inventories or back into the network.

### Batch Assembler

Buffers the jobs pushed by AE2 crafting CPUs inside nine private storage cell slots and executes them once the input material has actually stopped arriving, then returns the products to the network. Inputs roll back only when the cells cannot hold a push, when material is missing, or when the grid is out of power.

Supports crafting-table, smithing-table and stonecutting recipes, and the pattern priority it uses as a provider can be set from the screen.

### Chat Messages

The 52 messages this mod sends to chat can be switched off one by one in its configuration files — one for the client and one for the server.

## Recipes and items

Everything this mod adds sits in the creative tab **AE2 Pattern Disk**, and each recipe shows up in JEI or EMI like any other mod's.

## In-game guide

A guide ships with the mod, reachable from the AE2 guide book, covering the machines above and every pattern disk tier. It is available in English and Simplified Chinese. Most machine screens link straight to their guide page.

## License

Licensed under **LGPL-3.0**, except for assets marked ARR — currently the batch assembler's block textures and its glow-shell model, which are the author's own works and are **not** covered by that grant. Source, issues and the full license text: [github.com/xingluo01/AE2-Pattern-Disk](https://github.com/xingluo01/AE2-Pattern-Disk).
