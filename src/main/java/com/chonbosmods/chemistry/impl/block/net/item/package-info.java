/**
 * The discrete ITEM transport subsystem (2026-06-06 item-channel design §13.4): the pull-driven,
 * nearest-first routing of individual item stacks over {@code CC_ItemPipe} against vanilla storage
 * containers. This is deliberately separate from the fungible buffer plumbing in the parent net
 * package: items carry identity (type + count + opaque metadata) and travel as in-transit stacks
 * with a re-route ladder, where energy and resources are interchangeable amounts balanced into
 * shared buffers. The two subsystems share only network discovery and caching; transport logic does
 * not cross over.
 */
package com.chonbosmods.chemistry.impl.block.net.item;
