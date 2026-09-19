---
navigation:
  parent: index.md
  title: ME Pattern Disk Provider
  position: 1020
item_ids:
- ae2_pattern_disk:pattern_disk_provider
- ae2_pattern_disk:cable_pattern_disk_provider
categories:
- devices
---

# ME Pattern Disk Provider

The ME Pattern Disk Provider is a pattern provider that serves real pattern disks. Put disks holding patterns into its disk slots and it offers their encoded patterns to ME autocrafting.

It only pushes jobs; it does not craft. It sends patterns and materials to a compatible machine beside it, such as the Efficient Molecular Assembler.

Finished items can come back to the provider's return slots. The disks are the sole source of truth: change what is on them and the provider works out afresh what it can offer.

## Using it

1. Connect the provider to your ME network.
2. Insert one or more pattern disks.
3. Put an Efficient Molecular Assembler or another compatible machine next to it.
4. Write patterns onto the disks with the ME Pattern Transferer or the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md), then insert them.

## Panel form

Mounted on an ME cable it becomes a panel: same disk slots, interface, pattern logic and return-slot interface as the block form. The difference is that it only pushes patterns out of the face it is attached to, which suits tighter cabling.

The two forms convert into each other: a shapeless craft in a crafting grid swaps one for the other, with no extra materials.

## Upload button (ExtendedAE Plus)

With ExtendedAE Plus installed, its "upload pattern to a provider" button works on this provider too. The provider has no pattern slots of its own, so an uploaded pattern lands in a free space on one of the disks it holds.

## Recipes

<RecipesFor id="ae2_pattern_disk:pattern_disk_provider" />

<RecipeFor id="ae2_pattern_disk:cable_pattern_disk_provider" />
