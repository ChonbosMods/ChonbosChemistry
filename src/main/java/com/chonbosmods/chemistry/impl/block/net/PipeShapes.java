package com.chonbosmods.chemistry.impl.block.net;

/**
 * Pure, table-driven mapping from a pipe's connected-faces bitmask to its <em>effective-topology</em>
 * interaction-state key plus the Y-rotation that orients that shape.
 *
 * <p>This is the offline-derived twin of the engine's connected-block pattern matcher: given which of
 * the six neighbour faces are connected, it returns the same {@code (shape, rotationIndex)} pair the
 * engine's {@code CustomConnectedBlockPattern.getConnectedBlockTypeKey} would resolve when a cable is
 * placed. The mod needs this because the H8 power-state flip path swaps a cable's interaction state
 * directly ({@link PipePowerStates}) and must therefore know which shape a given face-set maps to
 * without re-running the engine matcher.
 *
 * <h2>Bitmask</h2>
 * Bit order is the OFFSETS face indices: {@code +X=0, -X=1, +Y=2, -Y=3, +Z=4, -Z=5}; bit {@code i}
 * set means face {@code i} is connected. The mask is the low 6 bits of an {@code int} (0..63).
 *
 * <h2>State keys</h2>
 * The returned {@code stateKey} is a PascalCase {@code State.Definitions} key from
 * {@code Server/Item/Items/ChonbosMods/CC_PowerCable.json} (e.g. {@code "Straight"}, {@code "Elbow"},
 * {@code "Vertical_End_Up"}, {@code "Six"}). These are the exact keys
 * {@code BlockAccessor.setBlockInteractionState} and {@link PipePowerStates} consume. The empty
 * (no-connections) {@code node} shape has no Definition of its own: it IS the base/default block, so
 * {@link #stateFor}{@code (0, false)} returns {@code "default"} and {@link #stateFor}{@code (0, true)}
 * returns the bare powered node {@code "On"}.
 *
 * <h2>How the table was derived</h2>
 * The source of truth is
 * {@code Server/Item/CustomConnectedBlockTemplates/CC_PowerConnectedBlockTemplate.json}. Each of the 26
 * non-node shapes carries one {@code PatternsToMatchAnyOf} entry with six {@code RulesToMatch} rules
 * (one per face): an {@code Include} rule pins that face connected, an {@code Exclude} rule pins it
 * empty. Reading the Include set off every shape gives that shape's <em>base</em> face-set (its
 * canonical, rotation-0 mask). Examples: {@code straight}&rarr;{@code +Z|-Z}, {@code elbow}&rarr;
 * {@code +X|+Z}, {@code end}&rarr;{@code +Z}, {@code six}&rarr;all six.
 *
 * <p>A shape's {@code AllowedPatternTransformations} block then says which engine transforms generate
 * additional coverage from that base set: {@code IsCardinallyRotatable:true} adds the three Y-rotations
 * (90/180/270), and {@code MirrorX}/{@code MirrorZ} add mirrored variants. We reproduce the engine's
 * {@code PatternRotationDefinition.computeEnabled()} ordering and its {@code Rotation.rotateY} face
 * permutation ({@code yaw90: (x,y,z)->(z,y,-x)}, {@code yaw180: ->(-x,y,-z)}, {@code yaw270: ->(-z,y,x)})
 * to expand every shape's base mask into the world masks it covers, recording {@code (shape, yaw)} for
 * each. Iterating shapes in template order and rotations in {@code computeEnabled} order with
 * first-match-wins exactly mirrors the engine's resolution order. TIE-BREAK that matters for exactly
 * two masks ({@code 0b110110} and {@code 0b111010}): within a single shape, all four pure cardinal
 * rotations are tried BEFORE any mirror variant, so a mask reachable both by a yaw-2 rotation and by a
 * MirrorX of yaw-0 records rotation index 2 (pure rotation wins). The result: all 64 masks are covered,
 * with zero cross-shape conflicts (the only duplicate hits are a shape matching itself at a symmetric
 * rotation, where the lower yaw index wins). The {@code rotationIndex} we store is the yaw ordinal,
 * which equals {@code RotationTuple.index()} for a pure cardinal rotation
 * ({@code index = roll*16 + pitch*4 + yaw}, and pitch=roll=0 here). The derivation script lived under
 * {@code /tmp/gen_table.py}; this hand-checked table is the shipped deliverable.
 *
 * <h2>Orientation</h2>
 * <b>Finding (decompile-backed): a programmatic state swap does NOT and CANNOT set orientation; the
 * block keeps its existing rotation index.</b> Evidence from the extracted server jar
 * ({@code com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor}):
 * <pre>{@code
 * default void setBlockInteractionState(int x,int y,int z, BlockType bt, String state, boolean force){
 *     ...
 *     BlockType newState = bt.getBlockForState(state);   // state NAME only -> shape geometry
 *     int currentRotation = this.getRotationIndex(x,y,z); // reads the block's EXISTING rotation
 *     this.setBlock(x,y,z, ..., newState, currentRotation, 0, 198); // reuses it verbatim
 * }
 * }</pre>
 * The API takes a state name and no rotation argument; internally it preserves
 * {@code getRotationIndex(x,y,z)}. So the H8 {@code _on}/{@code _off} texture flip
 * ({@link PipePowerStates} + {@code NetworkTickSystem#applyPoweredVisual}) is rotation-preserving by
 * construction: it never reshapes geometry, it only retextures the shape that was already there.
 *
 * <p>By contrast, the natural placement path
 * ({@code ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors} &rarr;
 * {@code CustomConnectedBlockPattern.getConnectedBlockTypeKey}) returns a
 * {@code ConnectedBlockResult(blockTypeKey, rotationIndex)} and applies BOTH via
 * {@code setBlock(..., result.rotationIndex(), ...)}. Orientation therefore lives in a separate
 * rotation index that is set only by the rule-matching engine at placement / neighbour-update time, NOT
 * encoded in the state name.
 *
 * <p><b>Consequence for Task 11.</b> Because rotation is not settable through
 * {@code setBlockInteractionState}, this table returns the {@link Shape} pair (state key +
 * {@code rotationIndex} 0..3) so the caller can decide:
 * <ol>
 *   <li><b>ConnectedBlocksUtil re-resolution</b> for orientation: when a topology change needs a new
 *       shape AND a new rotation, drive the engine matcher (place/neighbour-notify) so it sets the
 *       rotation index itself, then let the H8 flip handle only the {@code _on}/{@code _off} texture on
 *       top of the engine-chosen orientation. This table's {@code rotationIndex} lets Task 11 detect
 *       when a pure state swap would leave the wrong rotation (i.e. {@code rotationIndex != 0} or the
 *       block's current rotation differs) and fall back to re-resolution.</li>
 *   <li><b>Visual-only compromise:</b> for the steady-state energize/de-energize flip where the shape
 *       and rotation are unchanged, the rotation-preserving state swap is correct and cheap. If a flip
 *       ever races a topology change, the worst case is a one-tick stale orientation: the logic
 *       (connectivity) is always correct; only the welded geometry may lag until the next engine
 *       re-resolution. Do not block shipping on visuals.</li>
 * </ol>
 */
