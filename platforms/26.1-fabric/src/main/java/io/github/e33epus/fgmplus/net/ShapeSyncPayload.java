package io.github.e33epus.fgmplus.net;

import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.shape.ShapeCache;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;

/**
 * Standalone shape-sync channel ({@code fgmplus:shape_sync}), used in both directions.
 *
 * <p>Shapes live in the shared {@link ShapeCache}; the client piggybacks its C2S send onto
 * FGM's own sync (WildfireSyncClientMixin) and the server broadcasts next to FGM's S2C syncs
 * (WildfireSyncServerMixin) plus its own post-receive rebroadcast. Peers without this mod
 * never receive it (each send is gated on {@code canSend}), so mixed-version setups degrade
 * to default shapes instead of desyncing.</p>
 */
public record ShapeSyncPayload(UUID uuid, ShapeData shape) implements CustomPacketPayload {

	public static final Type<ShapeSyncPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FgmPlusMod.MODID, "shape_sync"));

	public static final StreamCodec<ByteBuf, ShapeData> SHAPE_CODEC = StreamCodec.of(
			(buf, shape) -> shape.write(buf),
			ShapeData::read
	);

	public static final StreamCodec<ByteBuf, ShapeSyncPayload> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, ShapeSyncPayload::uuid,
			SHAPE_CODEC, ShapeSyncPayload::shape,
			ShapeSyncPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	/** Applies a synced shape to the shared cache; used by the server and client receivers. */
	public static void apply(UUID uuid, ShapeData shape) {
		ShapeCache.put(uuid, shape == null ? new ShapeData() : shape);
	}

	/** Sends the local player's shape to the server; mirrors FGM's C2S sync cadence. */
	@Environment(EnvType.CLIENT)
	public static void sendToServer(UUID uuid) {
		if(!ClientPlayNetworking.canSend(ID)) return;
		ClientPlayNetworking.send(new ShapeSyncPayload(uuid, ShapeCache.getOrCreate(uuid).copy()));
	}

	/** Server: sends one player's shape to a single client, when that client accepts the payload. */
	public static void sendTo(ServerPlayer sendTo, UUID uuid) {
		if(!ServerPlayNetworking.canSend(sendTo, ID)) return;
		ServerPlayNetworking.send(sendTo, new ShapeSyncPayload(uuid, ShapeCache.getOrCreate(uuid).copy()));
	}

	/**
	 * Server: sends one player's shape to everyone currently tracking them, skipping the
	 * tracked player itself and (when set) an excluded recipient (FGM's sendToAllClients
	 * exception semantics).
	 */
	public static void broadcast(Avatar toSync, UUID uuid, ServerPlayer except) {
		PlayerLookup.tracking(toSync).stream()
				.filter(player -> !player.equals(toSync) && !player.equals(except))
				.forEach(player -> sendTo(player, uuid));
	}
}
