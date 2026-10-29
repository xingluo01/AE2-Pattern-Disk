---
navigation:
  parent: index.md
  title: ME Pattern Disk Provider
  icon: pattern_disk_provider
  position: 1020
item_ids:
- ae2_pattern_disk:pattern_disk_provider
- ae2_pattern_disk:cable_pattern_disk_provider
categories:
- devices
---

# ME Pattern Disk Provider

<Row gap="20">
  <BlockImage id="pattern_disk_provider" scale="8" />
  <ItemImage id="cable_pattern_disk_provider" scale="4" />
</Row>

The ME Pattern Disk Provider replaces the pattern slots with real pattern disks. Put disks holding patterns into its disk slots and their encoded patterns are offered to ME autocrafting; everything else behaves like an <ItemLink id="ae2:pattern_provider" />, including pushing patterns and materials to adjacent machines and taking products back through its return inventory ("Returned Items" in the interface).

The disks are the sole source of truth: change what is on them and the provider works out afresh what it can offer.

## Using it

Write patterns onto disks with the ME Pattern Transferer or the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md), insert the disks, and place the provider next to the target machine.

## Panel form

Mounted on an ME cable it becomes a panel, matching AE2's flat pattern provider variant: same disk slots, interface, pattern logic and return inventory interface as the block form, and it pushes out of the face it is attached to until the direction is changed (AE2's flat variant can only push in one direction; this mod's panel can be switched to omnidirectional). The two forms convert into each other with a shapeless craft.

## Push direction

The block form matches AE2's normal and directional variants and switches with a wrench in rotate mode on a face; the panel walks a fixed cycle with a wrench in rotate mode: the attached side (default), omnidirectional, the other five faces.

- Omnidirectional means all six sides; directional means that one side only.
- The panel has no arrow model, so it announces the new setting on the action bar.

## Upload button

With ExtendedAE Plus installed, its "upload pattern to a provider" button works here too. The provider has no pattern slots of its own, so an uploaded pattern lands in a free space on one of the disks it holds; **AE2:Utility** uploads the same way and behaves the same.

Storing a pattern on a disk returns the blank pattern it freed to the ME network, the mirror image of the blank pattern spent when a pattern is taken back out. A network that is gone or full refuses the write: the upload fails, the pattern stays with the uploader, and the blank pattern is not consumed.

## Blocking and dispatch boost

The left toolbar carries three provider-specific buttons.

**Blocking mode** cycles through three states:

- **Non-blocking** - ignore the contents of the target.
- **Blocking** - AE2's blocking mode: do not push crafting ingredients if the target inventory contains a pattern input.
- **Smart blocking** - do not push crafting ingredients if the target inventory contains anything other than this pattern's inputs. A target made up only of this pattern's inputs, or an empty one, stays usable.

**Dispatch boost** has two states:

- **Standard dispatch**: use AE2's standard dispatch.
- **Smart boost**: hold the copies the crafting CPU hands over during the same tick and merge them into a
  single delivery once the CPU has stopped handing over.

The field to the left of the button caps the copies per batch; `0` means unlimited. With no cap, do not
leave the sender handing over for long: the copies pile up in the machine (up to 512 are saved with the
block, the rest live in memory only), so a large order is better off with a number in that field. Targets that only accept the usual
single-pattern push are unaffected: the copies fall back to one delivery each.

Two known boundaries:

- The panel form accumulates the same way; its flush is driven by a server level tick.
- With either state on, dispatch takes over the provider's own path, so the **crafting lock is not
  re-armed** while it is on (the lock is merely cleared) - AE2 only re-arms it inside its private push handler.

**Output extraction** cycles through three states:

- **Do not extract** - only the target machine's own push is used.
- **Extract** - pull outputs out of the target every 20 ticks.
- **Fast extraction** - the initial extraction interval is 1 tick; after a miss the interval doubles (capped at 20 ticks) and snaps back to 1 tick as soon as something is extracted.

Extraction only takes items that appear in the **outputs of an installed pattern**, so leftover ingredients, tools and anything else stay where they are. Each pass moves as much as the network accepts; whatever does not fit stays in the target.

Because the whitelist is the pattern outputs, a product that is also the input of another installed pattern can be pulled back into the network too. If two patterns share one machine and one feeds the other, keep them on separate providers or leave extraction off on that machine.
