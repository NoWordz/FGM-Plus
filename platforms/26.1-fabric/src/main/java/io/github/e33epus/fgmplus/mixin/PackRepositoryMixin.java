package io.github.e33epus.fgmplus.mixin;

import com.google.common.collect.ImmutableMap;
import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.sound.HurtSoundManager;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Injects the always-on dynamic hurt-sound pack into the client pack repository,
 * replacing the Forge 1.20.1 AddPackFinders + required=true mechanism (Fabric has
 * no pack-finder event).
 *
 * <p>The redirect target is the ImmutableMap.copyOf call inside PackRepository#
 * discoverAvailable: the method builds a MUTABLE TreeMap, hands it to every
 * RepositorySource, then freezes it. Putting our pack into the mutable map before
 * the freeze is the only reliable seam — the {@code available} field itself is
 * already immutable by the time reload() returns (putting there throws
 * UnsupportedOperationException, hit by 1.5.0's first build in dev).</p>
 *
 * <p>Selection is NOT our job: rebuildSelected unconditionally adds every pack
 * whose {@code Pack#isRequired()} is true, and our PackSelectionConfig passes
 * required=true, so vanilla itself keeps the pack selected on every reload.</p>
 *
 * <p>require=0: if upstream reshapes discoverAvailable the feature degrades to
 * FGM's default hurt sounds instead of crashing.</p>
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

	@Redirect(method = "discoverAvailable", at = @At(value = "INVOKE",
			target = "Lcom/google/common/collect/ImmutableMap;copyOf(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;",
			remap = false),
			require = 0)
	//the return type MUST match the target's exact erased type — copyOf's descriptor
	//returns RAW ImmutableMap (no generics), so the handler must too
	@SuppressWarnings({"rawtypes", "unchecked"})
	private ImmutableMap fgmplus$injectHurtSoundPack(Map<String, Pack> mutable) {
		try {
			mutable.put(HurtSoundManager.PACK_ID, HurtSoundManager.createHurtSoundPack());
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: failed to inject the custom hurt sound pack", e);
		}
		return ImmutableMap.copyOf(mutable);
	}
}
