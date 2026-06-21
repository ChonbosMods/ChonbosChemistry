package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * The full set of configurable {@link Port}s on a machine, and the query "which ports
 * carry channel X in direction Y" that the transport layer asks each tick (design §5.3).
 */
public final class PortConfig {

    public static final BuilderCodec<PortConfig> CODEC = BuilderCodec.builder(PortConfig.class, PortConfig::new)
        .append(new KeyedCodec<>("Ports", new ArrayCodec<>(Port.CODEC, Port[]::new)),
            (o, v) -> o.ports = List.of(v), o -> o.ports.toArray(new Port[0])).add()
        .build();

    private List<Port> ports = List.of();

    private PortConfig() {
    }

    public static PortConfig of(List<Port> ports) {
        PortConfig c = new PortConfig();
        c.ports = List.copyOf(ports);
        return c;
    }

    /** @return the ports whose channel and direction both match. */
    public List<Port> portsFor(PortChannel channel, PortDirection direction) {
        List<Port> matches = new ArrayList<>();
        for (Port p : ports) {
            if (p.channel() == channel && p.direction() == direction) {
                matches.add(p);
            }
        }
        return matches;
    }

    /**
     * The single port on {@code faceIndex} carrying {@code channel}, or {@code null} if none. Under
     * one-port-per-face (design 2026-06-05 §1) a face carries at most one port per channel; this is the
     * face-precise lookup the endpoint collector uses to find the port that faces back at a pipe. If
     * several ports somehow match (legacy data), the first is returned.
     */
    public Port portAt(int faceIndex, PortChannel channel) {
        for (Port p : ports) {
            if (p.faceIndex() == faceIndex && p.channel() == channel) {
                return p;
            }
        }
        return null;
    }

    public List<Port> ports() {
        return ports;
    }

    /**
     * A view of just the ports on the footprint cell at offset {@code (dx,dy,dz)} from the anchor (model
     * orientation), so a multi-cell machine exposes the right ports per cell. The transport and visual
     * layers query a single cell's faces via {@code portAt}/{@code ports()} on this view, unaware of the
     * footprint: {@code WorldMachineLookup} resolves a touched cell to its anchor + offset and calls this.
     * A cell with no configured ports yields an empty config (the basis for dropping inherited-tag pipe
     * arms on portless filler faces). Ports are kept as-is; rotation projection is layered on separately.
     */
    public PortConfig forCell(int dx, int dy, int dz) {
        List<Port> matches = new ArrayList<>();
        for (Port p : ports) {
            if (p.cellX() == dx && p.cellY() == dy && p.cellZ() == dz) {
                matches.add(p);
            }
        }
        return PortConfig.of(matches);
    }

    /**
     * A new {@link PortConfig} with {@code port}'s face configured to exactly that port: every existing
     * port on the same {@code faceIndex} is dropped first (replace-not-append), so a face never ends up
     * carrying two ports under one-port-per-face (design 2026-06-05 §1). Used by the {@code CC_Wrench}
     * to persist a cycled machine face. {@code this} is left unchanged.
     *
     * @param port the new port for its face; a null port returns an unchanged copy.
     */
    public PortConfig withFacePort(Port port) {
        if (port == null) {
            return PortConfig.of(ports);
        }
        List<Port> rebuilt = new ArrayList<>(ports.size() + 1);
        for (Port p : ports) {
            if (p.faceIndex() != port.faceIndex()) {
                rebuilt.add(p);
            }
        }
        rebuilt.add(port);
        return PortConfig.of(rebuilt);
    }
}
