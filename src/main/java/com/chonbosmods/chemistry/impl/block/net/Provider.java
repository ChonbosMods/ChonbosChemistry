package com.chonbosmods.chemistry.impl.block.net;

/**
 * Test-seam for something that supplies resource into a {@link Network}. Per-provider rate limits
 * are encapsulated here: {@link #extract} already caps at the provider's own throughput.
 */
public interface Provider {

    /** Resource this provider supplies; {@code null} for POWER. */
    String resourceId();

    /**
     * Pull up to {@code max} out of this provider.
     *
     * @return amount actually provided; {@code simulate == true} means no mutation.
     */
    long extract(long max, boolean simulate);
}
