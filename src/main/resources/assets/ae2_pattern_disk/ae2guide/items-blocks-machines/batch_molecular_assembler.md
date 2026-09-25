---
navigation:
  parent: index.md
  title: Batch Assembler
  icon: batch_molecular_assembler
  position: 1050
item_ids:
- ae2_pattern_disk:batch_molecular_assembler
categories:
- machines
---

# Batch Assembler

<BlockImage id="batch_molecular_assembler" scale="8" />

The Batch Assembler buffers the crafting jobs a pattern provider pushes at it inside storage cells in the machine, and once materials stop arriving for a short while it runs everything it can from that buffer in one go. It suits large orders.

## Cell slots

Nine cell slots run along the bottom of the interface and accept storage cells of any type, with the same filter as an ME Drive. A cell here is the machine's private buffer: it is not exposed to the ME network and cannot be taken out by the network or by pipes, and a cell that offers no storage, such as a spatial cell, does nothing.

The slots lock while the machine holds buffered jobs. Pressing "Return to Buffer" sends the buffered raw materials back to the ME network; once the machine has been idle for 10 ticks the rest of the cell contents follows automatically. If the network cannot take them the remainder stays in the cell and is reconsidered when the machine next has work. Items put into a cell by hand are returned by the same mechanism.

The job queue lives in memory only: after loading a save there is no backlog, the materials those jobs brought in are returned by the mechanism above, and the matching crafting CPU jobs need cancelling by hand.

## Pattern pool

Nine pattern disk slots run in one row. The recipes on all those disks pool into the machine's shared pattern pool and are exposed to ME autocrafting, and the disks also appear in the management terminal and the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md) disk lists. Only crafting table, smithing table and stonecutting recipes are run; processing patterns are refused.

## Product return

Everything a craft produces, leftover containers included, enters a return queue that hands it to the ME network and on to the crafting CPU for the next step, so the machine does not fight the CPU's bookkeeping.

Products are handed to the ME network in full. When the network is short on space the products queue and retry, materials are not rolled back, and products take up no cell slots.

## Batch wait modes

The machine batches by quiet time: every arriving material restarts the clock, and once materials actually stop the batch starts and everything buffered runs at once.

- **Standard**: batches after 40 ticks of quiet.
- **Fast**: batches after 10 ticks of quiet.
- **Small batch, quick start**: a batch of fewer than 8 jobs makes the next batch wait only 1 tick; after 32 such small runs the machine re-measures the feed over one full window, and a feed stopped for 8 windows resets the classification. Runs of 8 jobs or more, and machines that have not run a first batch yet, always wait the full window.

Each actual run charges 10 AE: the whole-batch path counts a batch as one run, so a batch costs 10 AE no matter how many jobs it holds, while the per-craft path charges 10 AE per craft. Batching therefore lowers power use as well as scheduling overhead. Window state is not persisted; after loading a save it starts from a full window.

There is no extra cap on the backlog beyond what the cells hold. When the buffer is full, new pushes are refused (the machine does not report itself busy, and the CPU retries later); a backlog already gathered still starts one quiet window after the pushes stop. Throughput is capped by what the crafting CPU can hand out, so pair the machine with CPU acceleration cards.

## Acceleration cards

Up to four <ItemLink id="ae2:speed_card" />, each doubling the parallel analysis (2 / 4 / 8 / 16). The total produced follows the ordered amount and the cards never change it.

## Pairing with a pattern provider

Put it next to an ME Pattern Disk Provider or any other AE2 pattern provider.

## Recipe

<RecipeFor id="ae2_pattern_disk:batch_molecular_assembler" />
