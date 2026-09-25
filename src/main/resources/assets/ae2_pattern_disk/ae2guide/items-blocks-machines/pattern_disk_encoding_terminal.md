---
navigation:
  parent: index.md
  title: ME Pattern Disk Encoding Terminal
  icon: pattern_disk_encoding_terminal
  position: 1035
item_ids:
- ae2_pattern_disk:pattern_disk_encoding_terminal
categories:
- devices
---

# ME Pattern Disk Encoding Terminal

<ItemImage id="pattern_disk_encoding_terminal" scale="4" />

The ME Pattern Disk Encoding Terminal adds pattern disk support to the <ItemLink id="ae2:pattern_encoding_terminal" />. The four modes - crafting, processing, smithing and stonecutting - work as they do there, including substitutions, fluid substitution and rotating the primary output. It differs in three ways:

- The encoded pattern is written onto a pattern disk instead of being handed to a pattern provider; with no single target it stays in the encoded slot.
- Blank patterns do not go into the slot: that slot only shows how many blank patterns the ME network holds, encoding takes one straight from the network, and it says so when there are none.
- Processing mode has three further tools - multiply, divide and merge same items - for scaling a recipe's multiplier or merging input slots that hold the same item. The buttons' tooltips give the multipliers.

The terminal scans the whole ME network for pattern disks; pick one and the pattern can be written onto it, or the patterns it holds reviewed and tidied.

## Disk list

The lower part of the interface lists the network's pattern disks, every one of them when the search bar is empty, unmarked disks included. The keys a disk cell accepts are in its hover tooltip.

The search bar filters in two ways:

- **Plain text**: matches the disk's name (with JECH installed, Chinese names also match by pinyin and initials).
- **Text starting with `#`**: matches the disk's mark. A mark records which work block a disk belongs to: writing one with a work block on the cursor records that block's category, without one it records the imported recipe's category, and with neither nothing is written. Crafting, smithing and stonecutting marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind answer the same search; processing has no single category, so a hand-encoded processing disk keeps the name "Processing pattern".

The toggle beside the search bar decides whether unmarked disks appear: off filters by the typed text, so a `#` search leaves unmarked disks out; on keeps them listed regardless of the search.

### Sorting

When the terminal's item grid sorts by mod, an extra "additional sort" toggle appears, on by default, applying three levels in turn:

- First by the **tier** in the name: `Basic Factory < Advanced Factory < Elite Factory < Ultimate Factory`, `Infused Alloy < Reinforced Alloy < Atomic Alloy`. The tier table lives in `config/ae2_pattern_disk-client.toml`; it ships with tiers for Mekanism factories and alloys and for Powah. Names that match none of them skip this level.
- Then names that match once the numbers are removed share a group: `1k ME Storage Component` and `4k ME Storage Component` together, `1k Crafting Storage` on its own.
- Within a group the numbers decide the order: `1k < 4k < 16k < 64k < 256k < 1M`, `4 < 16 < 64 < 256 < 1024`, with a trailing k/M/G/T/P/E counted as a power of 1024.
- Turning it off falls back to AE2's original two levels (mod, then literal name), where `16k` comes before `1k` again.

It stays hidden in the other sort modes. The same toggle governs the patterns inside each disk on the management terminal.

### Write results

Every write reports back in chat. Success names the disk written to; failure gives the reason, such as the disk being full, locked to another pattern type, already holding a recipe with the same output, the pattern's type being unresolvable, or the target disk no longer being in the list.

### Writing as you encode

With something in the search bar, pressing Encode tries the listed disks in order and writes onto the first one that accepts the pattern, saving the "encode, then click the disk" round trip. On success the encoded slot is cleared and the freed blank pattern is returned in the order network → inventory → encoded slot. An empty search bar writes nothing automatically and the pattern stays in the encoded slot; when no listed disk accepts it, chat reports the reason from the first disk that refused.

## Uploading to NEO ECO

With NEO ECO AE Extension installed, an upload button appears in the top right. It sends the pattern in the encoded slot to NEO ECO's computation cluster pattern storage, clears the slot on success, and returns the replacement the other side names (usually a blank pattern) in the order network → inventory → encoded slot. Nothing is handed back when the pattern was merely stored as an item, since the network already holds that stack; when the other side has no replacement to give, the pattern stays in the encoded slot rather than being dropped. Without that mod the button does not appear.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_encoding_terminal" />
