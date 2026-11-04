/**
 * Integrations shipped with this mod, one package per supported neighbour.
 *
 * <p>These are not api. They live here rather than under {@code common} so each one stays a self-contained
 * adapter, and what they need from this mod they ask through
 * {@link io.github.lounode.ae2pattern.api.PatternDiskApi} - the same facade an addon would use. That is
 * deliberate: a facade nobody consumes rots, and these are the consumers that keep it honest.</p>
 *
 * <h2>What an integration does through the api</h2>
 *
 * <ul>
 *   <li><b>Get its machines' disks listed in this mod's terminals.</b> {@code registerDiskHost} hands the
 *       encoding terminal a collector, which is how a machine that cannot implement {@code IPatternDiskHost}
 *       gets listed anyway.</li>
 *   <li><b>Read and decode a disk.</b> {@code contents}, {@code decodePatterns} and {@code patternType} say
 *       what is on one and what each pattern means.</li>
 *   <li><b>Write onto a disk.</b> {@code canAccept} says whether a write would land, {@code insert} does it
 *       and reports what the disk refused, {@code removeAt} takes one off, and {@code BlankPatternSink} is the
 *       ME network accounting a write owes.</li>
 * </ul>
 *
 * <h2>What still names {@code common} - and why that is not a gap</h2>
 *
 * <p>An integration also has to plug into this mod's own machinery, and that part names types the api does not
 * expose: a {@code PatternDiskEncodingTermMenu} subclass, a terminal extension point, a block entity a Jade
 * provider reads. Those implement AE2's and NeoForge's contracts rather than this mod's, so they are not api
 * surface and are not meant to be reached from outside.</p>
 *
 * <p>A fourth case is narrower and worth stating: an integration asking <em>whether a slot holds one of this
 * mod's disks</em> names the disk item, because that is a stricter question than {@code isPatternDisk} answers.
 * Slot admission has to stay strict - an implementation that got in would be configured by machinery that does
 * not recognise it - so the api deliberately offers no way to widen it.</p>
 *
 * <p>An addon that is <em>not</em> shipped with this mod should need nothing but {@code api}. If it finds
 * itself reaching into {@code common}, that is a gap in the facade worth reporting rather than working
 * around.</p>
 */
package io.github.lounode.ae2pattern.integration;

