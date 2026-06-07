package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Source;
import com.chonbosmods.chemistry.impl.block.net.item.ItemPathfinder.Candidate;
import java.util.List;

/**
 * The pure, single-attempt PULL extraction-eligibility function for ONE source endpoint
 * (2026-06-06 item-channel design §13.4). Given a {@link Source} container reached through a PULL pipe
 * face, it decides whether an item may be pulled out THIS interval and, if so, produces the in-flight
 * {@link TravelingStack} the caller hands to that source's via-pipe {@code inTransit} list. The pull
 * INTERVAL itself lives in the engine glue (Task 7's tick driver); this layer is the single attempt.
 *
 * <h2>The rules it enforces (design-verbatim)</h2>
 * <ol>
 *   <li><b>Saturation backpressure</b> — design "[TUNE]": <i>"max in-transit per network = segment
 *       count (saturation also blocks extraction)"</i>. When {@code inTransitCount >= saturationCap}
 *       the source is not even consulted: a saturated network applies backpressure and pulls nothing.
 *   <li><b>No extraction without a destination</b> — design "Decisions": <i>"No extraction without a
 *       destination: a PULL face extracts only after the network confirms a valid, admitting, accepting
 *       destination (Mekanism sorter rule)."</i> The pathfinder's nearest-first candidates are probed
 *       (simulate insert) and the FIRST that accepts &gt; 0 wins; if none accept, nothing is extracted
 *       (and the source's contents are left untouched: only a {@code firstExtractable} simulate happens).
 *   <li><b>Per-pull cap</b> — design "[TUNE]": <i>"per-pull cap ~16 items"</i>. The candidate stack
 *       reported by {@link ContainerView#firstExtractable} is already capped at {@code pullCap}.
 * </ol>
 *
 * <h2>Which filter applies at the SOURCE</h2>
 * The filter consulted at extraction is the SOURCE'S VIA-PIPE filter, evaluated against the source's
 * RECEIVING face (the PULL face index the pipe presents toward the container). This is consistent with
 * the pathfinder's "filter gates the pipe you are ENTERING" rule (see {@link ItemPathfinder}): the
 * extracted stack is entering the source via pipe, so that pipe's filter is the gate. The filter call
 * is delegated to {@link ContainerView#firstExtractable}, which receives the pipe key + face and
 * consults the filter while it scans for the first admitted stack.
 *
 * <h2>Sizing decision (step 3 of the plan, DECIDED)</h2>
 * When the first accepting candidate accepts FEWER items in simulate than are extractable, this layer
 * extracts only {@code min(extractable, accepted)} — it sizes the stack to what the first accepting
 * destination will take. RATIONALE: it never creates a stack that is GUARANTEED to partially bounce on
 * arrival. The re-route ladder ({@link ItemTransit}) still handles a destination that fills DURING the
 * trip (arrival-only re-validation), but there is no reason to launch a stack already known to overflow
 * its target. This is simple and Mekanism-faithful (a logistical sorter pulls toward a confirmed sink
 * and right-sizes to it). The remaining source contents stay put for the next interval's attempt.
 *
 * <h2>Source self-exclusion</h2>
 * A destination whose {@code containerKey} equals the source container is NOT a valid target: a chest
 * must never pull out of itself to deliver back into itself, even when it has room. The same physical
 * chest can legitimately appear as both a {@link Source} (a PULL face) and a
 * {@link com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination} (a NORMAL/PUSH face),
 * so the candidate scan skips any candidate at the source's own position.
 *
 * <h2>Metadata seam</h2>
 * The pure layer reasons over {@link ItemKey} (id + count) alone; item metadata is knowable only by the
 * world container at the moment of extraction. {@link ContainerView#extract} therefore returns an
 * {@link ContainerView.Extracted} carrying both the committed amount AND the extracted stack's opaque
 * metadata, which this layer threads verbatim into {@link TravelingStack#of} without inspecting it. The
 * in-memory test fake supplies {@code null} metadata; the world impl (Task 7) supplies the real
 * document read from the matched slot.
 *
 * <p>Pure JDK + net-layer types only: no engine imports.
 */
public final class ItemExtraction {

    private ItemExtraction() {
    }

