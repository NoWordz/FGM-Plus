package io.github.e33epus.fgmplus.sound;

import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.gui.ShapeStudioScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import com.wildfire.client.ClientHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Custom female hurt sound support, mirroring FGM's own multi-ogg layout:
 * every *.ogg dropped into config/fgmplus/sounds/ becomes an entry of the
 * single fgmplus:custom_hurt event and SoundEngine picks one at random per
 * play, exactly like female_gender:female_hurt with its oggs. Arbitrary
 * file names (Chinese, spaces, upper case) are accepted: the pack exposes
 * them under sanitized index names custom_hurt_0..N-1 mapped to the
 * name-sorted directory listing, and sounds.json is regenerated on every
 * resource read, so a full resource reload (F3+T or the preview button, which
 * chains Minecraft#reloadResourcePacks) always picks up added/removed files.
 * SoundManager#reload() is NOT enough for that: its only call is
 * SoundEngine#reload (buffer cache), the sounds.json entry map is only rebuilt
 * by the full reload listener pipeline. When the directory holds no ogg at
 * all the pack exposes an empty sounds.json and every path falls back to
 * FGM's own female hurt sound (ClientHelper#femaleHurt on 5.0.0 — the old
 * WildfireSounds.FEMALE_HURT constant is gone).
 *
 * <p>The hurt-sound swap seam is HurtSoundMixin (redirect at
 * AbstractAvatarConfigHolder#tryPlayHurtSound); on Fabric the pack is inserted
 * into the client PackRepository by {@code PackRepositoryMixin}.</p>
 */
public final class HurtSoundManager {

	public static final Identifier CUSTOM_HURT_ID =
			Identifier.fromNamespaceAndPath(FgmPlusMod.MODID, "custom_hurt");
	// FGM unwraps this holder before playing the sound locally by identifier.
	public static final SoundEvent CUSTOM_HURT = SoundEvent.createVariableRangeEvent(CUSTOM_HURT_ID);
	private static final Holder<SoundEvent> CUSTOM_HURT_HOLDER = Holder.direct(CUSTOM_HURT);

	public static final String PACK_ID = "fgmplus_hurt_sounds";
	private static final PackFormat PACK_FORMAT = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
	private static final String SOUNDS_JSON_PATH = "sounds.json";
	// Pack-internal resource name of indexed ogg entry i (always lowercase, so
	// even exotic file names on disk stay a valid Identifier path)
	private static final Pattern INDEXED_OGG = Pattern.compile("custom_hurt_(\\d+)\\.ogg");

	// Guards the preview button: a resource reload is already in flight, extra
	// clicks are dropped instead of queueing parallel reloads
	private static final AtomicBoolean RELOADING = new AtomicBoolean(false);

	// File-set fingerprint baked into the SoundManager at its last (re)load, or
	// null while unknown. Seeded on the client's first config access (the game's
	// initial resource load runs after that and bakes in exactly this set) and
	// refreshed whenever the preview button completes a full reload. If the
	// current scan matches, previewing only needs the cheap SoundManager#reload
	// (drops the cached ogg buffers, no LoadingOverlay) and plays instantly; a
	// mismatch means added/removed/renamed files -> full resource reload.
	private static volatile String lastLoadedFingerprint;

	private static final byte[] EMPTY_SOUNDS_JSON = "{}".getBytes(StandardCharsets.UTF_8);

	private static final byte[] PACK_MCMETA = String.format(Locale.ROOT,
			"{\"pack\":{\"min_format\":[%d,%d],\"max_format\":[%d,%d],"
					+ "\"description\":\"FGM Plus custom hurt sounds\"}}",
			PACK_FORMAT.major(), PACK_FORMAT.minor(), PACK_FORMAT.major(), PACK_FORMAT.minor())
			.getBytes(StandardCharsets.UTF_8);

	private HurtSoundManager() {
	}

	// ------------------------------------------------------------------
	// Frozen API for the GUI (ShapeStudioScreen sound section)
	// ------------------------------------------------------------------

	/** Opens config/fgmplus/sounds/ (created if missing) in the system file browser. */
	public static void openFolder() {
		try {
			Path dir = getSoundDir();
			Files.createDirectories(dir);
			//vanilla Util.openFile routes directories through rundll32 FileProtocolHandler
			//on a single-slash file:/ URL, which silently opens nothing on Windows;
			//explorer.exe on the plain path is the one open that always works there
			if (System.getProperty("os.name", "").contains("Windows")) {
				new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
			} else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
				//26.3 移除了 Util.getPlatform().openFile()：macOS 用系统 open 命令
				new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
			} else {
				//Linux 等桌面环境走 freedesktop 的 xdg-open
				new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start();
			}
		} catch(Throwable t) {
			// surfaced at warn: a silent no-op click is undebuggable
			FgmPlusMod.LOGGER.warn("FGM Plus: failed to open the sound folder", t);
		}
	}

	/**
	 * Preview: plays instantly when the loaded sound set already matches the
	 * directory (the common case — SoundManager#reload only drops the cached ogg
	 * buffers so replaced file contents play fresh, no LoadingOverlay flash);
	 * runs the full resource reload first only when files were added, removed or
	 * renamed since the last load (the sounds.json entry map is only rebuilt by
	 * the full pipeline). The 60s timeout exists because the reload future can
	 * NEVER complete on failure (it goes through rollbackResourcePacks instead)
	 * and the future returned while another reload overlay is showing is
	 * discarded wholesale — without it the RELOADING latch would stay closed
	 * forever and silently kill the preview button for the rest of the session.
	 */
	public static void reloadThenPlay() {
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null) {
			FgmPlusMod.LOGGER.debug("FGM Plus reloadThenPlay: no local player, skipping");
			return;
		}
		List<Path> files = scanSoundFiles();
		String fingerprint = fingerprintOf(files);
		//Sync-based fast path, not "files non-empty": an empty sound folder with
		//nothing loaded is also in sync — play the fallback sound right away. The
		//old !files.isEmpty() guard made every preview click chain a full resource
		//reload on installs that have no custom sounds yet
		boolean loaded = mc.getSoundManager().getSoundEvent(CUSTOM_HURT_ID) != null;
		boolean inSync = files.isEmpty() ? !loaded
				: loaded && Objects.equals(lastLoadedFingerprint, fingerprint);
		if(inSync) {
			//Loaded set matches and nothing changed since: play straight away, no
			//reload of any kind. SoundManager#reload() would be a full audio-engine
			//restart (SoundEngine.stopAll mutes music and every channel for a beat)
			//— too invasive for a preview click. A same-name content swap keeps the
			//old cached buffer until the user runs F3+T (documented in the UI).
			testPlay();
			if(mc.gui.screen() instanceof ShapeStudioScreen studio) {
				studio.refreshSoundStatus();
			}
			return;
		}
		if(!RELOADING.compareAndSet(false, true)) {
			return; //a reload is already running; its completion will play
		}
		mc.reloadResourcePacks()
				.orTimeout(60, TimeUnit.SECONDS)
				.whenComplete((v, t) -> mc.execute(() -> {
					RELOADING.set(false);
					if(t == null) {
						lastLoadedFingerprint = fingerprintOf(scanSoundFiles());
						testPlay();
						if(mc.gui.screen() instanceof ShapeStudioScreen studio) {
							studio.refreshSoundStatus();
						}
					} else {
						FgmPlusMod.LOGGER.warn("FGM Plus resource reload failed or timed out", t);
					}
				}));
	}

	/**
	 * Plays the custom hurt sound once at the local player's position using the
	 * currently loaded entry map. When no ogg is present, falls back to FGM's own
	 * female hurt event (ClientHelper#femaleHurt on 5.0.0) so the preview button
	 * is never silent.
	 */
	public static void testPlay() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if(player == null) {
			FgmPlusMod.LOGGER.debug("FGM Plus testPlay: no local player, skipping");
			return;
		}
		boolean present = hasCustomSounds();
		if(present) {
			player.level().playLocalSound(player.getX(), player.getY(), player.getZ(),
					CUSTOM_HURT, SoundSource.PLAYERS, 1.0F, 1.0F, false);
		} else {
			SoundEvent fallback = fallbackHurtSound();
			player.level().playLocalSound(player.getX(), player.getY(), player.getZ(),
					fallback, SoundSource.PLAYERS, 1.0F, 1.0F, false);
		}
		FgmPlusMod.LOGGER.debug("FGM Plus testPlay: {} ogg file(s) present={}, playing {}",
				scanSoundFiles().size(), present, present ? CUSTOM_HURT_ID : fallbackHurtSound().location());
	}

	/** FGM's registered female hurt event; errors loudly in the log if absent. */
	private static SoundEvent fallbackHurtSound() {
		try {
			return ClientHelper.INSTANCE.femaleHurt().value();
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: FGM's female hurt sound is not available", e);
			return CUSTOM_HURT;
		}
	}

	public static Component getStatusText() {
		List<Path> files = scanSoundFiles();
		if(files.isEmpty()) {
			return Component.translatable("fgmplus.studio.sound_none");
		}
		Minecraft mc = Minecraft.getInstance();
		boolean loaded = mc != null
				&& mc.getSoundManager().getSoundEvent(CUSTOM_HURT_ID) != null
				&& Objects.equals(lastLoadedFingerprint, fingerprintOf(files));
		if(!loaded) {
			return Component.translatable("fgmplus.studio.sound_pending", files.size());
		}
		StringBuilder label = new StringBuilder();
		int shown = Math.min(2, files.size());
		for(int i = 0; i < shown; i++) {
			if(i > 0) {
				label.append(", ");
			}
			label.append(files.get(i).getFileName().toString());
		}
		if(files.size() > shown) {
			label.append("…");
		}
		return Component.translatable("fgmplus.studio.sound_enabled", files.size(), label.toString());
	}

	// ------------------------------------------------------------------
	// Redirect target used by HurtSoundMixin
	// ------------------------------------------------------------------

	/**
	 * Returns the custom sound holder only when at least one
	 * user-provided ogg exists AND the entry map actually contains it (the map is
	 * only rebuilt by a full resource reload, so files dropped while the game is
	 * running would otherwise swap out the original sound for an unresolvable
	 * event: no sound at all on hurt, not even FGM's default — matching the
	 * preview path's fallback keeps both paths consistent). Null passthrough for
	 * non-FEMALE genders is preserved so FGM's own null-check keeps working.
	 */
	public static Holder<SoundEvent> resolveHurtSound(Holder<SoundEvent> original) {
		if(original == null || !hasCustomSounds()) {
			return original;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.getSoundManager().getSoundEvent(CUSTOM_HURT_ID) != null
				? CUSTOM_HURT_HOLDER : original;
	}

	// ------------------------------------------------------------------
	// Paths and scanning
	// ------------------------------------------------------------------

	/** Creates config/fgmplus/sounds/ up front, so manual ogg imports always have a
	 *  home even if the studio's open-folder button is never clicked. */
	public static void ensureSoundDir() {
		try {
			Files.createDirectories(getSoundDir());
		} catch(IOException e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: could not create the sound folder", e);
		}
	}

	public static Path getSoundDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(FgmPlusMod.MODID).resolve("sounds");
	}

	/** All *.ogg files in the sound dir (not hidden), sorted by file name. */
	public static List<Path> scanSoundFiles() {
		try(Stream<Path> stream = Files.list(getSoundDir())) {
			return stream
					.filter(Files::isRegularFile)
					.filter(p -> {
						String name = p.getFileName().toString();
						return !name.startsWith(".") && name.toLowerCase(Locale.ROOT).endsWith(".ogg");
					})
					.sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
					.toList();
		} catch(IOException e) {
			return List.of();
		}
	}

	public static boolean hasCustomSounds() {
		return !scanSoundFiles().isEmpty();
	}

	/** Cheap marker of the file-set identity (names in sorted order). */
	private static String fingerprintOf(List<Path> files) {
		StringBuilder sb = new StringBuilder();
		for(Path p : files) {
			sb.append(p.getFileName().toString()).append('|');
		}
		return sb.toString();
	}

	/** One sounds.json content per scan; empty dir -> no custom_hurt entry at all. */
	private static byte[] buildSoundsJson(List<Path> files) {
		if(files.isEmpty()) {
			return EMPTY_SOUNDS_JSON;
		}
		StringBuilder json = new StringBuilder("{\"custom_hurt\":{\"category\":\"player\",\"sounds\":[");
		for(int i = 0; i < files.size(); i++) {
			if(i > 0) {
				json.append(',');
			}
			json.append("\"fgmplus:custom_hurt_").append(i).append('"');
		}
		json.append("]}}");
		return json.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static IoSupplier<InputStream> ofBytes(byte[] data) {
		return () -> new ByteArrayInputStream(data);
	}

	/** Seeds the loaded-set fingerprint once the client is coming up. */
	public static void seedFingerprint() {
		if(lastLoadedFingerprint == null) {
			lastLoadedFingerprint = fingerprintOf(scanSoundFiles());
		}
	}

	// ------------------------------------------------------------------
	// Runtime resource pack (injected by PackRepositoryMixin)
	// ------------------------------------------------------------------

	/**
	 * required (PackSelectionConfig) -> PackRepository keeps it selected unconditionally
	 * (mirrors the Forge 1.20.1 required=true pack); fixedPosition stops players from
	 * moving or disabling it. Note it still SHOWS UP in the resource pack screen as a
	 * locked entry — there is no way to hide a repository pack from that screen.
	 */
	public static Pack createHurtSoundPack() {
		PackLocationInfo location = new PackLocationInfo(
				PACK_ID,
				Component.literal("FGM Plus Custom Hurt Sounds"),
				PackSource.BUILT_IN,
				java.util.Optional.empty());
		//26.3 的 ResourcesSupplier 合并为 openMetadata + openResources(Stream) 两个抽象方法
		Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
			@Override
			public PackMetadataResources openMetadata(PackLocationInfo info) {
				return new HurtSoundPack(info);
			}

			@Override
			public Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata metadata) {
				return Stream.of(new HurtSoundPack(info));
			}
		};
		return Pack.readMetaAndCreate(
				location,
				supplier,
				PackType.CLIENT_RESOURCES,
				new PackSelectionConfig(true, Pack.Position.TOP, true));
	}

	public static final class HurtSoundPack implements PackResources {

		private final PackLocationInfo location;

		public HurtSoundPack(PackLocationInfo location) {
			this.location = location;
		}

		@Override
		public PackLocationInfo location() {
			return this.location;
		}

		@Override
		public IoSupplier<InputStream> getRootResource(String... names) {
			if(names.length == 1 && "pack.mcmeta".equals(names[0])) {
				return ofBytes(PACK_MCMETA);
			}
			return null;
		}

		@Override
		public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
			if(type != PackType.CLIENT_RESOURCES || !FgmPlusMod.MODID.equals(location.getNamespace())) {
				return null;
			}
			String path = location.getPath();
			if(SOUNDS_JSON_PATH.equals(path)) {
				return ofBytes(buildSoundsJson(scanSoundFiles()));
			}
			Matcher indexed = path.startsWith("sounds/")
					? INDEXED_OGG.matcher(path.substring("sounds/".length())) : null;
			if(indexed != null && indexed.matches() && indexed.group(1).length() <= 9) {
				//The length guard keeps a forged custom_hurt_<huge> request from
				//throwing NumberFormatException and failing the whole reload
				List<Path> files = scanSoundFiles();
				int index = Integer.parseInt(indexed.group(1));
				if(index < files.size()) {
					return IoSupplier.create(files.get(index));
				}
			}
			return null;
		}

		@Override
		public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput output) {
			if(type != PackType.CLIENT_RESOURCES || !FgmPlusMod.MODID.equals(namespace)) {
				return;
			}
			//sounds.json is intentionally NOT listed here: its consumers go through
			//getResource, and the SoundManager's listResources pass runs with an
			//".ogg" suffix filter that would discard it anyway
			List<Path> files = scanSoundFiles();
			for(int i = 0; i < files.size(); i++) {
				String path = "sounds/custom_hurt_" + i + ".ogg";
				if(path.startsWith(prefix)) {
					output.accept(Identifier.fromNamespaceAndPath(FgmPlusMod.MODID, path),
							IoSupplier.create(files.get(i)));
				}
			}
		}

		@Override
		public java.util.Set<String> getNamespaces(PackType type) {
			return type == PackType.CLIENT_RESOURCES ? java.util.Set.of(FgmPlusMod.MODID) : java.util.Set.of();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> serializer) throws java.io.IOException {
			if(serializer == net.minecraft.server.packs.metadata.pack.PackMetadataSection.CLIENT_TYPE) {
				return (T) new net.minecraft.server.packs.metadata.pack.PackMetadataSection(
						Component.literal("FGM Plus custom hurt sounds"),
						new net.minecraft.util.InclusiveRange<>(PACK_FORMAT, PACK_FORMAT));
			}
			return null;
		}

		@Override
		public void close() {
		}
	}
}
