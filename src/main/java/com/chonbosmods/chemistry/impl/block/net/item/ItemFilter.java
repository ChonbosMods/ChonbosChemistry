package com.chonbosmods.chemistry.impl.block.net.item;

/**
 * A per-pipe gate deciding whether a given item may pass at a point in the network
 * (2026-06-06 item-channel design §13.4, "Filter stub"). The transport code consults a filter at
 * three points, all of which read this one seam:
 *
 * <ol>
 *   <li><b>Extraction</b>: a PULL face only pulls a stack its own pipe's filter admits.
 *   <li><b>Destination qualification</b>: a candidate container is only a valid target if the
 *       delivering pipe's filter admits the stack.
 *   <li><b>Junction hops</b>: during pathfinding, a junction pipe whose filter rejects the stack is
 *       impassable for that stack (the edge is gated, the route detours).
 * </ol>
 *
 * <p>v1 ships only {@link #ALLOW_ALL}: this interface is a deliberate stub seam (user request). The
 * planned implementer is the tag/type filter configured at pipe intersections; because routing
 * consumes this seam from day one, that future feature only supplies a richer {@link FilterLookup}
 * and never rewrites the routing.
 */
@FunctionalInterface
public interface ItemFilter {

    /**
     * Whether {@code key} may pass at the pipe {@code pipeKey}, arriving through face {@code viaFace}.
     *
     * @param key     the item under consideration; implementations must tolerate {@code null}
     *                (callers may probe before a stack exists)
     * @param pipeKey the packed position key of the pipe whose filter this is
     * @param viaFace the face index (0..5) the item arrives through, for direction-aware filters
     * @return true if the item is admitted
     */
    boolean admits(ItemKey key, long pipeKey, int viaFace);

    /** The v1 filter: admits every item, including a {@code null} key (null-defensive). */
    ItemFilter ALLOW_ALL = (key, pipeKey, viaFace) -> true;
}
