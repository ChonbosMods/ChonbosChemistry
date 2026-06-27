package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

/**
 * Test helper: builds an {@link ItemStack} via its CODEC (decode-from-BSON) rather than the
 * {@code new ItemStack(String,int)} constructor, which resolves the item against the global AssetStore to
 * seed durability (unloaded in the unit-test JVM → NPE). Mirrors the {@code stack(...)} helper the craft
 * codec tests already use.
 */
final class RecipeCardOpsTestStacks {

    private RecipeCardOpsTestStacks() {
    }

    static ItemStack stack(String id, int quantity) {
        BsonDocument doc = new BsonDocument()
            .append("Id", new BsonString(id))
            .append("Quantity", new BsonInt32(quantity));
        return ItemStack.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
    }
}
