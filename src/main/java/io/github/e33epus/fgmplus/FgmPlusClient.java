package io.github.e33epus.fgmplus;

import io.github.e33epus.fgmplus.net.ShapeSyncPayload;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.sound.HurtSoundManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.player.LocalPlayer;

@Environment(EnvType.CLIENT)
public class FgmPlusClient implements ClientModInitializer {

	/**
	 * Per-player shape carried on the render state, the 26.1 successor of the
	 * GenderRenderState duck interface (upstream FabricClientHelper.STATE is the
	 * same mechanism for its own GenderRenderState). {@code HumanoidRenderState}
	 * has no UUID field on 26.1 (javap-verified), so a data key is required; the
	 * payload is the {@link ShapeData} itself, not the static-only
	 * {@code ShapeRenderState} toggle holder.
	 */
	public static final RenderStateDataKey<ShapeData> STATE_KEY = RenderStateDataKey.create(() -> "FgmPlusShapeData");

	@Override
	public void onInitializeClient() {
		//the import folder must exist for manual ogg drops, even if the studio's
		//open-folder button is never clicked
		HurtSoundManager.ensureSoundDir();

		//Seed the sound-file fingerprint before the game's initial resource load
		//scans the same directory (HurtSoundManager preview fast path)
		HurtSoundManager.seedFingerprint();

		//same unregistered-event -> holder registration pattern as FGM's own
		//FabricClientHelper.registerSounds (female_hurt)
		HurtSoundManager.registerSound();

		ClientPlayConnectionEvents.INIT.register((handler, client) ->
				ClientPlayNetworking.registerReceiver(ShapeSyncPayload.ID, (payload, context) -> {
					// the server never sends our own shape back to us, but guard anyway
					LocalPlayer self = context.player();
					if(self != null && payload.uuid().equals(self.getUUID())) return;
					ShapeSyncPayload.apply(payload.uuid(), payload.shape());
				}));
	}
}
