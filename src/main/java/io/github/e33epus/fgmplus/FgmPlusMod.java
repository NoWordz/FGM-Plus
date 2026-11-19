package io.github.e33epus.fgmplus;

import com.mojang.logging.LogUtils;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;

/** FGM Plus 的服务器入口：注册逐玩家造型同步通道。 */
public final class FgmPlusMod implements ModInitializer {
    public static final String MODID = "fgmplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        FgmPlusConfig.setup();
        PayloadTypeRegistry.serverboundPlay().register(ShapeSyncPayload.ID, ShapeSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShapeSyncPayload.ID, ShapeSyncPayload.CODEC);
        ServerPlayConnectionEvents.INIT.register((handler, server) ->
            ServerPlayNetworking.registerReceiver(handler, ShapeSyncPayload.ID, (payload, context) -> {
                ShapeSyncPayload.applyServer(context.player().getUUID(), payload.shape());
                PlayerConfigHolder player = ShapeSyncPayload.serverPlayer(context.player().getUUID());
                if (player != null) {
                    ShapeSyncPayload.broadcast(context.player(), player, null);
                }
            }));
    }
}
