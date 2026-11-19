package io.github.e33epus.fgmplus.gui;

import com.wildfire.client.gui.WildfireButton;
import com.wildfire.client.gui.WildfireSlider;
import com.wildfire.client.gui.screen.BaseWildfireScreen;
import com.wildfire.common.entities.avatars.AbstractAvatarConfigHolder;
import com.wildfire.common.entities.players.PlayerConfigHolder;
import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeHolder;
import io.github.e33epus.fgmplus.shape.ShapeRenderState;
import io.github.e33epus.fgmplus.shape.ShapeStore;
import io.github.e33epus.fgmplus.sound.HurtSoundManager;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Per-player breast shape editor (three-axis scale + perkiness + offsets), styled
 * after FGM 5's redesigned screens: darkened backdrop via extractTransparentBackground
 * (NOT the blurred background — blur may only run once per frame), the title centered
 * at the same height as FGM's wardrobe, the live player preview in the same left-hand
 * frame (BaseWildfireScreen#renderPlayerInFrame), and a translucent panel on the right
 * in FGM's grid (166px content width, 82px halves).
 *
 * <p>FGM 5 (26.2) screens take an {@link AbstractAvatarConfigHolder} in their
 * constructor — the Shape Studio edits the player's {@link PlayerConfigHolder}
 * through the ShapeHolder duck interface. The entry button in the wardrobe only
 * shows for player configs, so the cast below always succeeds in practice.</p>
 */
public class ShapeStudioScreen extends BaseWildfireScreen {

	//FGM 5's customization grid: 166px of content, split into two 82px halves
	private static final int FULL_WIDTH = 166;
	private static final int HALF_WIDTH = FULL_WIDTH / 2 - 2;
	private static final int PANEL_FILL = 0x55000000;
	//FGM 5's buttons are 15px tall (its sliders stay 20px)
	private static final int FGM_BUTTON_HEIGHT = 15;

	//Cached at init and refreshed on button presses: getStatusText() stats the disk,
	//which must not run every render frame
	private Component hurtSoundStatus;

	public ShapeStudioScreen(Component title, @Nullable Screen parent, AbstractAvatarConfigHolder holder) {
		super(title, parent, holder);
	}

	@Override
	public void init() {
		super.init(); //BaseWildfireScreen stamps its anti-accident click window here

		int y = this.height / 2 - 11; //FGM's baseline
		final int left = this.width / 2 - 36; //same column FGM's sliders live in
		final int right = left + HALF_WIDTH + 4;

		//Initial slider positions only; callbacks re-resolve the player so edits
		//always land on the live cache entry, never a captured reference
		ShapeData shape = shape();

		//Live preview: update applies to the live shape while dragging (no persist,
		//no sync); save on release writes to disk and flags for sync
		addStudioSlider(left, y - 24, HALF_WIDTH,
				ShapeData.MIN_SCALE, ShapeData.MAX_SCALE, shape.getScaleX(),
				value -> shape().setScaleX(value),
				value -> Component.translatable("fgmplus.studio.scale", "X", String.format(Locale.ROOT, "%.2f", value)),
				value -> persist("scaleX", value));

		addStudioSlider(left, y - 4, HALF_WIDTH,
				ShapeData.MIN_SCALE, ShapeData.MAX_SCALE, shape.getScaleY(),
				value -> shape().setScaleY(value),
				value -> Component.translatable("fgmplus.studio.scale", "Y", String.format(Locale.ROOT, "%.2f", value)),
				value -> persist("scaleY", value));

		addStudioSlider(left, y + 16, HALF_WIDTH,
				ShapeData.MIN_SCALE, ShapeData.MAX_SCALE, shape.getScaleZ(),
				value -> shape().setScaleZ(value),
				value -> Component.translatable("fgmplus.studio.scale", "Z", String.format(Locale.ROOT, "%.2f", value)),
				value -> persist("scaleZ", value));

		//UI shows the inverted value so that dragging RIGHT protrudes (user's
		//expected direction): stored offsetZ is positive toward the torso back
		//(front = -z, user-measured), so the slider negates on both read and write
		addStudioSlider(right, y + 16, HALF_WIDTH,
				ShapeData.MIN_OFFSET, ShapeData.MAX_OFFSET, -shape.getOffsetZ(),
				value -> shape().setOffsetZ(-value),
				value -> Component.translatable("fgmplus.studio.pos", "Z", String.format(Locale.ROOT, "%+.2f", value)),
				value -> persist("offsetZ", -value))
				.setTooltip(Tooltip.create(Component.translatable("fgmplus.studio.pos_z_tip")));

		addStudioSlider(right, y - 24, HALF_WIDTH,
				ShapeData.MIN_OFFSET, ShapeData.MAX_OFFSET, shape.getOffsetX(),
				value -> shape().setOffsetX(value),
				value -> Component.translatable("fgmplus.studio.pos", "X", String.format(Locale.ROOT, "%+.2f", value)),
				value -> persist("offsetX", value));

		addStudioSlider(right, y - 4, HALF_WIDTH,
				ShapeData.MIN_OFFSET, ShapeData.MAX_OFFSET, shape.getOffsetY(),
				value -> shape().setOffsetY(value),
				value -> Component.translatable("fgmplus.studio.pos", "Y", String.format(Locale.ROOT, "%+.2f", value)),
				value -> persist("offsetY", value));

		addStudioSlider(left, y + 36, HALF_WIDTH,
				ShapeData.MIN_PERK, ShapeData.MAX_PERK, shape.getPerkiness(),
				value -> shape().setPerkiness(value),
				value -> Component.translatable("fgmplus.studio.perkiness", String.format(Locale.ROOT, "%+.0f", value)),
				value -> persist("perkiness", value));

		//Roundness: 0 = FGM's flat box (the "triangle" silhouette), 1 = full
		//superellipsoid; displayed as a percentage. Syncs to everyone like the
		//rest of the shape payload
		addStudioSlider(right, y + 36, HALF_WIDTH,
				ShapeData.MIN_ROUNDNESS, ShapeData.MAX_ROUNDNESS, shape.getRoundness(),
				value -> shape().setRoundness(value),
				value -> Component.translatable("fgmplus.studio.roundness", String.format(Locale.ROOT, "%.0f", value * 100f)),
				value -> persist("roundness", value))
				.setTooltip(Tooltip.create(Component.translatable("fgmplus.studio.roundness_tip")));

		//Custom hurt sound controls; every *.ogg in the folder plays (random pick,
		//same as FGM's own damage oggs). Preview plays instantly when the loaded
		//set matches the directory and only chains a full resource reload when the
		//files changed, so newly dropped/renamed files are picked up without restart
		this.hurtSoundStatus = HurtSoundManager.getStatusText();
		addButton(builder -> builder
				.message(() -> Component.translatable("fgmplus.studio.sound_folder"))
				.position(left, y + 56).size(HALF_WIDTH, FGM_BUTTON_HEIGHT)
				.onPress(button -> {
					HurtSoundManager.openFolder();
					this.hurtSoundStatus = HurtSoundManager.getStatusText();
				}));
		addButton(builder -> builder
				.message(() -> Component.translatable("fgmplus.studio.preview"))
				.position(right, y + 56).size(HALF_WIDTH, FGM_BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("fgmplus.studio.preview_tip")))
				.onPress(button -> {
					HurtSoundManager.reloadThenPlay();
					this.hurtSoundStatus = HurtSoundManager.getStatusText();
				}));

		addButton(builder -> builder
				.message(() -> Component.translatable("fgmplus.studio.reset"))
				.position(left, y + 76).size(HALF_WIDTH, FGM_BUTTON_HEIGHT)
				.onPress(button -> {
					((ShapeHolder) player()).fgmplus$setShape(new ShapeData());
					persist("reset", 0.0F);
					//Re-init so the sliders re-read from the fresh default shape
					this.rebuildWidgets();
				}));
		//Diagnosis: shows the computed torso-back plane; if it hugs the back from
		//every angle the transform chain behind the flatten clamp is proven
		addButton(builder -> builder
				.message(() -> Component.translatable(ShapeRenderState.debugPlane ? "fgmplus.studio.debug_on" : "fgmplus.studio.debug_off"))
				.position(right, y + 76).size(HALF_WIDTH, FGM_BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("fgmplus.studio.debug_tip")))
				.onPress(button -> {
					ShapeRenderState.debugPlane = !ShapeRenderState.debugPlane;
					button.updateMessage();
				}));
		//Cleavage bridge toggle: off restores the plain superellipsoid (and FGM's
		//own separation slider feel) for players who prefer the previous look
		addButton(builder -> builder
				.message(() -> Component.translatable(shape().isCleavage() ? "fgmplus.studio.cleavage_on" : "fgmplus.studio.cleavage_off"))
				.position(left, y + 96).size(FULL_WIDTH, FGM_BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("fgmplus.studio.cleavage_tip")))
				.onPress(button -> {
					shape().setCleavage(!shape().isCleavage());
					persist("cleavage", shape().isCleavage() ? 1.0F : 0.0F);
					button.updateMessage();
				}));
	}

	/** The player whose config this screen edits; throws only if misused on a mannequin. */
	private PlayerConfigHolder player() {
		if(config instanceof PlayerConfigHolder plr) {
			return plr;
		}
		throw new IllegalStateException("Shape Studio opened on a non-player config holder");
	}

	/** The live shape instance of the edited player (the duck field, never a copy). */
	private ShapeData shape() {
		return ((ShapeHolder) player()).fgmplus$getShape();
	}

	private WildfireSlider addStudioSlider(int x, int y, int width, float min, float max, float current,
			it.unimi.dsi.fastutil.floats.FloatConsumer update,
			it.unimi.dsi.fastutil.floats.Float2ObjectFunction<Component> message,
			it.unimi.dsi.fastutil.floats.FloatConsumer save) {
		return addSlider(builder -> builder
				.position(x, y).size(width, 20)
				.range(min, max).current(current)
				.update(update).message(message).save(save));
	}

	/** Re-reads the sound status text; called after the async preview reload finishes. */
	public void refreshSoundStatus() {
		this.hurtSoundStatus = HurtSoundManager.getStatusText();
	}

	private void persist(String key, float value) {
		PlayerConfigHolder plr = config instanceof PlayerConfigHolder p ? p : null;
		if(plr == null) {
			return;
		}
		ShapeStore.save(plr.uuid, ((ShapeHolder) plr).fgmplus$getShape());
		//FGM's own tick loop detects this flag and sends the C2S sync packet;
		//the shape payload piggybacks on that send (WildfireSyncClientMixin)
		plr.needsSync = true;
		//Observability for the "slider edits stop applying" report class
		FgmPlusMod.LOGGER.debug("FGM Plus shape edit: uuid={}, {}={}", plr.uuid, key, value);
	}

	/**
	 * Styled after FGM 5's screens: darken the world with extractTransparentBackground
	 * (never the blurred background — it may only run once per frame), panel + title
	 * on top. Vanilla Screen calls this exactly once per frame.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.extractTransparentBackground(graphics);

		int x = this.width / 2;
		int y = this.height / 2;
		//Same translucent panel style as FGM's customization tabs, sized over the grid
		//(+1 on the bottom edge: fill's end is exclusive, and the wrapped status
		//text's second line reaches y+134)
		graphics.fill(x - 40, y - 32, x + 136, y + 135, PANEL_FILL);

		//Title centered at FGM's wardrobe title height
		graphics.text(this.font, this.title, x - this.font.width(this.title) / 2, y - 82, 0xFFFFFF, false);

		//Large live preview of the actual player in FGM's own frame; the model renders
		//through the same GenderLayer pipeline, so live slider edits show up here
		renderPlayerInFrame(graphics, x - 90, y + 44, mouseX, mouseY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		//NO manual background extraction here: vanilla Screen already calls it once
		//per frame, and the blur behind it would throw on a second call
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		int x = this.width / 2;
		int y = this.height / 2;
		//Status text (loaded sound files) below the 15px button rows; can exceed the
		//162 px panel (long file names), so wrap to at most two lines with a tail
		int ly = y + 114;
		for(String line : wrapStatus(this.hurtSoundStatus.getString(), this.font, FULL_WIDTH - 4)) {
			graphics.centeredText(this.font, line, x + 48, ly, 0xE0E0E0);
			ly += 10;
		}
	}

	/** Greedy character-wrap to at most 2 lines of {@code maxWidth} px. */
	private static List<String> wrapStatus(String text, net.minecraft.client.gui.Font font, int maxWidth) {
		List<String> lines = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();
		for(int i = 0; i < text.length(); ) {
			int codePoint = text.codePointAt(i);
			String piece = new String(Character.toChars(codePoint));
			if(current.length() > 0 && font.width(current + piece) > maxWidth) {
				lines.add(current.toString());
				current.setLength(0);
			}
			current.append(piece);
			i += Character.charCount(codePoint);
		}
		if(current.length() > 0) {
			lines.add(current.toString());
		}
		if(lines.size() > 2) {
			String second = lines.get(1);
			while(!second.isEmpty() && font.width(second + "…") > maxWidth) {
				second = second.substring(0, second.length() - 1);
			}
			return List.of(lines.get(0), second + "…");
		}
		return lines;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		//Ensure all sliders are saved even when the mouse comes up outside the widget
		//(same children() sweep FGM 5's customization screen uses)
		for(var child : this.children()) {
			if(child instanceof WildfireSlider slider) {
				slider.save();
			}
		}
		return super.mouseReleased(event);
	}
}
