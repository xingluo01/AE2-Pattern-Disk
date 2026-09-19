---
navigation:
  parent: index.md
  title: Batch Assembler
  position: 1050
item_ids:
- ae2_pattern_disk:batch_molecular_assembler
categories:
- machines
---

# Batch Assembler

The Batch Assembler buffers the crafting jobs a pattern provider pushes at it in **storage cells** inside the machine, and once no new materials have arrived for a short while it runs everything it can from that buffer in one go. It suits large orders: let the materials pile up, then craft the batch and send the products back to the network together.

## Cell slots

Nine **cell slots** run along the bottom of the interface and take storage cells of any type (the same filter as an ME Drive). Here a cell is nothing but the machine's private buffer: its contents never enter the ME network and cannot be pulled out by the network or by pipes. A cell that registers no storage handler — a spatial cell, for instance — provides no buffering at all. **The slots lock while the machine is working** (while there are buffered crafting jobs), so nothing can be added or removed and no items can be duplicated. Pressing "Return buffer" sends every buffered raw material back to the ME network; once the machine has been idle for 10 ticks (no backlog and nothing waiting to be returned), whatever is left in the cell slots also goes back automatically, so materials do not end up locked away as the machine's private store. If the network cannot take them, the remainder stays in the cell and is reconsidered the next time there is work — it does not retry every tick. Items you put into a cell by hand are carried back to the network by the same mechanism.

The queue lives in memory only: after loading a save the machine has no backlog, and the materials those jobs brought in are returned to the network by the idle-return mechanism above. You will need to cancel the matching crafting CPU jobs by hand.

## Pattern pool

Nine **pattern disk slots** (one row in the interface). The recipes on all those disks are pooled into the machine's shared pattern pool and exposed to ME autocrafting; the disks also show up in the disk lists of the **pattern access terminal** and the [ME Pattern Disk Encoding Terminal](pattern_disk_encoding_terminal.md). Only the three instant recipe types — crafting table, smithing table and stonecutting — can be run by this machine; processing patterns are refused.

Intermediate products are not swallowed by the machine: everything a craft produces (including leftover containers) goes into the machine's **smooth return queue**, which sends about 5% of the running total per tick back to the ME network over roughly 20 ticks, so the crafting CPU can schedule the next step without fighting the machine's bookkeeping. If the network is short on space the products queue up and keep going, with no rollback of materials and no products taking up the cell slots. When an item is exactly what the crafting CPU is waiting for right now — a bottle recovered by the pattern chain, say — it does not follow the 5%/tick rate but goes in one push that tick (at least 512 per tick, and never below the smooth rate): stalling on that one item would hold up the whole chain. The per-write cap for large orders is unchanged.

## Batch wait modes

The machine is driven purely by **quiet time**: every time materials arrive the clock restarts, and the batch only starts once the materials really stop (two successful pushes at least the chosen quiet period apart), after which everything buffered runs at once:

* **Standard**: batches after 40 ticks of quiet;
* **Fast**: batches after 10 ticks of quiet;
* **Small batch, quick start**: when the previous batch produced fewer than 8 jobs, the quiet window clearly caught almost nothing, so the next batch starts after just 1 tick; after 32 such small runs in a row the machine spends one full window re-measuring the feed (so an order that has turned into a steady stream is not run push by push), and a feed that stays stopped for 8 windows resets the classification, so a large order after a long idle spell still batches as before. Runs that reach 8 jobs or more, and machines that have not run a first batch yet, always wait the full window — which is exactly why the batching behaviour for large orders is unchanged. What this saves is scheduling overhead (fewer runs and fewer save writes), not power: the machine charges 10 AE **per job**, not per batch. Window state is not persisted; after loading a save it starts from a full window.

There is no extra cap on the backlog: the cell slots hold as much as the cell itself allows. When the buffer is full new pushes are refused (the machine does not report itself as "busy" over this, and the CPU retries later), while a backlog that is already there still starts once the successful pushes stop, after one quiet window. Throughput is capped by what the crafting CPU can hand out (its own acceleration and co-processing cards), so pair the machine with CPU acceleration cards.

## Acceleration cards

Up to four AE2 acceleration cards, each doubling the worker threads (2 / 4 / 8 / 16). The threads are used only to **analyse queued patterns in parallel**: when a pattern first enters the queue its input variants, leftover containers and primary output are resolved and cached for reuse.

* **Storage and network stay on the server thread**: cell reads and writes and grid interaction must happen there (AE2's storage is not thread-safe), so the cards do not split a batch across threads and do not change how much is produced;
* **No per-tick limit**: as much of the backlog as possible is finished each tick, rather than the batch being cut off at a fixed number of jobs;
* **Smooth product return**: products are not written to the ME network in one go but returned at about 5% of the running total per tick over roughly 20 ticks, so large orders do not stall the machine on a single burst of storage IO; when the network is short on space the products queue inside the machine until there is room again.

The total produced always follows the ordered amount; the cards never change it.

## Pairing with a pattern provider

Put it next to an ME Pattern Disk Provider or any other AE2 pattern provider: the provider pushes patterns and materials, the Batch Assembler batches and crafts, and the products go back.

## Recipe

<RecipeFor id="ae2_pattern_disk:batch_molecular_assembler" />
