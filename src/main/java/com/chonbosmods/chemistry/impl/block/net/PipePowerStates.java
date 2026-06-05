package com.chonbosmods.chemistry.impl.block.net;

/**
 * Pure mapping between a power cable's <em>unpowered</em> and <em>powered</em> block-state names.
 *
 * <p>The cable's {@code State.Definitions} come in twin pairs: each topology shape has an OFF state
 * (PascalCase, e.g. {@code "Elbow"}) using {@code cable_pipe_off.png} and an ON twin
 * ({@code "Elbow_On"}) overriding the texture to {@code cable_pipe_on.png}. The base/default block (an
 * unpowered node) maps to/from the special {@code "On"} powered-node state and {@code "default"}
 * (which the engine resolves back to the base block).
 *
 * <p>{@link NetworkTickSystem} reads each member cable's current interaction-state name once per tick
 * and, depending on whether the network is energized, computes {@link #poweredOf} or
 * {@link #unpoweredOf} and only swaps the block when the name actually changes. Both directions are
 * idempotent, so re-applying the same intent never triggers a redundant swap.
 *
 * <p>Convention: the engine reports the BASE block's state as {@code null} (no entry) or
 * {@code "default"}; both are treated as the unpowered node here. A {@code null}/empty/{@code "default"}
 * input therefore powers up to the bare {@code "On"} node, and powering down any ON state that has no
 * shape prefix returns to {@code "default"} (the base block).
 */
public final class PipePowerStates {

    /** Suffix marking a powered twin state. */
    private static final String ON_SUFFIX = "_On";
    /** The bare powered-node state (twin of the unpowered base/default block). */
    private static final String ON = "On";
    /** The engine's "return to base block" pseudo-state. */
    private static final String DEFAULT = "default";

    private PipePowerStates() {
    }

    /**
     * The powered twin of {@code state}.
     *
     * <ul>
     *   <li>{@code null} / {@code ""} / {@code "default"} &rarr; {@code "On"} (the powered node)
     *   <li>{@code "On"} &rarr; {@code "On"} (idempotent)
     *   <li>any already-powered state ({@code *_On}) &rarr; unchanged (idempotent)
     *   <li>any OFF shape, e.g. {@code "Elbow"} &rarr; {@code "Elbow_On"}
     * </ul>
     */
    public static String poweredOf(String state) {
        if (isBlank(state) || DEFAULT.equals(state)) {
            return ON;
        }
        if (isPowered(state)) {
            return state; // already powered (either "On" or "<Shape>_On")
        }
        return state + ON_SUFFIX;
    }

    /**
     * The unpowered twin of {@code state}.
     *
     * <ul>
     *   <li>{@code "On"} &rarr; {@code "default"} (back to the base block)
     *   <li>{@code "Elbow_On"} &rarr; {@code "Elbow"}
     *   <li>{@code null} / {@code ""} &rarr; {@code "default"}
     *   <li>any already-unpowered state ({@code "default"} or an OFF shape) &rarr; unchanged (idempotent)
     * </ul>
     */
    public static String unpoweredOf(String state) {
        if (isBlank(state)) {
            return DEFAULT;
        }
        if (ON.equals(state)) {
            return DEFAULT; // the bare powered node returns to the base block
        }
        if (state.endsWith(ON_SUFFIX)) {
            return state.substring(0, state.length() - ON_SUFFIX.length());
        }
        return state; // already an OFF shape or "default"
    }

    /**
     * Whether {@code state} is a powered state: the bare {@code "On"} node or any {@code *_On} twin.
     * {@code null}/empty are not powered.
     */
    public static boolean isPowered(String state) {
        if (isBlank(state)) {
            return false;
        }
        return ON.equals(state) || state.endsWith(ON_SUFFIX);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
