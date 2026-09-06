package io.izzel.arclight.common.mixin.bukkit;

import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.v.CraftRegistry;
import org.bukkit.craftbukkit.v.CraftSound;
import org.bukkit.craftbukkit.v.util.CraftNamespacedKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = CraftSound.class, remap = false)
public abstract class CraftSoundMixin {

    /**
     * @author Binaris
     * @reason Hybrid servers can have mod/custom sounds registered in the minecraft
     * sound registry that have no equivalent in the vanilla Bukkit Sound enum.
     * Fall back to a generic sound instead of throwing, so plugins like ItemsAdder
     * do not fail while handling game events carrying those sounds.
     */
    @Overwrite
    public static Sound minecraftToBukkit(SoundEvent minecraft) {
        if (minecraft == null) {
            return Sound.UI_BUTTON_CLICK;
        }
        Registry<SoundEvent> registry = CraftRegistry.getMinecraftRegistry(Registries.SOUND_EVENT);
        ResourceLocation location = registry.getResourceKey(minecraft).map(ResourceKey::location).orElse(null);
        if (location == null) {
            ArclightServer.LOGGER.debug("No registry key for sound '{}', returning generic fallback sound", minecraft);
            return Sound.UI_BUTTON_CLICK;
        }
        NamespacedKey key = CraftNamespacedKey.fromMinecraft(location);
        Sound bukkit = org.bukkit.Registry.SOUNDS.get(key);
        if (bukkit == null) {
            ArclightServer.LOGGER.debug("No vanilla Bukkit sound for '{}', returning generic fallback sound", key);
            return Sound.UI_BUTTON_CLICK;
        }
        return bukkit;
    }
}
