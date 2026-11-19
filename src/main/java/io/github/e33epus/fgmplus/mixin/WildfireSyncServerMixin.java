package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import com.wildfire.common.networking.WildfireSync;
import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Piggybacks the shape payload onto FGM's S2C syncs so every client that FGM
 * itself syncs to also receives the shape. Each send is individually gated on
 * ServerPlayNetworking#canSend, so peers without this mod are skipped. Only
 * the 3-arg sendToAllClients carries the exclusion argument; the 2-arg
 * overload delegates to it, so one injection site covers both.
 */
@Mixin(value = WildfireSync.class, remap = false)
public abstract class WildfireSyncServerMixin {

	@Inject(method = "sendToAllClients(Lnet/minecraft/world/entity/Avatar;Lcom/wildfire/common/entities/avatars/AbstractAvatarConfigHolder;Lnet/minecraft/server/level/ServerPlayer;)V",
			at = @At("TAIL"), require = 1, remap = false)
	private static void fp$broadcast(Avatar toSync, AbstractAvatarConfigHolder config, ServerPlayer except, CallbackInfo ci) {
		//mannequin holders have no per-player shape; skip silently
		if(config instanceof PlayerConfigHolder player) {
			ShapeSyncPayload.broadcast(toSync, player, except);
		}
	}

	@Inject(method = "sendToClient", at = @At("TAIL"), require = 1, remap = false)
	private static void fp$sendOne(ServerPlayer sendTo, AbstractAvatarConfigHolder toSync, CallbackInfo ci) {
		if(toSync instanceof PlayerConfigHolder player) {
			ShapeSyncPayload.sendTo(sendTo, player);
		}
	}
}
