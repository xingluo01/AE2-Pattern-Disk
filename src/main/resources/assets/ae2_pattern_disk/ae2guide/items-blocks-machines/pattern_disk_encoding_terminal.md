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

The lower part of the interface lists the network's pattern disks. **With nothing in the search bar every disk is listed** (unmarked ones included); while you type, the list is filtered by what you typed - by name (unmarked disks have names too and take part like any other), or by mark when prefixed with `#` (unmarked disks have no mark to match). The toggle next to the search bar keeps unmarked disks in regardless of the search. A mark records which kind of recipe a disk belongs to (see [Pattern Disks](pattern_disks.md)).

When this terminal's **item grid** sorts by mod, an extra "additional sort" toggle shows up (on by default). Turning it on turns on two levels at once: names that match once the numbers are removed share a group (`1k Storage Component` and `4k Storage Component` together, `1k Storage Housing` on its own), and within a group the numbers decide the order (`1k < 4k < 16k < 64k < 256k < 1M`, `4 < 16 < 64 < 256 < 1024`, with a trailing k/M/G/T/P/E counted as a power of 1024). Turning it off falls back to AE2's original two levels (mod, then literal name), where `16k` comes before `1k` again. It stays hidden in the other two sort modes, which do not have that problem. The same toggle drives the patterns inside each disk on the management terminal.

### Search

The search bar filters in two ways:

- **Plain text**: matches the disk's name.
- **Text starting with `#`**: matches the disk's mark. A mark records which work block the disk belongs to: a right-click binds the recipe category of the work block on your cursor, and only without such a work block does it fall back to the imported recipe's category; with neither, that right click writes nothing. Crafting, smithing and stonecutting marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind carry the same name and answer the same search. Processing has no single category — each machine has its own — so a hand-encoded processing disk keeps the name "Processing pattern"; search for "processing" to find it.

### Showing unmarked disks

The toggle to the right of the search bar decides whether disks without a mark appear:

- **Normal** (off): filtered by what you typed. A name search compares names only, and unmarked disks take part like any other; a `#` mark search finds no match for them, so they stay out.
- **Force show** (on): unmarked disks ignore the search filter and stay listed. Marked disks are still filtered by the search as usual.

### Mouse controls

Each button does something different on a disk:

- **Left click**: write the currently encoded pattern onto that disk.
- **Right click with a work block picked up on the cursor**: overwrite the disk's mark with the recipe category that work block runs; without such a work block on the cursor it falls back to the imported recipe's category; with neither, nothing is written. This changes the mark, not the name.
- **Shift + right click**: write the search bar's text onto the disk as its mark; an empty search bar clears the disk's mark instead.
- **Middle click**: rename the disk after the work block its mark stands for.

### Write results

Every write reports back in chat. Success names the disk the pattern went to; failure gives the reason — the disk is full, it is locked to another pattern type, it already holds a recipe with the same output, this pattern's type cannot be resolved, or the target disk is no longer in the list.

### Writing as you encode

When the search bar narrows the list to exactly one disk, pressing Encode writes the pattern straight onto it, saving the "encode, then click the disk" round trip. With more than one disk, or none, the pattern stays in the encoded slot and chat reports how many disks the list currently holds.

## Uploading to NEO ECO

With NEO ECO AE Extension installed, an upload button appears in the top right of the interface. It sends the pattern in the encoded slot to NEO ECO's computation cluster pattern storage, clears the slot on success, and returns a blank pattern in the order network → inventory → encoded slot. Without that mod the button does not appear.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_encoding_terminal" />
