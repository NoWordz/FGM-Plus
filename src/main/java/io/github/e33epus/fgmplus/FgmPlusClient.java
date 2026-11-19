package io.github.e33epus.fgmplus;

import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import io.github.e33epus.fgmplus.sound.HurtSoundManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;

@Environment(EnvType.CLIENT)
public final class FgmPlusClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HurtSoundManager.ensureSoundDir();
        HurtSoundManager.seedFingerprint();
        ClientPlayConnectionEvents.INIT.register((handler, client) ->
            ClientPlayNetworking.registerReceiver(ShapeSyncPayload.ID, (payload, context) -> {
                LocalPlayer self = context.player();
                if (self == null || !payload.uuid().equals(self.getUUID())) {
                    ShapeSyncPayload.applyClient(payload.uuid(), payload.shape());
                }
            }));
    }
}
