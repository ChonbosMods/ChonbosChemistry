package com.chonbosmods.chemistry.impl.block.net.item;

/**
 * Resolves the {@link ItemFilter} in effect at a given pipe (2026-06-06 item-channel design §13.4,
 * "Filter stub"). The routing code never holds filters directly: it asks the lookup per pipe so the
 * future tag/type intersection-filter feature can vary the result per junction without touching the
 * pathfinder, extraction, or destination-qualification code.
 *
 * <p>v1 ships only {@link #NONE}, which reports {@link ItemFilter#ALLOW_ALL} for every pipe.
 */
@FunctionalInterface
public interface FilterLookup {

    /**
     * The filter governing the pipe at {@code pipeKey}. Never {@code null}: an unfiltered pipe
     * resolves to {@link ItemFilter#ALLOW_ALL} so callers can chain {@code forPipe(k).admits(..)}.
     *
     * @param pipeKey the packed position key of the pipe
     * @return its filter, never null
     */
    ItemFilter forPipe(long pipeKey);

    /** The v1 lookup: every pipe is unfiltered ({@link ItemFilter#ALLOW_ALL}). */
    FilterLookup NONE = pipeKey -> ItemFilter.ALLOW_ALL;
}
