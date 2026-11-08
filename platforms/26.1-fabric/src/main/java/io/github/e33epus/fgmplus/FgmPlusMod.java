package io.github.e33epus.fgmplus;

import com.mojang.logging.LogUtils;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;

/**
 * Common entrypoint: registers the shape-sync payload on both sides and the dedicated-server
 * receiver. The shape lands in the shared {@link io.github.e33epus.fgmplus.shape.ShapeCache} and
 * is immediately rebroadcast to everyone tracking the sender, mirroring how FGM itself
 * rebroadcasts its C2S sync ({@code ServerboundSyncPacket#handle}).
 */
public class FgmPlusMod implements ModInitializer {

	public static final String MODID = "fgmplus";
	public static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onInitialize() {
		FgmPlusConfig.setup();

		// each payload type must be registered on both directions regardless of side
		//(26.1 fabric-api renamed the old playC2S/playS2C accessors)
		PayloadTypeRegistry.serverboundPlay().register(ShapeSyncPayload.ID, ShapeSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShapeSyncPayload.ID, ShapeSyncPayload.CODEC);

		ServerPlayConnectionEvents.INIT.register((handler, server) ->
				ServerPlayNetworking.registerReceiver(handler, ShapeSyncPayload.ID, (payload, context) -> {
					//Trust the connection, not the payload: FGM's own C2S handler keys everything
					//on the authenticated sender the same way, so a modified client cannot spoof
					//other players
					UUID sender = context.player().getUUID();
					ShapeSyncPayload.apply(sender, payload.shape());
					//Rebroadcast to everyone tracking the sender (except the sender itself),
					//matching FGM's own post-sync broadcast semantics
					ShapeSyncPayload.broadcast(context.player(), sender, null);
				}));
	}
}
