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

Three more modes (advanced, chiseling, overloaded) appear once the matching encoder from another mod sits in an upgrade slot, and **dropping an encoded pattern into the output slot switches the terminal to that pattern's mode** - overloaded lays out each row's items with its input and output, advanced reads each slot's face back, chiseling works out what is being chiseled into what. Taking the pattern away does not switch back.

The terminal scans the whole ME network for pattern disks; pick one and the pattern can be written onto it, or the patterns it holds reviewed and tidied.

## Disk list

The lower part of the interface lists the network's pattern disks, every one of them when the search bar is empty, unmarked disks included. The keys a disk cell accepts are in its hover tooltip.

The search bar filters in two ways:

- **Plain text**: matches the disk's name (with JECH installed, Chinese names also match by pinyin and initials).
- **Text starting with `#`**: matches the disk's mark. A mark records which work block a disk belongs to: writing one with a work block on the cursor records that block's category, without one it records the imported recipe's category, and with neither nothing is written. Crafting, smithing and stonecutting marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind answer the same search; processing has no single category, so a hand-encoded processing disk keeps the name "Processing pattern".

The toggle beside the search bar decides whether unmarked disks appear: off filters by the typed text, so a `#` search leaves unmarked disks out; on keeps them listed regardless of the search.

### Sorting

When the item grid sorts by mod, an extra "additional sort" toggle appears (on by default): within a mod it looks at the tier word in the name, then groups names that match once the numbers are removed, and orders each group by the numbers in the name - so different capacities of the same series stay next to each other and `16k` no longer comes before `1k`. Both the tier table and which names take part in the numeric level live in `config/ae2_pattern_disk-client.toml` (the file's comments explain the syntax); turning the toggle off falls back to AE2's original two levels (mod, then literal name).

It stays hidden in the other sort modes. The same toggle governs the patterns inside each disk on the management terminal.

### Write results

Every write reports back in chat. Success names the disk written to; failure gives the reason, such as the disk being full, locked to another pattern type, already holding a recipe with the same output, the pattern's type being unresolvable, or the target disk no longer being in the list.

### Writing as you encode

Pressing Encode writes to the disk in the current group that has the most room left. The search bar decides which disks are candidates at all - a disk it filters out is never written to - and Encode picks within that set. (To fill disks in list order instead, end the search text with `@order`.) On success the encoded slot is cleared and the freed blank pattern is returned in the order network → inventory → encoded slot. When the list holds no disk of the current group, Encode writes nothing and the pattern stays in the encoded slot - that is a missing target, not a failure. If some disk did refuse it, chat reports the reason from the first disk that refused.

The "current group" is the pattern kind your mode produces. A disk belongs to it either through its mark or through the type it was locked to when its first pattern was written, so a disk marked "crafting" and one locked to the crafting type are one group. An empty disk - no mark, no lock - belongs to no group and is never written to automatically, and when no disk is in the group, Encode writes nothing and says nothing.

## Uploading to NEO ECO

With NEO ECO AE Extension installed, an upload button appears in the top right. It sends the pattern in the encoded slot to NEO ECO's computation cluster pattern storage, clears the slot on success, and returns the replacement the other side names (usually a blank pattern) in the order network → inventory → encoded slot. Nothing is handed back when the pattern was merely stored as an item, since the network already holds that stack; when the other side has no replacement to give, the pattern stays in the encoded slot rather than being dropped. Without that mod the button does not appear.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_encoding_terminal" />
