package io.izzel.arclight.common.mixin.core.server.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(net.minecraft.server.commands.WeatherCommand.class)
public abstract class WeatherCommandMixin {

    /**
     * Vanilla hardcodes MinecraftServer#overworld(), so /weather always mutates the main world no matter which
     * world the command was executed from. In a Multiverse server that makes the weather of every secondary
     * world impossible to control, and it is what made the reported "uncontrollable rain" happen. CraftBukkit
     * fixed this as SPIGOT-7680 and Sponge ships an equivalent mixin; the 1.21.1 Spigot tree Arclight builds on
     * does not contain it, so the level of the command source is used instead.
     */
    @Overwrite
    private static int setClear(CommandSourceStack source, int i) {
        source.getLevel().setWeatherParameters(duration(source, i, ServerLevel.RAIN_DELAY), 0, false, false);
        source.sendSuccess(() -> Component.translatable("commands.weather.set.clear"), true);
        return i;
    }

    @Overwrite
    private static int setRain(CommandSourceStack source, int i) {
        source.getLevel().setWeatherParameters(0, duration(source, i, ServerLevel.RAIN_DURATION), true, false);
        source.sendSuccess(() -> Component.translatable("commands.weather.set.rain"), true);
        return i;
    }

    @Overwrite
    private static int setThunder(CommandSourceStack source, int i) {
        source.getLevel().setWeatherParameters(0, duration(source, i, ServerLevel.THUNDER_DURATION), true, true);
        source.sendSuccess(() -> Component.translatable("commands.weather.set.thunder"), true);
        return i;
    }

    private static int duration(CommandSourceStack source, int i, net.minecraft.util.valueproviders.IntProvider provider) {
        return i == -1 ? provider.sample(source.getLevel().getRandom()) : i;
    }
}