public final class PipeShapes {

    /** The bare powered-node state key (twin of the base/default block). See {@link PipePowerStates}. */
    private static final String ON = "On";

    /** The engine's "return to base block" pseudo-state, used for the unenergized node. */
    private static final String DEFAULT = "default";

    /**
     * A resolved effective-topology shape: the PascalCase interaction-state key plus the Y-rotation
     * index (0..3 = yaw None/90/180/270) the engine would orient that shape with.
     *
     * <p>For the empty {@code node} mask the {@code stateKey} is the bare {@code "On"} (never empty), but
     * callers that want the <em>unenergized</em> node should use {@link PipeShapes#stateFor}, which maps
     * it to the base {@code "default"} block.
     */
    public record Shape(String stateKey, int rotationIndex) {
    }

    /**
     * The 64-entry shape table indexed by connected-faces bitmask (0..63). Derived offline from the
     * power {@code CustomConnectedBlockTemplate} (see the class javadoc). Entry [0] is the node, whose
     * {@code stateKey} is the bare {@code "On"}; {@link #stateFor} special-cases it back to
     * {@code "default"} when unenergized.
     */
    private static final Shape[] TABLE = {
        new Shape(ON, 0),                         // 0b000000  node (base block / On twin)
        new Shape("End", 1),                      // 0b000001  end r1  faces=+X
        new Shape("End", 3),                      // 0b000010  end r3  faces=-X
        new Shape("Straight", 1),                 // 0b000011  straight r1  faces=+X|-X
        new Shape("Vertical_End_Up", 0),          // 0b000100  vertical_end_up r0  faces=+Y
        new Shape("Vertical_Elbow_Up", 1),        // 0b000101  vertical_elbow_up r1  faces=+X|+Y
        new Shape("Vertical_Elbow_Up", 3),        // 0b000110  vertical_elbow_up r3  faces=-X|+Y
        new Shape("Vertical_Tee_Up_Ew", 0),       // 0b000111  vertical_tee_up_ew r0  faces=+X|-X|+Y
        new Shape("Vertical_End_Down", 0),        // 0b001000  vertical_end_down r0  faces=-Y
        new Shape("Vertical_Elbow_Down", 1),      // 0b001001  vertical_elbow_down r1  faces=+X|-Y
        new Shape("Vertical_Elbow_Down", 3),      // 0b001010  vertical_elbow_down r3  faces=-X|-Y
        new Shape("Vertical_Tee_Down_Ew", 0),     // 0b001011  vertical_tee_down_ew r0  faces=+X|-X|-Y
        new Shape("Straight_Vertical", 0),        // 0b001100  straight_vertical r0  faces=+Y|-Y
        new Shape("Vertical_Branch", 1),          // 0b001101  vertical_branch r1  faces=+X|+Y|-Y
        new Shape("Vertical_Branch", 3),          // 0b001110  vertical_branch r3  faces=-X|+Y|-Y
        new Shape("Vertical_Straight_Ud_Ew", 0),  // 0b001111  vertical_straight_ud_ew r0  faces=+X|-X|+Y|-Y
        new Shape("End", 0),                      // 0b010000  end r0  faces=+Z
        new Shape("Elbow", 0),                    // 0b010001  elbow r0  faces=+X|+Z
        new Shape("Elbow", 3),                    // 0b010010  elbow r3  faces=-X|+Z
        new Shape("Tee", 3),                      // 0b010011  tee r3  faces=+X|-X|+Z
        new Shape("Vertical_Elbow_Up", 0),        // 0b010100  vertical_elbow_up r0  faces=+Y|+Z
        new Shape("Tripod", 0),                   // 0b010101  tripod r0  faces=+X|+Y|+Z
        new Shape("Tripod", 3),                   // 0b010110  tripod r3  faces=-X|+Y|+Z
        new Shape("Vertical_3horiz_Up", 3),       // 0b010111  vertical_3horiz_up r3  faces=+X|-X|+Y|+Z
        new Shape("Vertical_Elbow_Down", 0),      // 0b011000  vertical_elbow_down r0  faces=-Y|+Z
        new Shape("Tripod_Down", 0),              // 0b011001  tripod_down r0  faces=+X|-Y|+Z
        new Shape("Tripod_Down", 3),              // 0b011010  tripod_down r3  faces=-X|-Y|+Z
        new Shape("Vertical_3horiz_Down", 3),     // 0b011011  vertical_3horiz_down r3  faces=+X|-X|-Y|+Z
        new Shape("Vertical_Branch", 0),          // 0b011100  vertical_branch r0  faces=+Y|-Y|+Z
        new Shape("Fourbent", 0),                 // 0b011101  fourbent r0  faces=+X|+Y|-Y|+Z
        new Shape("Fourbent", 3),                 // 0b011110  fourbent r3  faces=-X|+Y|-Y|+Z
        new Shape("Vertical_3horiz_Ud", 3),       // 0b011111  vertical_3horiz_ud r3  faces=+X|-X|+Y|-Y|+Z
        new Shape("End", 2),                      // 0b100000  end r2  faces=-Z
        new Shape("Elbow", 1),                    // 0b100001  elbow r1  faces=+X|-Z
        new Shape("Elbow", 2),                    // 0b100010  elbow r2  faces=-X|-Z
        new Shape("Tee", 1),                      // 0b100011  tee r1  faces=+X|-X|-Z
        new Shape("Vertical_Elbow_Up", 2),        // 0b100100  vertical_elbow_up r2  faces=+Y|-Z
        new Shape("Tripod", 1),                   // 0b100101  tripod r1  faces=+X|+Y|-Z
        new Shape("Tripod", 2),                   // 0b100110  tripod r2  faces=-X|+Y|-Z
        new Shape("Vertical_3horiz_Up", 1),       // 0b100111  vertical_3horiz_up r1  faces=+X|-X|+Y|-Z
        new Shape("Vertical_Elbow_Down", 2),      // 0b101000  vertical_elbow_down r2  faces=-Y|-Z
        new Shape("Tripod_Down", 1),              // 0b101001  tripod_down r1  faces=+X|-Y|-Z
        new Shape("Tripod_Down", 2),              // 0b101010  tripod_down r2  faces=-X|-Y|-Z
        new Shape("Vertical_3horiz_Down", 1),     // 0b101011  vertical_3horiz_down r1  faces=+X|-X|-Y|-Z
        new Shape("Vertical_Branch", 2),          // 0b101100  vertical_branch r2  faces=+Y|-Y|-Z
        new Shape("Fourbent", 1),                 // 0b101101  fourbent r1  faces=+X|+Y|-Y|-Z
        new Shape("Fourbent", 2),                 // 0b101110  fourbent r2  faces=-X|+Y|-Y|-Z
        new Shape("Vertical_3horiz_Ud", 1),       // 0b101111  vertical_3horiz_ud r1  faces=+X|-X|+Y|-Y|-Z
        new Shape("Straight", 0),                 // 0b110000  straight r0  faces=+Z|-Z
        new Shape("Tee", 0),                      // 0b110001  tee r0  faces=+X|+Z|-Z
        new Shape("Tee", 2),                      // 0b110010  tee r2  faces=-X|+Z|-Z
        new Shape("Cross", 0),                    // 0b110011  cross r0  faces=+X|-X|+Z|-Z
        new Shape("Vertical_Tee_Up_Ns", 0),       // 0b110100  vertical_tee_up_ns r0  faces=+Y|+Z|-Z
        new Shape("Vertical_3horiz_Up", 0),       // 0b110101  vertical_3horiz_up r0  faces=+X|+Y|+Z|-Z
        new Shape("Vertical_3horiz_Up", 2),       // 0b110110  vertical_3horiz_up r2  faces=-X|+Y|+Z|-Z
        new Shape("Five", 0),                     // 0b110111  five r0  faces=+X|-X|+Y|+Z|-Z
        new Shape("Vertical_Tee_Down_Ns", 0),     // 0b111000  vertical_tee_down_ns r0  faces=-Y|+Z|-Z
        new Shape("Vertical_3horiz_Down", 0),     // 0b111001  vertical_3horiz_down r0  faces=+X|-Y|+Z|-Z
        new Shape("Vertical_3horiz_Down", 2),     // 0b111010  vertical_3horiz_down r2  faces=-X|-Y|+Z|-Z
        new Shape("Five_Down", 0),                // 0b111011  five_down r0  faces=+X|-X|-Y|+Z|-Z
        new Shape("Vertical_Straight_Ud_Ns", 0),  // 0b111100  vertical_straight_ud_ns r0  faces=+Y|-Y|+Z|-Z
        new Shape("Vertical_3horiz_Ud", 0),       // 0b111101  vertical_3horiz_ud r0  faces=+X|+Y|-Y|+Z|-Z
        new Shape("Vertical_3horiz_Ud", 2),       // 0b111110  vertical_3horiz_ud r2  faces=-X|+Y|-Y|+Z|-Z
        new Shape("Six", 0),                      // 0b111111  six r0  faces=+X|-X|+Y|-Y|+Z|-Z
    };

