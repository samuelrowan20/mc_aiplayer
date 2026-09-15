package io.github.zoyluo.aibot.autonomy;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.util.Identifier;

/** One selected vanilla recipe. The ordinary screen performs ingredient transfer and consumption. */
public final class MechanicalCrafting {
    private MechanicalCrafting() {}

    public static AutonomyState.Result craft(AIPlayerEntity bot, String recipeId) {
        if (!(bot.currentScreenHandler instanceof AbstractCraftingScreenHandler screen)
                || !screen.canUse(bot)) {
            return AutonomyState.Result.failure("crafting_screen_required", "Open a player or table crafting screen first.");
        }
        var entry = bot.getServer().getRecipeManager().get(
                RegistryKey.of(RegistryKeys.RECIPE, Identifier.of(recipeId))).orElse(null);
        if (entry == null || !(entry.value() instanceof CraftingRecipe)) {
            return AutonomyState.Result.failure("recipe_unavailable", "No crafting recipe with the selected identifier.");
        }
        if (!screen.getCursorStack().isEmpty() || screen.getInputSlots().stream().anyMatch(slot -> slot.hasStack())
                || screen.getOutputSlot().hasStack()) {
            return AutonomyState.Result.failure("crafting_grid_not_empty", "Empty the cursor and crafting grid through slot clicks first.");
        }
        if (entry.value() instanceof ShapedRecipe shaped
                && (shaped.getWidth() > screen.getWidth() || shaped.getHeight() > screen.getHeight())) {
            return AutonomyState.Result.failure("grid_too_small", "Selected recipe requires a "
                    + shaped.getWidth() + "x" + shaped.getHeight() + " crafting grid.");
        }
        var placement = entry.value().getIngredientPlacement();
        if (placement.hasNoPlacement()) return AutonomyState.Result.failure("manual_grid_required",
                "This recipe has no automatic ingredient placement; use ordinary crafting grid slot clicks.");
        if (placement.getIngredients().size() > screen.getWidth() * screen.getHeight()) {
            return AutonomyState.Result.failure("grid_too_small", "Recipe requires "
                    + placement.getIngredients().size() + " ingredient slots.");
        }
        RecipeFinder finder = new RecipeFinder();
        bot.getInventory().main.forEach(finder::addInputIfUsable);
        if (!finder.isCraftable(entry.value(), null)) {
            String requirements = placement.getIngredients().stream().map(ingredient ->
                    ingredient.getMatchingItems().stream().limit(12)
                            .map(item -> Registries.ITEM.getId(item.value()).toString())
                            .collect(java.util.stream.Collectors.joining("|", "[", "]")))
                    .collect(java.util.stream.Collectors.joining(", "));
            return AutonomyState.Result.failure("missing_ingredients",
                    "Present usable inventory cannot satisfy one item per ingredient group: " + requirements);
        }
        // Both flags false: exactly one fill, survival ingredients only. Never grants ingredients.
        screen.fillInputSlots(false, false, entry, bot.getServerWorld(), bot.getInventory());
        if (!screen.getOutputSlot().hasStack()) {
            return AutonomyState.Result.failure("missing_recipe_requirements",
                    "Required ingredients are absent from inventory or this recipe does not fit the current grid.");
        }
        var output = screen.quickMove(bot, screen.getOutputSlot().id);
        screen.sendContentUpdates();
        if (output.isEmpty()) return AutonomyState.Result.failure("inventory_full", "Output remains in the crafting screen; no room in inventory.");
        return AutonomyState.Result.success("Crafted one selected recipe operation.");
    }
}
