package com.chonbosmods.chemistry.api.registry;

/**
 * Static access point for the live {@link SubstanceRegistry}.
 *
 * <p>The implementation sets the registry in the plugin's {@code setup()} and clears it in
 * {@code shutdown()}, so a hot-reload swaps it cleanly rather than leaving stale state (design
 * §8.1). Reads native, like Hytale's own static asset access.
 */
public final class Chemistry {

    private static volatile SubstanceRegistry registry;

    private Chemistry() {
    }

    /**
     * @throws IllegalStateException if the registry is not yet initialized (called before setup or
     *     after shutdown).
     */
    public static SubstanceRegistry substances() {
        SubstanceRegistry current = registry;
        if (current == null) {
            throw new IllegalStateException("SubstanceRegistry is not initialized (Chonbo's Chemistry not set up, or already shut down)");
        }
        return current;
    }

    /** Installs the registry. Called by the implementation during plugin setup. */
    public static void set(SubstanceRegistry substanceRegistry) {
        registry = substanceRegistry;
    }

    /** Clears the registry. Called by the implementation during plugin shutdown. */
    public static void clear() {
        registry = null;
    }
}
