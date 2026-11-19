package io.github.e33epus.fgmplus.net;

import com.wildfire.api.client.WildfireClientAPI;
import com.wildfire.api.server.WildfireServerAPI;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeHolder;
import io.netty.buffer.ByteBuf;
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
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** FGM Plus 的逐玩家胸部造型同步包。没有附属包的客户端会被能力检查跳过。 */
public record ShapeSyncPayload(UUID uuid, ShapeData shape) implements CustomPacketPayload {
    public static final Type<ShapeSyncPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FgmPlusMod.MODID, "shape_sync"));

    public static final StreamCodec<ByteBuf, ShapeData> SHAPE_CODEC = StreamCodec.of(
        (buf, value) -> value.write(buf), ShapeData::read);
    public static final StreamCodec<ByteBuf, ShapeSyncPayload> CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, ShapeSyncPayload::uuid,
        SHAPE_CODEC, ShapeSyncPayload::shape,
        ShapeSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public static void applyClient(UUID uuid, ShapeData shape) {
        PlayerConfigHolder player = WildfireClientAPI.players().getOrCreate(uuid);
        ((ShapeHolder) player).fgmplus$setShape(shape == null ? new ShapeData() : shape);
    }

    public static void applyServer(UUID uuid, ShapeData shape) {
        PlayerConfigHolder player = WildfireServerAPI.players().getOrCreate(uuid);
        ((ShapeHolder) player).fgmplus$setShape(shape == null ? new ShapeData() : shape);
    }

    public static PlayerConfigHolder serverPlayer(UUID uuid) {
        return WildfireServerAPI.players().get(uuid);
    }

    @Environment(EnvType.CLIENT)
    public static void sendToServer(PlayerConfigHolder player) {
        if (ClientPlayNetworking.canSend(ID)) {
            ClientPlayNetworking.send(new ShapeSyncPayload(player.uuid,
                ((ShapeHolder) player).fgmplus$getShape().copy()));
        }
    }

    public static void sendTo(ServerPlayer target, PlayerConfigHolder player) {
        if (ServerPlayNetworking.canSend(target, ID)) {
            ServerPlayNetworking.send(target, new ShapeSyncPayload(player.uuid,
                ((ShapeHolder) player).fgmplus$getShape().copy()));
        }
    }

    /**
     * Relays one player's shape to everyone FGM tracked-syncs to. {@code except}
     * mirrors {@code WildfireSync#sendToAllClients}'s exclusion argument (may be
     * null); the sending client is always skipped — it already holds the shape.
     */
    public static void broadcast(Avatar source, PlayerConfigHolder player, @Nullable ServerPlayer except) {
        PlayerLookup.tracking(source).stream()
            .filter(target -> !target.equals(source))
            .filter(target -> !target.equals(except))
            .forEach(target -> sendTo(target, player));
    }
}
