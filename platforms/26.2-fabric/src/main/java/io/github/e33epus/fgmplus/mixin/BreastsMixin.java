package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.config.value.ConfigKey;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Widens FGM's slider bounds at the single source every consumer shares: the
 * {@code ConfigKey} objects created in {@code Breasts}' clinit. On FGM 5.0.0 the
 * key record is captured at birth by every consumer — the save codec (disk clamp),
 * the sync stream codec (network clamp), {@code ConfigValue#update}'s validate gate
 * (live writes) and the sliders ({@code WildfireSlider.Builder#forConfig} reads the
 * validator's ConfigRange) — so redefining the static fields after the fact is
 * useless (the old FGM5-Beta lesson); the create call itself must be intercepted.
 *
 * <p>Breasts' clinit makes five create calls, in source order: OFFSET_X (0,-1,1),
 * OFFSET_Y (0,-1,1), OFFSET_Z (0,-1,0), BUST_SIZE (0.6,0,0.8), CLEAVAGE (0,0,0.1).
 * OFFSET_X and OFFSET_Y have identical argument fingerprints, so the Y redirect is
 * ordinal-based with an argument guard as a second line of defense (if upstream
 * reorders, the guard still keeps the arguments sane; ordinal dispatch then just
 * picks the wrong twin, degrading to a widened X — cosmetics, not corruption).</p>
 *
 * <p>BUST_SIZE is the core path (require=1); the offset widenings are conveniences
 * and degrade to FGM's stock bounds (require=0).</p>
 */
@Mixin(value = com.wildfire.common.entities.Breasts.class, remap = false)
public abstract class BreastsMixin {

	@Redirect(method = "<clinit>", require = 1, remap = false,
			at = @At(value = "INVOKE", remap = false, ordinal = 3,
					target = "Lcom/wildfire/common/config/value/ConfigKey;create(FFF)Lcom/wildfire/common/config/value/ConfigKey;"))
	private static ConfigKey<Float> fgmplus$widenBustSize(float defaultValue, float minInclusive, float maxInclusive) {
		//fingerprint guard: (0.6, 0, 0.8) is BUST_SIZE and nothing else
		if(defaultValue == 0.6F && minInclusive == 0.0F && maxInclusive == 0.8F) {
			return ConfigKey.create(defaultValue, minInclusive, (float) FgmPlusConfig.bustSizeMax);
		}
		return ConfigKey.create(defaultValue, minInclusive, maxInclusive);
	}

	@Redirect(method = "<clinit>", require = 0, remap = false,
			at = @At(value = "INVOKE", remap = false, ordinal = 1,
					target = "Lcom/wildfire/common/config/value/ConfigKey;create(FFF)Lcom/wildfire/common/config/value/ConfigKey;"))
	private static ConfigKey<Float> fgmplus$widenOffsetY(float defaultValue, float minInclusive, float maxInclusive) {
		//(0, -1, 1): OFFSET_X and OFFSET_Y are indistinguishable by args; ordinal 1
		//is OFFSET_Y while the source order holds
		if(defaultValue == 0.0F && minInclusive == -1.0F && maxInclusive == 1.0F) {
			return ConfigKey.create(defaultValue, (float) FgmPlusConfig.bustOffsetYMin, maxInclusive);
		}
		return ConfigKey.create(defaultValue, minInclusive, maxInclusive);
	}

	@Redirect(method = "<clinit>", require = 0, remap = false,
			at = @At(value = "INVOKE", remap = false, ordinal = 2,
					target = "Lcom/wildfire/common/config/value/ConfigKey;create(FFF)Lcom/wildfire/common/config/value/ConfigKey;"))
	private static ConfigKey<Float> fgmplus$widenOffsetZ(float defaultValue, float minInclusive, float maxInclusive) {
		//fingerprint guard: (0, -1, 0) is OFFSET_Z and nothing else
		if(defaultValue == 0.0F && minInclusive == -1.0F && maxInclusive == 0.0F) {
			return ConfigKey.create(defaultValue, (float) FgmPlusConfig.bustOffsetZMin, (float) FgmPlusConfig.bustOffsetZMax);
		}
		return ConfigKey.create(defaultValue, minInclusive, maxInclusive);
	}
}
