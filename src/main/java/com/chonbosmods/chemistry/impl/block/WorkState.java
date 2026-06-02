package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Recipe progress modelled on the built-in furnace's semantics: accumulate elapsed {@code dt}
 * (seconds) into {@code progress} while inputs are present, and complete one unit when
 * {@code progress} exceeds the recipe {@code duration}, carrying the remainder forward.
 *
 * <p>Deliberately diverges from the furnace (which resets on input loss): when inputs run dry,
 * {@code progress} is <em>frozen</em> (retained) so the operation resumes cleanly (run-dry
 * behaviour, plan rule 2).
 *
 * <p>Mutated each tick, so it exposes a public no-arg constructor; the codec reuses it.
 */
public final class WorkState {

    public static final BuilderCodec<WorkState> CODEC = BuilderCodec.builder(WorkState.class, WorkState::new)
        .append(new KeyedCodec<>("Progress", Codec.FLOAT), (o, v) -> o.progress = v, o -> o.progress).add()
        .append(new KeyedCodec<>("Active", Codec.BOOLEAN), (o, v) -> o.active = v, o -> o.active).add()
        .build();

    private float progress;
    private boolean active;

    public WorkState() {
    }

    /**
     * Advance the operation by {@code dt} seconds against a recipe of length {@code duration}.
     *
     * <ul>
     *   <li>If {@code !hasInputs}: marks inactive and leaves {@code progress} untouched (run-dry
     *       freeze), returns {@code false}.</li>
     *   <li>Otherwise: marks active and accumulates {@code dt} into {@code progress}. If the result
     *       exceeds {@code duration} (and {@code duration > 0}), subtracts {@code duration} (retaining
     *       the remainder) and returns {@code true} to signal one completed unit.</li>
     *   <li>Edge: a non-positive {@code duration} never completes (returns {@code false}) to avoid
     *       divide/loop issues, but progress is still accumulated and the state still goes active.</li>
     * </ul>
     *
     * <p>Completes at most one unit per call: a {@code dt} larger than {@code duration} does not
     * drain multiple completions, so the caller is expected to keep {@code dt} small relative to
     * {@code duration}.
     *
     * @return {@code true} iff one unit completed this call.
     */
    public boolean advance(float dt, float duration, boolean hasInputs) {
        if (!hasInputs) {
            active = false;
            return false;
        }
        active = true;
        progress += dt;
        if (duration > 0 && progress > duration) {
            progress -= duration;
            return true;
        }
        return false;
    }

    public float progress() {
        return progress;
    }

    public boolean active() {
        return active;
    }
}
