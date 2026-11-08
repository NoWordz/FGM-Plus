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
 * Piggybacks the shape payload onto FGM's C2S sync: whenever FGM actually sent
 * its own packet (sendToServer returns true, i.e. the server accepts the payload)
 * for the local player's config, the shape goes along. FGM's client tick loop
 * (WildfireClientEventHandler#onClientTick) calls this every 5 ticks while
 * needsSync is set and clears the flag on success, so the shape sync cadence
 * matches FGM's own exactly. The canSend gate inside ShapeSyncPayload.sendToServer
 * keeps FGM-only servers out.
 */
@Mixin(value = WildfireSync.class, remap = false)
public abstract class WildfireSyncClientMixin {

	@Inject(method = "sendToServer(Lnet/minecraft/network/Connection;Lcom/wildfire/common/entities/avatars/AbstractAvatarConfigHolder;)Z",
			at = @At("TAIL"), require = 1, remap = false)
	private static void fp$sendShape(Connection connection, AbstractAvatarConfigHolder config, CallbackInfoReturnable<Boolean> cir) {
		if(Boolean.TRUE.equals(cir.getReturnValue()) && config instanceof PlayerConfigHolder) {
			ShapeSyncPayload.sendToServer(config.uuid);
		}
	}
}
