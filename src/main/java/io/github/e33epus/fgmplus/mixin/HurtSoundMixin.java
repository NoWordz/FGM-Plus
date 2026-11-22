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
 * Redirects the per-gender hurt sound lookup inside FGM's
 * AbstractAvatarConfigHolder#tryPlayHurtSound (the 5.0.0 successor of the old
 * WildfireEventHandler#onEntityHurt seam — the whole client hurt-sound path now
 * funnels through here, called from LivingEntityMixin at the playSound point of
 * handleDamageEvent) so that when the user provides custom oggs in
 * config/fgmplus/sounds/ (and the sounds.json entry map is live), FGM's female
 * hurt sound is swapped for fgmplus:custom_hurt. Everything else in
 * tryPlayHurtSound (the sounds().hurt() gate, volume/pitch, the pitch variation)
 * is left untouched.
 *
 * <p>The handler receives the receiver instance (ClientHelper.INSTANCE) and must
 * preserve the original's null for non-FEMALE/OTHER genders so FGM's own
 * null-check keeps working.</p>
 */
@Mixin(value = AbstractAvatarConfigHolder.class, remap = false)
public abstract class HurtSoundMixin {

	@Redirect(method = "tryPlayHurtSound", require = 1, remap = false,
			at = @At(value = "INVOKE",
					target = "Lcom/wildfire/client/ClientHelper;hurtSound(Lcom/wildfire/api/Gender;)Lnet/minecraft/core/Holder;",
					remap = false))
	private Holder<SoundEvent> fgmplus$redirectHurtSound(ClientHelper helper, Gender gender) {
		Holder<SoundEvent> original = helper.hurtSound(gender);
		// null (non-FEMALE/OTHER gender) must stay null so FGM's own null-check keeps working
		return HurtSoundManager.resolveHurtSound(original);
	}
}
