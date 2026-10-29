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

The Marked Pattern Disk Provider has no disk slots of its own. Pick one mark from the marks already carried by pattern disks anywhere on the same ME network, and it offers the patterns of every disk carrying that mark to autocrafting - a mirror of those disks, not a copy of them.

It behaves like an <ItemLink id="ae2:pattern_provider" /> in every other respect: it pushes patterns and materials to adjacent machines, has a returned-items inventory, and carries the same left toolbar.

## Picking a mark

The candidate grid holds three rows of seven buttons. Each button shows the machine (catalyst) the mark's recipe category belongs to; when a category lists several machines they rotate on a fixed beat. Marks that read as the same thing are folded into one candidate: a disk named `#minecraft:crafting` and one named `#mode:crafting` share a button, because both stand for crafting patterns. Marks you typed by hand stay separate.

Buttons read three states: idle, the currently selected mark, and the mark under the cursor.

- **Search** sits above the grid and only filters the candidates; right-click it to clear. It does not take focus when the screen opens.
- **Display mode** switches between all marks and the selected one only.
- **Clear selected mark** asks once: the first click turns the button red, the second one clears the selection. Clicking anywhere else cancels.

With no mark selected the provider offers nothing. That is deliberate - an empty mirror would otherwise duplicate every pattern on the network.

## What it does not mirror

Mirrored patterns exist for autocrafting only. They never appear in the pattern disk management terminal or any other disk listing, and they cannot be taken out - the disks themselves remain the only place patterns are stored.

## Dispatch and blocking

The toolbar keeps the three provider-specific buttons:

- **Blocking mode** - non-blocking, AE2's blocking mode, or smart blocking.
- **Dispatch boost** - standard dispatch or smart boost, with an optional cap.
- **Product extraction** - off, normal, or fast.

All three are recorded on the memory card together with the selected mark, so copying a configured provider and pasting it elsewhere restores the whole setup.

## Panel form

Mounted on an ME cable it becomes a panel with the same interface and behaviour, and it pushes out of the face it is attached to until changed with a wrench. Both forms share the same logic, menu and layout document.
