---
navigation:
  parent: index.md
  title: ME Pattern Disk Encoding Terminal
  position: 1035
item_ids:
- ae2_pattern_disk:pattern_disk_encoding_terminal
categories:
- devices
---

# ME Pattern Disk Encoding Terminal

The ME Pattern Disk Encoding Terminal is a panel mounted on an ME cable that ties pattern encoding to pattern disks. It scans the whole ME network for pattern disks; pick one and you can write the pattern you just encoded onto it, or review and tidy the patterns it already holds.

## Encoding modes

The terminal has four encoding views — crafting, processing, smithing and stonecutting — cycled with the mode button. Switching changes the recipe grid, the output area and the tools on offer.

- **Crafting**: 3×3 crafting grid plus output, with substitution and fluid-substitution toggles.
- **Processing**: multi-input, multi-output processing recipes, with secondary-output rotation and multiply/divide.
- **Smithing**: the standard template, base and addition layout.
- **Stonecutting**: a single input, with twelve candidates visible at once over three rows (four columns by three rows, no gap between rows); a scrollbar appears when there are more.

## Blank patterns

Encoding no longer needs a blank pattern in the slot. The blank pattern slot is read-only and shows how many blank patterns the ME network holds; pressing Encode takes one straight from the network. When the network has none the terminal says so rather than doing nothing.

## Processing tools

Tools unique to processing mode, for tuning the recipe multiplier of large automated jobs:

- **Rotate primary output**: swaps the primary output for the first secondary one, cycling and tidying the output slots as it goes.
- **Multiply / divide**: scales the whole recipe's inputs and outputs. Click for ×2 (÷2), Shift-click for ×3 (÷3), Ctrl-click for ×5 (÷5), Alt-click for ×10 (÷10). Divide checks that both inputs and outputs divide evenly, and does nothing at all when they do not.
- **Merge same items**: merges input slots holding the same item, so a recipe takes up fewer slots.

## Disk list

The lower part of the interface lists the network's pattern disks. By default **only disks carrying a mark are listed** — a mark records which kind of recipe a disk belongs to (see [Pattern Disks](pattern_disks.md)). A disk with no mark stays out of the default list whether or not it holds patterns — including a disk you have only ever written to and never bound a mark on. The toggle next to the search bar brings those disks out.

### Search

The search bar filters in two ways:

- **Plain text**: matches the disk's name.
- **Text starting with `#`**: matches the disk's mark. A mark records which machine the disk belongs to: binding it with a right-click takes the imported recipe's category when you have just imported one, and the current encoding mode otherwise. Crafting, smithing and stonecutting marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind carry the same name and answer the same search. Processing has no single category — each machine has its own — so a hand-encoded processing disk keeps the name "Processing pattern"; search for "processing" to find it.

### Showing unmarked disks

The toggle to the right of the search bar decides whether disks without a mark appear:

- **Normal** (off): only marked disks are listed; unmarked ones are left out.
- **Force show** (on): unmarked disks are listed as well and are exempt from `#` mark search, having no mark to match. Marked disks are still filtered by mark as usual.

### Mouse controls

Each button does something different on a disk:

- **Left click**: write the currently encoded pattern onto that disk.
- **Right click with a work block**: overwrite the disk's mark with the current recipe type. This changes the mark, not the name.
- **Shift + right click**: write the search bar's text onto the disk as its mark; an empty search bar clears the disk's mark instead.
- **Middle click with a work block**: rename the disk after the machine its mark stands for.

### Write results

Every write reports back in chat. Success names the disk the pattern went to; failure gives the reason — the disk is full, it is locked to another pattern type, it already holds a recipe with the same output, this pattern's type cannot be resolved, or the target disk is no longer in the list.

### Writing as you encode

When the search bar narrows the list to exactly one disk, pressing Encode writes the pattern straight onto it, saving the "encode, then click the disk" round trip. With more than one disk, or none, the pattern stays in the encoded slot and chat reports how many disks the list currently holds.

## Uploading to NEO ECO

With NEO ECO AE Extension installed, an upload button appears in the top right of the interface. It sends the pattern in the encoded slot to NEO ECO's computation cluster pattern storage, clears the slot on success, and returns a blank pattern in the order network → inventory → encoded slot. Without that mod the button does not appear.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_encoding_terminal" />
