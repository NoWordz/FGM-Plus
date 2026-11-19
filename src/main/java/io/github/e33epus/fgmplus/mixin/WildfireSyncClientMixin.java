package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import com.wildfire.common.networking.WildfireSync;
import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Piggybacks the shape payload onto FGM's C2S sync: whenever FGM actually put
 * its own packet on the wire ({@code sendToServer} returned {@code true}), the
 * shape goes along. Gated on ClientPlayNetworking#canSend inside
 * sendToServer, so FGM-only servers simply never receive it.
 */
@Mixin(value = WildfireSync.class, remap = false)
public abstract class WildfireSyncClientMixin {

	@Inject(method = "sendToServer", at = @At("RETURN"), require = 1, remap = false)
	private static void fp$sendShape(Connection connection, AbstractAvatarConfigHolder config,
			CallbackInfoReturnable<Boolean> cir) {
		//false = the server lacks FGM's channel, so nothing went out; staying
		//quiet keeps FGM-only servers untouched
		if(cir.getReturnValueZ() && config instanceof PlayerConfigHolder plr) {
			ShapeSyncPayload.sendToServer(plr);
		}
	}
}
