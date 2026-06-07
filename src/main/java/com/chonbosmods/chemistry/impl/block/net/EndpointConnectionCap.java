package com.chonbosmods.chemistry.impl.block.net;

/**
 * The shared "connection cap" rule (2026-06-07 tip-rendering design, Decision 1): a single pipe block
 * joins AT MOST 3 non-pipe endpoints (containers/machines/tanks). A 4th adjacent qualifying endpoint
 * does not connect: deterministic selection, lowest face index wins.
 *
 * <h2>Counts CONNECTIONS, not adjacency</h2>
 * The cap counts a face only once the neighbour beyond it WOULD be collected: it has already passed the
 * face-state gate and the port/container qualification checks of the caller. A {@link FlowState#NONE}
 * face, an empty cell, a wrong-channel neighbour, or a non-overlapping port consumes NO budget: those are
 * not connections. The cap therefore never starves a pipe of real connections just because it is
 * surrounded by irrelevant blocks.
 *
 * <h2>Per-MEMBER-pipe, not per-network</h2>
 * Each pipe block gets its own fresh budget of 3. In the endpoint walks ({@code ItemEndpoints},
 * {@code NetworkEndpoints}) a new instance is created for every member pipe, so two pipes laid side by
 * side can join 3 endpoints each (6 total) with no interference. In {@link PipeVisualStates#effectiveMask}
 * a single instance scopes the one pipe under inspection.
 *
 * <h2>Face-index order is the tie-break</h2>
 * The callers already iterate faces in OFFSETS order (+X,-X,+Y,-Y,+Z,-Z) without sorting, so claiming
 * budget in iteration order makes the lowest face indices deterministically win the 3 slots and drop the
 * 4th+. No extra ordering is required of callers beyond walking faces ascending.
 *
 * <h2>Storage endpoints count once</h2>
 * A storage neighbour ({@code NetworkEndpoints} BOTH-port across a NORMAL face) contributes a
 * provider-half AND an acceptor-half to the transport layer, but it is ONE connected neighbour: the
 * caller claims the budget ONCE for that face, not once per half.
 *
 * <h2>Effective vs physical (visual twin)</h2>
 * The cap applies to the EFFECTIVE mask only (transport truth). The engine's connected-block PATTERN
 * matcher does NOT cap: it welds an arm to every tagged neighbour regardless. So {@link PipeVisualStates}
 * leaves {@code physicalMask} UNCAPPED. For ITEM/container arms this never matters: containers carry no
 * face tag, so physical never counts them anyway. For MACHINE arms it is the intended behaviour: a 4th
 * machine welds physically but is dropped effectively, so {@code effective < physical} diverges and the
 * existing suppressed-arm swap retargets the cable to the capped (effective) shape: the correct topology
 * renders. No physical-side change is needed.
 *
 * <p>Pure JDK only; no engine types. Single-threaded use (one walk at a time).
 */
public final class EndpointConnectionCap {

    /** The maximum number of non-pipe endpoints a single pipe block may join (Decision 1). */
    public static final int MAX_ENDPOINTS_PER_PIPE = 3;

    private int claimed;

    /**
     * Attempts to claim one connection slot for a qualifying endpoint. Call this ONLY after the neighbour
     * has passed every qualification check (face state, port/container) and survived dedup, exactly at
     * the point the endpoint would be collected.
     *
     * @return {@code true} if within budget (slot claimed: collect the endpoint); {@code false} if the
     *     pipe is already at {@link #MAX_ENDPOINTS_PER_PIPE} (the 4th+ in face-index order: skip it).
     */
    public boolean tryClaim() {
        if (claimed >= MAX_ENDPOINTS_PER_PIPE) {
            return false;
        }
        claimed++;
        return true;
    }
}
