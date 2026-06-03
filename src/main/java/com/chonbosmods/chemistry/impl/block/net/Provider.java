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
     * <p>Losslessness contract relied on by {@link NetworkTransfer}: a {@code simulate == true} call
     * must faithfully predict the amount a subsequent {@code simulate == false} (commit) call with
     * that amount will move. Simulate is a side-effect-free dry run whose result the commit honors.
     *
     * @return amount actually provided; {@code simulate == true} means no mutation.
     */
    long extract(long max, boolean simulate);
}
