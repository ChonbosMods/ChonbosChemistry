package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** Hazard/behavior property flags for a compound. */
public final class PropertyFlags {

    public static final BuilderCodec<PropertyFlags> CODEC = BuilderCodec.builder(PropertyFlags.class, PropertyFlags::new)
        .append(new KeyedCodec<>("Corrosive", Codec.BOOLEAN), (o, v) -> o.corrosive = v, o -> o.corrosive).add()
        .append(new KeyedCodec<>("Flammable", Codec.BOOLEAN), (o, v) -> o.flammable = v, o -> o.flammable).add()
        .append(new KeyedCodec<>("Oxidizer", Codec.BOOLEAN), (o, v) -> o.oxidizer = v, o -> o.oxidizer).add()
        .append(new KeyedCodec<>("Volatile", Codec.BOOLEAN), (o, v) -> o.volatileFlag = v, o -> o.volatileFlag).add()
        .append(new KeyedCodec<>("Explosive", Codec.BOOLEAN), (o, v) -> o.explosive = v, o -> o.explosive).add()
        .append(new KeyedCodec<>("WaterSoluble", Codec.BOOLEAN), (o, v) -> o.waterSoluble = v, o -> o.waterSoluble).add()
        .build();

    private boolean corrosive;
    private boolean flammable;
    private boolean oxidizer;
    private boolean volatileFlag;
    private boolean explosive;
    private boolean waterSoluble;

    public boolean corrosive() {
        return corrosive;
    }

    public boolean flammable() {
        return flammable;
    }

    public boolean oxidizer() {
        return oxidizer;
    }

    /** Named {@code volatileFlag} internally since {@code volatile} is a Java keyword. */
    public boolean isVolatile() {
        return volatileFlag;
    }

    public boolean explosive() {
        return explosive;
    }

    public boolean waterSoluble() {
        return waterSoluble;
    }
}
