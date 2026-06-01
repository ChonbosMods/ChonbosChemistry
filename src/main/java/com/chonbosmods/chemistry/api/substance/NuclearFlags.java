package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** Nuclear-engineering flags for an isotope. */
public final class NuclearFlags {

    public static final BuilderCodec<NuclearFlags> CODEC = BuilderCodec.builder(NuclearFlags.class, NuclearFlags::new)
        .append(new KeyedCodec<>("Fissile", Codec.BOOLEAN), (o, v) -> o.fissile = v, o -> o.fissile).add()
        .append(new KeyedCodec<>("Fertile", Codec.BOOLEAN), (o, v) -> o.fertile = v, o -> o.fertile).add()
        .append(new KeyedCodec<>("Fusionable", Codec.BOOLEAN), (o, v) -> o.fusionable = v, o -> o.fusionable).add()
        .build();

    private boolean fissile;
    private boolean fertile;
    private boolean fusionable;

    public boolean fissile() {
        return fissile;
    }

    public boolean fertile() {
        return fertile;
    }

    public boolean fusionable() {
        return fusionable;
    }
}