    /**
     * Attempt one PULL extraction from {@code source} this interval.
     *
     * @param source         the PULL source endpoint to try (its via pipe is the extraction entry).
     * @param net            the source's ITEM network (bounds the destination-confirmation BFS).
     * @param grid           resolves member pipes to live
     *                       {@link com.chonbosmods.chemistry.impl.block.net.PipeNode}s.
     * @param containers     the container seam: source scan/extract + destination accept probe.
     * @param endpoints      the network's collected endpoints (destinations confirm the pull).
     * @param filters        the per-pipe filter lookup (v1 {@link FilterLookup#NONE}); the SOURCE'S via
     *                       pipe filter gates the extractable stack.
     * @param pullCap        [TUNE] the per-pull item cap (~16): the stack never carries more.
     * @param inTransitCount the number of stacks currently in flight on this network (for saturation).
     * @param saturationCap  [TUNE] the in-transit ceiling (~network segment count): at/over it,
     *                       extraction is blocked (backpressure).
     * @return the newly created in-flight stack (the caller adds it to the source's via-pipe
     *         {@code inTransit}), or {@code null} when nothing was extracted for ANY of the rules above.
     */
    public static TravelingStack tryExtract(
            Source source,
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters,
            int pullCap,
            int inTransitCount,
            int saturationCap) {

        // Rule 1 — SATURATION backpressure (design [TUNE]: "saturation also blocks extraction"). The
        // source is not even consulted: a saturated network pulls nothing.
        if (inTransitCount >= saturationCap) {
            return null;
        }

        ContainerView src = containerAt(containers, source.containerKey());
        if (src == null) {
            return null; // the source container vanished
        }

        // Rule 3 (cap) + the source viaPipe filter: firstExtractable scans for the first stack the
        // SOURCE via-pipe filter admits, reported with its count already capped at pullCap. Null means
        // empty / filter admits nothing.
        ItemKey extractable = src.firstExtractable(
            filters.forPipe(source.viaPipeKey()), source.viaPipeKey(), source.viaFace(), pullCap);
        if (extractable == null) {
            return null;
        }

        // Rule 2 — NO EXTRACTION WITHOUT A DESTINATION (Mekanism sorter rule). Find the nearest-first
        // admitting destinations FROM the source via pipe, then take the FIRST one that accepts > 0 in
        // simulate. A destination at the source's OWN container is excluded (never pull-then-deliver to
        // self). The sizing decision: extract min(extractable, what that first candidate accepts).
        List<Candidate> candidates =
            ItemPathfinder.candidates(source.viaPipeKey(), extractable, endpoints, net, grid, filters);

        Candidate chosen = null;
        int sized = 0;
        for (Candidate c : candidates) {
            long destKey = c.destination().containerKey();
            if (destKey == source.containerKey()) {
                continue; // source self-exclusion: don't deliver back into the chest we pulled from
            }
            ContainerView destView = containerAt(containers, destKey);
            if (destView == null) {
                continue; // candidate container vanished
            }
            int accepted = destView.insert(extractable, extractable.count(), true);
            if (accepted > 0) {
                chosen = c;
                sized = Math.min(extractable.count(), accepted);
                break; // first accepting candidate wins (nearest-first)
            }
        }
        if (chosen == null) {
            return null; // no admitting, accepting destination -> no extraction
        }

        // Rule 4 — COMMIT. Extract the right-sized amount for real. The commit may pull LESS than
        // promised if the contents raced down between firstExtractable and now: trust the actual amount.
        ContainerView.Extracted pulled = src.extract(extractable, sized, false);
        int actual = pulled.amount();
        if (actual <= 0) {
            return null; // raced empty: never build a count<=0 TravelingStack (persistence invariant)
        }

        // Build the in-flight stack. Path is the chosen candidate's (candidate-owned; of() copies).
        // Metadata flows verbatim from the commit (the world impl supplies the real document).
        return TravelingStack.of(
            extractable.id(), actual, pulled.metadata(), chosen.path(),
            source.containerKey(), chosen.destination().containerKey());
    }

    private static ContainerView containerAt(ContainerLookup containers, long key) {
        return containers.at(
            NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
    }
}
