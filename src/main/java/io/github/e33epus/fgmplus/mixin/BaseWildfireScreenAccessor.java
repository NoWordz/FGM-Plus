package io.github.e33epus.fgmplus.mixin;

import com.wildfire.client.gui.screen.BaseWildfireScreen;
import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes BaseWildfireScreen's protected config field to the GUI package so the
 * Shape Studio entry button can hand the displayed avatar's config holder to the
 * studio screen. The field belongs to the FGM mod itself and is never obfuscated.
 */
@Mixin(value = BaseWildfireScreen.class, remap = false)
public interface BaseWildfireScreenAccessor {

	@Accessor(value = "config", remap = false)
	AbstractAvatarConfigHolder fgmplus$getConfig();
}