    private PipeShapes() {
    }

    /**
     * Resolves the effective-topology {@link Shape} (state key + Y-rotation index) for a 6-bit
     * connected-faces bitmask. Never returns {@code null} and never returns an empty key: every mask in
     * {@code [0,63]} maps to a covered shape. The empty mask (0) resolves to the bare {@code "On"} node
     * key at rotation 0 (see {@link Shape}); use {@link #stateFor} if you want the unenergized node to
     * map to the base {@code "default"} block.
     *
     * @param connectedFacesBitmask bit i set means face i connected (OFFSETS order: +X=0,-X=1,+Y=2,-Y=3,+Z=4,-Z=5)
     * @return the resolved shape (state key + rotation index 0..3)
     * @throws IllegalArgumentException if the mask is outside {@code [0,63]}
     */
    public static Shape resolve(int connectedFacesBitmask) {
        if (connectedFacesBitmask < 0 || connectedFacesBitmask > 63) {
            throw new IllegalArgumentException(
                    "connectedFacesBitmask out of range [0,63]: " + connectedFacesBitmask);
        }
        return TABLE[connectedFacesBitmask];
    }

    /**
     * The pipe interaction-state key for a connected-faces bitmask, choosing the unpowered shape key or
     * its {@code _On} powered twin per {@code energized}.
     *
     * <ul>
     *   <li>mask 0 (node): {@code energized=false} &rarr; {@code "default"} (base block);
     *       {@code energized=true} &rarr; {@code "On"}.</li>
     *   <li>any other mask: {@code energized=false} &rarr; the PascalCase shape key (e.g. {@code "Elbow"});
     *       {@code energized=true} &rarr; the {@code _On} twin (e.g. {@code "Elbow_On"}).</li>
     * </ul>
     *
     * <p>The energized key is always exactly {@link PipePowerStates#poweredOf(String)} applied to the
     * unenergized key, so this stays in lockstep with the H8 flip path. Orientation is NOT part of the
     * returned key: see the {@code Orientation} section of this class. Callers that need the rotation use
     * {@link #resolve}.
     *
     * @param connectedFacesBitmask bit i set means face i connected (OFFSETS order)
     * @param energized whether the network is energized (selects the {@code _On} twin)
     * @return the interaction-state key to feed {@code setBlockInteractionState} / {@link PipePowerStates}
     * @throws IllegalArgumentException if the mask is outside {@code [0,63]}
     */
    public static String stateFor(int connectedFacesBitmask, boolean energized) {
        Shape shape = resolve(connectedFacesBitmask);
        if (connectedFacesBitmask == 0) {
            // The node has no Definition of its own: it is the base block.
            return energized ? ON : DEFAULT;
        }
        return energized ? PipePowerStates.poweredOf(shape.stateKey()) : shape.stateKey();
    }
}
