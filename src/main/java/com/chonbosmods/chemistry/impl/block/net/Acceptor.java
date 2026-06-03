package com.chonbosmods.chemistry.impl.block.net;

/**
 * Test-seam for something that receives resource out of a {@link Network}. Per-acceptor rate limits
 * are encapsulated here: {@link #capacityFor}/{@link #insert} already cap at the acceptor's own
 * throughput.
 */
public interface Acceptor {

    /** Room this acceptor can take of the given resource right now (0 if it cannot accept it). */
    long capacityFor(String resourceId);

    /**
     * Insert up to {@code amount} of {@code resourceId}.
     *
     * <p>Losslessness contract relied on by {@link NetworkTransfer}: a {@code simulate == true} call
     * must faithfully predict the amount a subsequent {@code simulate == false} (commit) call with
     * that amount will move. Simulate is a side-effect-free dry run whose result the commit honors.
     *
     * @return amount actually accepted; {@code simulate == true} means no mutation.
     */
    long insert(String resourceId, long amount, boolean simulate);
}
