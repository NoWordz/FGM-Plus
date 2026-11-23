package io.github.e33epus.fgmplus.mixin;

import com.wildfire.client.gui.WildfireButton;
import com.wildfire.client.gui.screen.WardrobeBrowserScreen;
import io.github.e33epus.fgmplus.gui.ShapeStudioScreen;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the Shape Studio entry button to the FGM 5 wardrobe browser, directly
 * below the appearance-settings button in the same 157px column (the layout of
 * WardrobeBrowserScreen#init is unchanged from the 1.21.11 port: the appearance
 * button sits at height/2 - 63, leaving the band below it free).
 */
@Mixin(value = WardrobeBrowserScreen.class, remap = false)
public abstract class WardrobeBrowserScreenMixin extends Screen {

	protected WardrobeBrowserScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"), require = 1, remap = false)
	private void fp$addShapeStudioButton(CallbackInfo ci) {
		// Shape Studio stores and renders shapes only for players.
		if(!(((BaseWildfireScreenAccessor) this).fgmplus$getConfig() instanceof PlayerConfigHolder player)) {
			return;
		}
		this.addRenderableWidget(new WildfireButton.Builder()
				.message(() -> Component.translatable("fgmplus.studio.entry"))
				.position(this.width / 2 - 36, this.height / 2 - 41)
				.size(157, 20)
				.onPress(button -> this.minecraft.gui.setScreen(new ShapeStudioScreen(
						Component.translatable("fgmplus.studio.title"), this, player)))
				.build(Util.getMillis()));
	}
}
