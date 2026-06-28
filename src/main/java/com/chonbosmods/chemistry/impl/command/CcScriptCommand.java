package com.chonbosmods.chemistry.impl.command;

import com.chonbosmods.chemistry.impl.block.craft.AutoCraftEngine;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScript;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScriptArgs;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Debug command {@code /cc-script}: mint a {@code CC_RecipeScript} card stamped with a chosen
 * {@link RecipeScript} and hand it to the commanding player, so the recipe-script engine
 * ({@link AutoCraftEngine}) can be exercised in-game before the (future) programmer bench exists.
 *
 * <h2>Syntax</h2>
 * <pre>{@code  /cc-script <recipeId[:count]> [recipeId[:count] ...]}</pre>
 * Each token is a recipe id with an optional {@code :count} ({@code count} omitted or {@code <= 0} =
 * infinite). Example: {@code /cc-script ironbar:5 nail} mints a card that crafts 5 iron bars (finite, first)
 * then nails forever (infinite, round-robin).
 *
 * <h2>Threading</h2>
 * This extends {@link AbstractPlayerCommand}, whose {@code execute(...)} body the engine already runs through
 * {@code world.execute(...)} (the {@link World} is the executor). So all the player / inventory work below is
 * on the WorldThread, satisfying the project's off-WorldThread command rule without an explicit wrap. The
 * pure token parsing happens BEFORE that hop (it touches no world state).
 *
 * <h2>Argument capture</h2>
 * The arg framework only groups space-separated tokens into a single list arg when the user wraps them in
 * {@code [a, b, c]} or comma-separates them (see {@code ParserContext}); a {@code withListRequiredArg(STRING)}
 * therefore captures only the FIRST bare token of {@code /cc-script a:5 b c}. So instead we declare no
 * required args, opt into {@link #setAllowsExtraArguments(boolean) extra arguments}, and recover the whole
 * rest-of-line ourselves via {@code CommandUtil.stripCommandName(getInputString()).split("\\s+")} : the same
 * proven pattern the vanilla {@code /notify} command uses for free-form multi-token input.
 */
public final class CcScriptCommand extends AbstractPlayerCommand {

    /** The item id of the recipe-script card (matches {@code Server/Item/Items/ChonbosMods/CC_RecipeScript.json}). */
    public static final String CARD_ITEM_ID = "CC_RecipeScript";

    /** Usage line shown on a parse failure (no translation key: this is a dev-only debug command). */
    private static final String USAGE =
        "Usage: /cc-script <recipeId[:count]> [recipeId[:count] ...]  "
        + "(count omitted or 0 = infinite). Example: /cc-script ironbar:5 nail";

    public CcScriptCommand() {
        super("cc-script", "Give yourself a CC_RecipeScript card stamped with a recipe script (debug)");
        // Declare no required args: we capture the whole rest-of-line ourselves (see class javadoc). Opting into
        // extra arguments stops the framework rejecting the trailing tokens it didn't bind to a declared arg.
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        // Parse FIRST (pure, no world state): on bad args, fail with usage feedback before touching anything.
        // Recover the rest-of-line ourselves (the list arg can't capture bare space-separated tokens : see
        // class javadoc). stripCommandName drops the leading "/cc-script "; an empty tail yields no tokens.
        String rawArgs = CommandUtil.stripCommandName(context.getInputString()).trim();
        List<String> tokens = rawArgs.isEmpty() ? List.of() : List.of(rawArgs.split("\\s+"));
        RecipeScript script;
        try {
            script = RecipeScriptArgs.parse(tokens);
        } catch (RecipeScriptArgs.ParseException ex) {
            context.sendMessage(Message.raw("Bad recipe script: " + ex.getMessage()));
            context.sendMessage(Message.raw(USAGE));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve your player entity."));
            return;
        }

        // Mint a fresh card (qty 1) and stamp it via the canonical AutoCraftEngine.writeScript path (the same
        // seam the future programmer bench will use). withMetadata is copy-on-write: `stamped` carries the
        // CC_RecipeScript metadata the auto-crafters read back via AutoCraftEngine.cardScript.
        ItemStack card = new ItemStack(CARD_ITEM_ID, 1);
        ItemStack stamped = AutoCraftEngine.writeScript(card, script);

        // Give it hotbar-first; the engine's giveItem returns the unplaced remainder when the inventory is
        // full. We don't silently destroy it: report the remainder so the player knows to free a slot.
        ItemStackTransaction transaction = player.giveItem(stamped, ref, store);
        ItemStack remainder = transaction.getRemainder();

        context.sendMessage(Message.raw("Recipe script: " + RecipeScriptArgs.describe(script)));
        if (remainder != null && !remainder.isEmpty()) {
            context.sendMessage(Message.raw(
                "Inventory full: could not give the " + script.entries().size()
                + "-entry recipe script card. Free a slot and retry."));
        } else {
            context.sendMessage(Message.raw(
                "Gave " + script.entries().size() + "-entry recipe script card."));
        }
    }
}
