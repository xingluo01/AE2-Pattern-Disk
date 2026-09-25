---
navigation:
  parent: index.md
  title: Pattern Disks
  icon: pattern_disk_1k
  position: 1010
item_ids:
- ae2_pattern_disk:pattern_disk_1k
- ae2_pattern_disk:pattern_disk_4k
- ae2_pattern_disk:pattern_disk_16k
- ae2_pattern_disk:pattern_disk_64k
- ae2_pattern_disk:pattern_disk_256k
---

# Pattern Disks

<Row gap="20">
  <ItemImage id="pattern_disk_1k" scale="4" />
  <ItemImage id="pattern_disk_4k" scale="4" />
  <ItemImage id="pattern_disk_16k" scale="4" />
  <ItemImage id="pattern_disk_64k" scale="4" />
  <ItemImage id="pattern_disk_256k" scale="4" />
</Row>

A pattern disk stores encoded AE2 patterns in a single item. An empty disk accepts any pattern type; writing the first pattern locks the disk to that type, and only patterns of that type are accepted afterwards.

All five tiers work the same way; only the capacity differs:

| Disk | Patterns |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

Patterns can be written with the [ME Pattern Transferer](pattern_transferer.md), or straight from the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md). A full disk goes into the [ME Pattern Disk Provider](pattern_disk_provider.md), where its patterns are offered to ME autocrafting.

## Pattern types

A disk accepts one pattern type only. Crafting, processing, smithing and stonecutting patterns each lock the disk to a matching type; with AdvancedAE installed, its advanced processing pattern locks the disk to a type of its own as well.

The table below shows every combination of type and capacity: rows are pattern types and where they come from, columns are disk capacities. The base layer is the same for every tier; the capacity layer changes with the capacity, and the corner mark changes with the type, so the mark tells you which kind of pattern a disk holds.

| Type | From | 1k | 4k | 16k | 64k | 256k |
| --- | --- | :-: | :-: | :-: | :-: | :-: |
| Untyped (empty) | — | ![](/assets/pattern_disk_types/pattern_disk_untyped_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_untyped_256k.png) |
| Crafting | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_crafting_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_crafting_256k.png) |
| Processing | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_processing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_processing_256k.png) |
| Smithing | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_smithing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_smithing_256k.png) |
| Stonecutting | Applied Energistics 2 | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_stonecutting_256k.png) |
| Advanced Processing | AdvancedAE | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_1k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_4k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_16k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_64k.png) | ![](/assets/pattern_disk_types/pattern_disk_adv_processing_256k.png) |

## Marks

A mark records which machine or recipe category a disk belongs to. It shows up in the disk list of the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md): hovering a disk adds a "Mark: ..." line, and with F3+H another line with the raw mark ID. Writing a mark never changes the disk's name.

Marks are written from that disk list, in two ways:

- **Right click while holding a work block**: records the recipe category of the work block on the cursor, such as crafting table, stonecutter, smithing table, furnace or smoker, and does not depend on the imported recipe.
  - Without such a work block on the cursor, it records the imported recipe's category instead; with neither, no mark is written.
  - The recipe viewer's machine table decides this (EMI uses its registered workstations, JEI only catalysts); a block it cannot identify is reported on screen, and that right click writes nothing.
- **Shift + right click**: records the search bar's text as the mark; an empty search bar clears it. This one does not depend on the imported recipe, so the same mark can be put on any disk.

The encoding terminal folds crafting, smithing and stonecutting marks onto their category name, so a hand-encoded stonecutting disk and one written from an imported stonecutting recipe show the same name and answer the same search. Processing has no single category, so a hand-encoded processing disk keeps the name "Processing pattern".

The mark is also what the `#` search in the encoding terminal's disk list filters on, and it decides which work block name a middle-click rename uses.

## Compatibility

The larger the disk, the more data the item carries. Keep disks in ordinary containers such as chests, and avoid leaving a large disk open in an item interface for long.

## Recipes

<RecipesFor id="ae2_pattern_disk:pattern_disk_1k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_4k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_16k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_64k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_256k" />
