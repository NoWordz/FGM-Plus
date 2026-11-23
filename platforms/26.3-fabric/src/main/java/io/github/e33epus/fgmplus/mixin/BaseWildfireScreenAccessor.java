package io.github.e33epus.fgmplus.mixin;

import com.wildfire.client.gui.screen.BaseWildfireScreen;
import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes BaseWildfireScreen's protected config holder to the mixin package so
 * the Shape Studio entry button can pass the displayed avatar's config (and thus
 * its UUID) through. On 5.0.0 the screen holds an AbstractAvatarConfigHolder —
 * the 1.21.11 playerUUID field no longer exists; the uuid lives on the holder
 * (config.uuid).
 */
@Mixin(value = BaseWildfireScreen.class, remap = false)
public interface BaseWildfireScreenAccessor {

	@Accessor(value = "config", remap = false)
	AbstractAvatarConfigHolder fgmplus$getConfig();
}
