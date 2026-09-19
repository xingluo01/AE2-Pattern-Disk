---
navigation:
  parent: index.md
  title: Pattern Disks
  position: 1010
item_ids:
- ae2_pattern_disk:pattern_disk_1k
- ae2_pattern_disk:pattern_disk_4k
- ae2_pattern_disk:pattern_disk_16k
- ae2_pattern_disk:pattern_disk_64k
- ae2_pattern_disk:pattern_disk_256k
---

# Pattern Disks

A pattern disk stores encoded AE2 patterns inside a single item. An empty disk takes any type; the first pattern written to it locks the disk to that pattern's type.

Every tier works the same way, only the capacity differs:

| Disk | Patterns |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

Use the ME Pattern Transferer to write patterns into a disk, or write the pattern you just encoded straight onto one from the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md). A full disk goes into an ME Pattern Disk Provider, which offers those patterns to ME autocrafting.

## Pattern types

A disk holds one pattern type only. Crafting, processing, smithing and stonecutting patterns each lock the disk to a different type.

## Marks

A disk can carry a **mark** recording which machine or recipe type it belongs to. The mark shows up in the disk list of the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md) — hovering a disk there adds its own "Mark: ..." line, plus a raw mark ID line with F3+H enabled — and it never changes the disk's name.

Two interactions write a mark, both on the disk list of the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md):

- **Right click with a work block**: records the current recipe type. If you imported a recipe from the recipe viewer, that is the recipe's category (furnace, smoking, stonecutting and so on, each kept apart); encoding by hand instead falls back to the encoding mode (crafting / processing / smithing / stonecutting).
- **Shift + right click**: records the search bar's text as the mark, and clears the mark when the search bar is empty. This one does not depend on an imported recipe, so you can stamp the same mark onto any disk you like.

The encoding terminal folds crafting, smithing and stonecutting mode marks onto their matching category name, so a disk encoded by hand and one written from an imported stonecutting recipe show the same name in the list and answer the same search. Processing has no single category — each machine has its own — so a hand-encoded processing disk keeps the name "Processing pattern".

The mark is what the encoding terminal's disk list filters on by default — only marked disks are listed — and it decides which machine name a middle-click rename uses.

## Compatibility

The larger the disk, the more data the item itself carries. Keep them in ordinary containers such as chests, and mind menu and network synchronisation when working with the larger tiers.

## Recipes

<RecipesFor id="ae2_pattern_disk:pattern_disk_1k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_4k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_16k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_64k" />
<RecipeFor id="ae2_pattern_disk:pattern_disk_256k" />
