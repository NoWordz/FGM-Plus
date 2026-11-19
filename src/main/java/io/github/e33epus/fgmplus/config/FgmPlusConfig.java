package io.github.e33epus.fgmplus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.e33epus.fgmplus.FgmPlusMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain JSON config (config/fgmplus/fgmplus.json), replacing the Forge 1.20.1 TOML spec.
 *
 * <p>Loaded lazily on first access: the mixin that widens FGM's slider keys reads these
 * values while FGM's own {@code Configuration} class initializes, which can happen before
 * any mod entrypoint runs. Missing or malformed keys fall back to the spec defaults.</p>
 */
public final class FgmPlusConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Slider range widenings; each entry clamps to the same bounds the Forge spec enforced
	public static double bustSizeMax = 4.0;        // [0.8, 4.0]; 4.0 tops FGM's x125 percent display at 500%, matching the Forge default
	public static double bustOffsetYMin = -1.5;    // [-4.0, -1.0], FGM default bound -1.0
	public static double bustOffsetZMin = -1.5;    // [-4.0, -1.0], FGM default bound -1.0
	public static double bustOffsetZMax = 0.0;     // [0.0, 4.0], FGM default bound 0.0
	public static double bounceMultiplierMax = 1.0;  // [0.5, 3.0], FGM default bound 0.5
	public static double floppyMultiplierMax = 1.5;  // [1.0, 3.0], FGM default bound 1.0

	private static boolean loaded;

	static {
		// load as early as possible: the ConfigurationMixin @Shadow initializers read these
		// fields while FGM's Configuration class initializes, which can predate any mod entrypoint
		load();
	}

	private FgmPlusConfig() {
	}

	public static void setup() {
		load();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FgmPlusMod.MODID).resolve(FgmPlusMod.MODID + ".json");
	}

	private static synchronized void load() {
		if(loaded) return;
		loaded = true;
		//config lives at config/fgmplus/fgmplus.json since 1.6.1 (was fgmplus.json in
		//the config root); carry the old file over so slider-widening settings survive
		try {
			Path legacy = FabricLoader.getInstance().getConfigDir().resolve(FgmPlusMod.MODID + ".json");
			if(!Files.exists(file()) && Files.isRegularFile(legacy)) {
				Files.move(legacy, file());
			}
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: could not migrate the old config file", e);
		}
		Path file = file();
		if(Files.isRegularFile(file)) {
			try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if(json != null) {
					bustSizeMax = clamp(opt(json, "bustSizeMax", bustSizeMax), 0.8, 4.0);
					bustOffsetYMin = clamp(opt(json, "bustOffsetYMin", bustOffsetYMin), -4.0, -1.0);
					bustOffsetZMin = clamp(opt(json, "bustOffsetZMin", bustOffsetZMin), -4.0, -1.0);
					bustOffsetZMax = clamp(opt(json, "bustOffsetZMax", bustOffsetZMax), 0.0, 4.0);
					bounceMultiplierMax = clamp(opt(json, "bounceMultiplierMax", bounceMultiplierMax), 0.5, 3.0);
					floppyMultiplierMax = clamp(opt(json, "floppyMultiplierMax", floppyMultiplierMax), 1.0, 3.0);
				}
			} catch(Exception e) {
				FgmPlusMod.LOGGER.warn("FGM Plus: failed to read config, using defaults", e);
			}
		}
		save();
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			JsonObject json = new JsonObject();
			json.addProperty("bustSizeMax", bustSizeMax);
			json.addProperty("bustOffsetYMin", bustOffsetYMin);
			json.addProperty("bustOffsetZMin", bustOffsetZMin);
			json.addProperty("bustOffsetZMax", bustOffsetZMax);
			json.addProperty("bounceMultiplierMax", bounceMultiplierMax);
			json.addProperty("floppyMultiplierMax", floppyMultiplierMax);
			Files.writeString(file(), GSON.toJson(json), StandardCharsets.UTF_8);
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: failed to write config", e);
		}
	}

	private static double opt(JsonObject json, String key, double fallback) {
		try {
			return json.get(key).getAsDouble();
		} catch(Exception e) {
			return fallback;
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	// ------------------------------------------------------------------
	// Widened bounds, looked up by the persisted key names FGM has used since 3.x.
	// On 26.2 ConfigRangeMixin resolves each ConfigKey's ConfigRange instance back
	// to these names by object identity and routes the slider ranges, the live
	// update() gate and the codec/stream clamp through this one table. The two
	// switches MUST stay key-for-key in lockstep; a key present in only one table
	// is treated as a misconfiguration and ConfigRangeMixin falls back to FGM's
	// stock bounds rather than leave a side unbounded.
	// ------------------------------------------------------------------

	/** Returns the widened minimum for an FGM config key, or NaN when the key is untouched. */
	public static float widenedMin(String fgmKey) {
		return switch(fgmKey == null ? "" : fgmKey) {
			case "bust_size" -> 0.0F; //unchanged, listed for symmetry
			case "breasts_yOffset" -> (float) bustOffsetYMin;
			case "breasts_zOffset" -> (float) bustOffsetZMin;
			case "bounce_multiplier" -> 0.0F; //unchanged
			case "floppy_multiplier" -> 0.25F; //unchanged
			default -> Float.NaN;
		};
	}

	/** Returns the widened maximum for an FGM config key, or NaN when the key is untouched. */
	public static float widenedMax(String fgmKey) {
		return switch(fgmKey == null ? "" : fgmKey) {
			case "bust_size" -> (float) bustSizeMax;
			case "breasts_yOffset" -> 1.0F; //unchanged
			case "breasts_zOffset" -> (float) bustOffsetZMax;
			case "bounce_multiplier" -> (float) bounceMultiplierMax;
			case "floppy_multiplier" -> (float) floppyMultiplierMax;
			default -> Float.NaN;
		};
	}
}
