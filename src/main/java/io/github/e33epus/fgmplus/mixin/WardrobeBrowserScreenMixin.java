package io.github.e33epus.fgmplus.mixin;

import com.wildfire.client.gui.WildfireButton;
import com.wildfire.client.gui.screen.WardrobeBrowserScreen;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import io.github.e33epus.fgmplus.gui.ShapeStudioScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the Shape Studio entry button to the FGM 5 (26.2) wardrobe browser,
 * directly below the appearance-settings button in the same 157px column. The
 * studio edits per-player shapes only, so the button is skipped for mannequin
 * configs (the wardrobe can open either through the synced-player list).
 */
@Mixin(value = WardrobeBrowserScreen.class, remap = false)
public abstract class WardrobeBrowserScreenMixin extends Screen {

	protected WardrobeBrowserScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"), require = 1, remap = false)
	private void fp$addShapeStudioButton(CallbackInfo ci) {
		//'this' is the merged WardrobeBrowserScreen instance at runtime; the
		//displayed avatar's config reaches the studio through the field accessor
		if(!(((BaseWildfireScreenAccessor) this).fgmplus$getConfig() instanceof PlayerConfigHolder player)) {
			return;
		}
		this.addRenderableWidget(new WildfireButton.Builder()
				.message(() -> Component.translatable("fgmplus.studio.entry"))
				.position(this.width / 2 - 36, this.height / 2 - 41)
				.size(157, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("fgmplus.studio.entry_tip")))
				.onPress(button -> this.minecraft.gui.setScreen(new ShapeStudioScreen(
						Component.translatable("fgmplus.studio.title"), this, player)))
				//BaseWildfireScreen#addButton stamps its own open-time guard; a bare
				//Builder just needs any sane timestamp — anti-double-click only
				//suppresses presses inside the first 200ms, same as FGM's own widgets
				.build(Util.getMillis()));
	}
}
