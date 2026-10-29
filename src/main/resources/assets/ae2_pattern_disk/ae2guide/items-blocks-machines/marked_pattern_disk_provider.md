---
navigation:
  parent: index.md
  title: Marked Pattern Disk Provider
  icon: marked_pattern_disk_provider
  position: 1030
item_ids:
- ae2_pattern_disk:marked_pattern_disk_provider
- ae2_pattern_disk:cable_marked_pattern_disk_provider
categories:
- devices
---

# Marked Pattern Disk Provider

<Row gap="20">
  <BlockImage id="marked_pattern_disk_provider" scale="8" />
  <ItemImage id="cable_marked_pattern_disk_provider" scale="4" />
</Row>

The Marked Pattern Disk Provider has no disk slots of its own. Pick one or more marks from those already carried by pattern disks anywhere on the same ME network, and it offers the patterns of every disk carrying a picked mark to autocrafting - a mirror of those disks, not a copy of them.

It behaves like an <ItemLink id="ae2:pattern_provider" /> in every other respect: it pushes patterns and materials to adjacent machines, has a returned-items inventory, and carries the same left toolbar.

## Choosing marks

The candidate grid holds three rows of seven buttons. Each button shows the machine (catalyst) its recipe category belongs to; when a category lists several machines they rotate on a fixed beat. Marks that read as the same thing are folded into one candidate: a disk named `#minecraft:crafting` and one named `#mode:crafting` share a button, because both stand for crafting patterns. Marks you typed by hand stay separate.

Click a button to add that mark to the selection, click it again to take it back out - several marks can be selected at once, and the provider mirrors the union of their disks. Buttons read three states: idle, selected, and the button under the cursor.

- **Search** sits above the grid and only filters the candidates; right-click it to clear. It does not take focus when the screen opens. With JustEnoughCharacters installed it also matches Chinese names by pinyin and initials.
- **Display mode** switches between all marks and only the ones currently selected.
- **Clear selected marks** asks twice: the first click turns the button red, the second drops the whole selection. Clicking anywhere else cancels.

With nothing selected the provider offers no patterns at all. That is deliberate - an empty mirror would otherwise duplicate every pattern on the network.

## What it does not mirror

Mirrored patterns exist for autocrafting only. They never appear in the pattern disk management terminal or any other disk listing, and they cannot be taken out - the disks themselves remain the only place patterns are stored.

Because the mirror is a second route to patterns the source provider already advertises, the same recipe can be offered by two providers at once. AE2 splits deliveries between them, which is what makes one set of disks drive several machines; keep that in mind when reading a plan's parallelism.

## Dispatch and blocking

The toolbar keeps the three provider-specific buttons:

- **Blocking mode** - non-blocking, AE2's blocking mode, or smart blocking.
- **Dispatch boost** - standard dispatch or smart boost, with an optional cap.
- **Product extraction** - off, normal, or fast.

All three are recorded on the memory card together with the selected marks, so copying a configured provider and pasting it elsewhere restores the whole setup. Pasting onto a machine that has no notion of one of them skips that entry quietly.

## Panel form

Mounted on an ME cable it becomes a panel with the same interface and behaviour, and it pushes out of the face it is attached to until changed with a wrench. Both forms share the same logic, menu and layout document.
