package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.config.value.ConfigKey;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same widening strategy as {@link BreastsMixin}, applied to the physics keys
 * created in {@code Breasts$Physics}' clinit: BOUNCE_MULTIPLIER create(0.333, 0, 0.5)
 * and FLOPPINESS create(0.75, 0.25, 1). The two calls have distinct argument
 * fingerprints, so no ordinal dispatch is needed here. Convenience widening —
 * require=0, a miss degrades to FGM's stock bounds.
 */
@Mixin(targets = "com.wildfire.common.entities.Breasts$Physics", remap = false)
public abstract class BreastsPhysicsMixin {

	@Redirect(method = "<clinit>", require = 0, remap = false,
			at = @At(value = "INVOKE", remap = false,
					target = "Lcom/wildfire/common/config/value/ConfigKey;create(FFF)Lcom/wildfire/common/config/value/ConfigKey;"))
	private static ConfigKey<Float> fgmplus$widenPhysicsMax(float defaultValue, float minInclusive, float maxInclusive) {
		if(defaultValue == 0.333F && minInclusive == 0.0F && maxInclusive == 0.5F) {
			return ConfigKey.create(defaultValue, minInclusive, (float) FgmPlusConfig.bounceMultiplierMax);
		}
		if(defaultValue == 0.75F && minInclusive == 0.25F && maxInclusive == 1.0F) {
			return ConfigKey.create(defaultValue, minInclusive, (float) FgmPlusConfig.floppyMultiplierMax);
		}
		return ConfigKey.create(defaultValue, minInclusive, maxInclusive);
	}
}
