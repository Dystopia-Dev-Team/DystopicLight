package io.izzel.arclight.common.mixin.compat.cobblemon;

import io.izzel.arclight.common.mod.ArclightConstants;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity")
@LoadIfMod(modid = ModIds.COBBLEMON, condition = LoadIfMod.ModCondition.PRESENT)
public class PokemonEntityMixin_Cobblemon {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");
    private static final float MAX_DELTA = 0.25F;

    @Unique
    private int arclight$lastTick = ArclightConstants.currentTick - 1;

    @ModifyArg(method = {"tick", "method_5773"}, require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/api/scheduling/SchedulingTracker;update(F)V"))
    private float arclight$useRealTime(float delta) {
        int currentTick = ArclightConstants.currentTick;
        int elapsedTicks = currentTick - this.arclight$lastTick;
        if (elapsedTicks < 1) {
            elapsedTicks = 1;
        }
        this.arclight$lastTick = currentTick;
        float realDelta = Math.min(elapsedTicks * (1f / 20f), MAX_DELTA);
        return realDelta;
    }
}