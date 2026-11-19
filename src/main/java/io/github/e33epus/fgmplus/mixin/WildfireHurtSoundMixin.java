package io.github.e33epus.fgmplus.mixin;

import com.wildfire.api.Gender;
import com.wildfire.client.ClientHelper;
import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import io.github.e33epus.fgmplus.sound.HurtSoundManager;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Swaps FGM's female/other hurt sound for the Shape Studio's custom event when
 * user-provided oggs are present AND loaded. On 26.2 the sound resolves inside
 * AbstractAvatarConfigHolder#tryPlayHurtSound through
 * ClientHelper#hurtSound(Gender) — the single seam every gender routes
 * through. Returns the original Holder untouched (including nulls for genders
 * FGM has no sound for), so FGM's own null-check keeps working.
 */
@Mixin(value = AbstractAvatarConfigHolder.class, remap = false)
public abstract class WildfireHurtSoundMixin {

	@Redirect(method = "tryPlayHurtSound", require = 1, remap = false,
			at = @At(value = "INVOKE",
					target = "Lcom/wildfire/client/ClientHelper;hurtSound(Lcom/wildfire/api/Gender;)Lnet/minecraft/core/Holder;",
					remap = false))
	private Holder<SoundEvent> fgmplus$redirectHurtSound(ClientHelper helper, Gender gender) {
		return HurtSoundManager.resolveHurtSound(helper.hurtSound(gender));
	}
}
