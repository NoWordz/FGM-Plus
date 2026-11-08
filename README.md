# FGM Plus

<em>Real bust scaling, per-player shaping and custom hurt sounds for Wildfire's Female Gender Mod.</em>

## What it is

An expansion for **[Wildfire's Female Gender Mod](https://modrinth.com/mod/female-gender)** (FGM). Where FGM's slider stops at a fixed cap and only moves the model around, FGM Plus grows the model itself and adds a shaping studio on top: sizes past the cap, three-axis scaling, perkiness, roundness, per-player shapes that sync to everyone, and your own hurt sounds. A fork of **Female Plastic Surgery** by [Rinko1231](https://github.com/Rinko1231/Female-Plastic-Surgery-for-WFG) (GPLv3). Client-side.

## Features

- **Real bust scaling** — sizes above FGM's cap grow the actual geometry, up to a 500% readout.
- **Shape Studio** — a per-player editor in FGM's wardrobe: three-axis scale, perkiness, position offsets, roundness, live preview, synced to everyone running the mod.
- **Roundness** — 0–100% morphs FGM's flat box into a superellipsoid across skin, jacket, armor and glint, keeping all physics.
- **Cleavage bridge** (1.21.1) — closes the inner gap between the two rounds at high roundness; toggleable, ON by default.
- **Anti-clip flattening** — vertices past the torso back are clamped, so extreme sizes never poke through.
- **Widened sliders & hurt sounds** — configurable caps for FGM's sliders, plus random custom hurt sounds from a folder.

## Compatibility

| Target | Requires |
| --- | --- |
| Forge 1.20.1 | FGM 1.20.1-3.x |
| Fabric 1.21.1 | Fabric API, FGM 3.2.1+1.21 |
| NeoForge 1.21.1 | FGM 3.2.2 |
| Fabric 1.21.11 | Fabric API, FGM 5.0.0-Beta.3+1.21.11 |

Download the jar for your loader and game version — there is no universal build. All four are client-side; put it on the server too (with FGM) so other players see your shape.

## Configuration

Config is `config/fgmplus/fgmplus.toml` on Forge and `config/fgmplus/fgmplus.json` elsewhere. It holds the widened slider caps — `bustSizeMax` (default 4.0, the 500% readout), `bustOffsetYMin`, `bustOffsetZMin`, `bustOffsetZMax`, `bounceMultiplierMax`, `floppyMultiplierMax`. Your shape lives in `config/fgmplus/shapes/`, hurt sounds in `config/fgmplus/sounds/`.

## Known limitations

- FGM's readout is ×1.25, so `bustSizeMax` 4.0 reads as 500% — values above 100% are expected.
- Widening changes the slider range only; already-saved values keep their number.
- The cleavage bridge is only on the 1.21.1 builds.
- Heavy Iris/Sodium shader stacks on 1.21.1 NeoForge can shade the breasts black — a shader-pipeline interaction, not a defect in this mod.

## FAQ

**Do I need it on the server?** — Not for your own view; install it (with FGM) there so others see your shape.

**Does it replace FGM?** — No, FGM stays required and unchanged; FGM Plus adds to it.

**Where do hurt sounds go?** — `config/fgmplus/sounds/` (`.ogg`), created on first launch.

**Why does the slider go past 100%?** — That is the widened range, not an error.

## Install

1. Install [FGM](https://modrinth.com/mod/female-gender) for your loader (Fabric also needs Fabric API).
2. Drop the matching FGM Plus jar into `mods/`.
3. Open FGM's wardrobe and pick **Shape Studio…**.

## Building from source

`versions/targets.json` declares the target matrix, `shared/` is the mapping-neutral layer, and `platforms/<mc>-<loader>/` is one Gradle project per target.

```bash
bash tools/build_all.sh                          # guard gate + build every target
bash tools/collect_jars.sh                       # collect into dist/<version>/
python tools/verify_targets.py                   # structure self-check
python tools/store_copy.py --platform modrinth  # derive the store body
```

## License

GPLv3, same as the original and its ancestor. See [LICENSE](LICENSE).
