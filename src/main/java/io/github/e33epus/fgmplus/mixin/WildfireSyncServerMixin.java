package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import com.wildfire.common.networking.WildfireSync;
import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Piggybacks the shape payload onto FGM's S2C syncs so remote players' shapes
 * reach every client that FGM itself syncs to. The two-arg sendToAllClients
 * overload delegates to the three-arg one, so hooking it covers both call sites
 * (post-sync rebroadcast and begin-tracking); each send is individually gated on
 * ServerPlayNetworking#canSend, so peers without this mod are skipped.
 */
@Mixin(value = WildfireSync.class, remap = false)
public abstract class WildfireSyncServerMixin {

	@Inject(method = "sendToAllClients(Lnet/minecraft/world/entity/Avatar;Lcom/wildfire/common/entities/avatars/AbstractAvatarConfigHolder;Lnet/minecraft/server/level/ServerPlayer;)V",
			at = @At("TAIL"), require = 1, remap = false)
	private static void fp$broadcast(Avatar toSync, AbstractAvatarConfigHolder config, ServerPlayer except, CallbackInfo ci) {
		// Player configs only: mannequins ride the same WildfireSync chain and have
		// no shape of their own, so broadcasting for them would just spawn cache
		// entries per mannequin uuid on every connected server.
		if(config instanceof com.wildfire.common.entities.players.PlayerConfigHolder) {
			ShapeSyncPayload.broadcast(toSync, config.uuid, except);
		}
	}

	@Inject(method = "sendToClient(Lnet/minecraft/server/level/ServerPlayer;Lcom/wildfire/common/entities/avatars/AbstractAvatarConfigHolder;)V",
			at = @At("TAIL"), require = 1, remap = false)
	private static void fp$sendOne(ServerPlayer sendTo, AbstractAvatarConfigHolder toSync, CallbackInfo ci) {
		if(toSync instanceof com.wildfire.common.entities.players.PlayerConfigHolder) {
			ShapeSyncPayload.sendTo(sendTo, toSync.uuid);
		}
	}
}
