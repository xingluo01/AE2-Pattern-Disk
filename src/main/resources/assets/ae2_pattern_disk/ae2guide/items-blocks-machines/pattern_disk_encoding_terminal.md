---
navigation:
  parent: index.md
  title: ME Pattern Disk Encoding Terminal
  icon: pattern_disk_encoding_terminal
  position: 1035
item_ids:
- ae2_pattern_disk:pattern_disk_encoding_terminal
- ae2_pattern_disk:wireless_pattern_disk_encoding_terminal
categories:
- devices
---

# ME Pattern Disk Encoding Terminal

<ItemImage id="pattern_disk_encoding_terminal" scale="4" />

The ME Pattern Disk Encoding Terminal adds pattern disk support to the <ItemLink id="ae2:pattern_encoding_terminal" />. The four modes - crafting, processing, smithing and stonecutting - work as they do there, including substitutions, fluid substitution and rotating the primary output. It differs in three ways:

- The encoded pattern is written onto a pattern disk instead of being handed to a pattern provider; with no single target it stays in the encoded slot.
- Blank patterns live in a real slot: up to 4 of them, refilled from the ME network and spent before the network when you encode or take a pattern off a disk - the network refills that slot and is drawn on directly once it is empty. You can drop patterns in yourself or take them out. Blank patterns returned after a write or an upload go into this slot first and overflow to the network only once it is full.
- Processing mode has three further tools - multiply, divide and merge same items - for scaling a recipe's multiplier or merging input and output slots that hold the same item. The buttons' tooltips give the multipliers.

**Merge same items** decides how identical items are written down: with it on, inputs and outputs holding the same item are folded into one slot; with it off, the recipe keeps as many slots as it has, both when a recipe is imported from JEI or EMI and when the pattern is encoded. AE2 itself merges identical inputs when it reads a pattern back, so crafting behaves the same either way - the difference is the structure the pattern is written with, and what the editing area shows. In advanced mode the faces are assigned per item, so two input slots holding the same item share one face. The overloaded tier lays items out row by row and does not take part in merging.

## Encoding modes and the output slot

Three more modes (advanced, chiseling, overloaded) appear once the matching encoder from another mod sits in an upgrade slot, and **dropping an encoded pattern into the output slot switches the terminal to that pattern's mode** - overloaded lays out each row's items with its input and output, advanced reads each slot's face back, chiseling works out what is being chiseled into what. Taking the pattern away, or clearing it to a blank pattern, does not switch the mode; a tier whose
encoder is not in the upgrade slot is not switched to either.

| Pattern | Mode it switches to |
| --- | --- |
| Crafting / processing / smithing / stonecutting pattern | the matching mode |
| Advanced processing pattern | Advanced |
| Chiseling pattern | Chiseling |
| Overloaded pattern | Overloaded |

The terminal scans the whole ME network for pattern disks; pick one and the pattern can be written onto it, or the patterns it holds reviewed and tidied.

## Disk list

The lower part of the interface lists the network's pattern disks, every one of them when the search bar is empty, unmarked disks included. The keys a disk cell accepts are in its hover tooltip.

The search bar filters in two ways:

- **Plain text**: matches the disk's name (with JECH installed, Chinese names also match by pinyin and initials).
- **Text starting with `#`**: matches the disk's mark. A mark records which machine or recipe category a disk belongs to, and is written from the disk list by right click (Shift + right click in the management terminal) with that text in the search bar. Crafting, smithing and stonecutting marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind answer the same search; processing has no single category, so a hand-encoded processing disk keeps the name "Processing pattern",
and a `#` search matches that name.

The toggle beside the search bar decides whether unmarked disks appear: off filters by the typed text, so a `#` search leaves unmarked disks out; on keeps them listed regardless of the search.

**Marking a fresh disk in one go**: importing a recipe with "+" fills the search bar with that recipe's
category name (for example `#Crafting`); right click the disk straight after (Shift + right click in the
management terminal) and the search bar's text becomes its mark. Policy words at the end of the search
text, such as `@order`, are not written into the mark. With an empty search bar the same click clears the
mark.

### Sorting

When the item grid sorts by mod, an extra "additional sort" toggle appears (on by default): within a mod it looks at the tier word in the name, then groups names that match once the numbers are removed, and orders each group by the numbers in the name - so different capacities of the same series stay next to each other and `16k` no longer comes before `1k`. Both the tier table and which names take part in the numeric level live in `config/ae2_pattern_disk-client.toml` (the file's comments explain the syntax); turning the toggle off falls back to AE2's original two levels (mod, then literal name).

It stays hidden in the other sort modes. The same toggle governs the patterns inside each disk on the management terminal.

### Write results

A write reports back in chat only when it fails: the disk's name and the reason - full, locked to another
pattern type, already holding a recipe with the same output, or a pattern whose type cannot be resolved.
A successful write is silent, and so is a candidate list that has all gone stale.

### Writing as you encode

Pressing Encode writes to the disk in the current group that has the most room left. The search bar decides which disks are candidates at all - a disk it filters out is never written to - and Encode picks within that set. (To fill disks in list order instead, end the search text with `@order`.) A large disk keeps being picked
until it stops being the emptiest, so a smaller disk in the same group can sit untouched for a long time -
write `@order`, or click that disk, to rotate. On success the encoded slot is cleared and the freed blank pattern is returned in the order blank-pattern slot → network → inventory → encoded slot. When the list holds no disk of the current group, Encode writes nothing and the pattern stays in the encoded slot - that is a missing target, not a failure. If some disk did refuse it, chat reports the reason from the first disk that refused.

The "current group" is the pattern kind your mode produces. A disk belongs to it either through an
identifier mark (`#mode:*` or `#<category id>`) or through the type it was locked to when its first pattern
was written; a readable category name such as `#Crafting` is for searching only and does not put a disk in a
group. An empty disk - no mark, no lock - belongs to no group and is never written to automatically, and when no disk is in the group, Encode writes nothing and says nothing. To write into an empty disk instead, click that disk.

| Mode | Pattern produced | Type the disk locks to |
| --- | --- | --- |
| Crafting | Crafting pattern | `ae2:crafting_pattern` |
| Stonecutting | Stonecutting pattern | `ae2:stonecutting_pattern` |
| Smithing | Smithing pattern | `ae2:smithing_table_pattern` |
| Processing | Processing pattern | `ae2:processing_pattern` |
| Chiseling | Chiseling pattern | `rechiseledae:chiseling_pattern` |
| Advanced | Advanced processing pattern | `advanced_ae:adv_processing_pattern` |
| Overloaded | Overloaded pattern | `ae2lt:overload_pattern` |

## Uploading to NEO ECO

With NEO ECO AE Extension installed, an upload button appears in the top right. It sends the pattern in the encoded slot to NEO ECO's computation cluster pattern storage, clears the slot on success, and returns the replacement the other side names (usually a blank pattern) in the order blank-pattern slot → network → inventory → encoded slot. Nothing is handed back when the pattern was merely stored as an item, since the network already holds that stack; when the other side has no replacement to give, the pattern stays in the encoded slot rather than being dropped. Without that mod the button does not appear.
