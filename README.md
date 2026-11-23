# FGM Plus

<em>Real bust scaling, per-player shaping and custom hurt sounds for Wildfire's Female Gender Mod.</em>

[Download FGM Plus on GitHub](https://github.com/NoWordz/FGM-Plus/releases/latest)

## What it is

FGM Plus expands **[Wildfire's Female Gender Mod](https://modrinth.com/mod/female-gender)** (FGM) with adjustable bust geometry, a per-player Shape Studio and custom hurt sounds. It works in single-player and can sync shapes in multiplayer when installed alongside FGM on the server and participating clients.

## Features

- **Real bust scaling** adjusts the geometry up to a 500% readout by default.
- **Shape Studio** lets you adjust three-axis scale, perkiness, position and roundness in FGM's wardrobe with a live preview.
- **Roundness** blends the box shape into a rounded model across skin, jacket, armor and glint while retaining FGM's physics.
- **Cleavage bridge** reduces the center gap at high roundness on supported builds. You can toggle it in Shape Studio.
- **Anti-clipping** keeps the rounded mesh within the back of the torso at extreme settings.
- **Wider sliders and custom hurt sounds** let you set your own slider limits and play sounds from a local folder.

## Compatibility

| Minecraft | Loader | FGM version used for the build | Java |
| --- | --- | --- | --- |
| 26.3 | Fabric 0.19.5+ | 5.0.0+mc26.3-fabric | 25 |
| 26.2 | Fabric | 5.0.0+mc26.2-fabric | 25 |
| 26.1–26.1.2 | Fabric | 5.0.0+mc26.1-fabric | 25 |
| 1.21.11 | Fabric | 5.0.0-Beta.3+1.21.11 | 21 |
| 1.21.1 | Fabric | 3.2.1+1.21 | 21 |
| 1.21.1 | NeoForge | 3.2.2 | 21 |
| 1.20.1 | Forge | 1.20.1-3.1 | 17 |

All Fabric builds require Fabric API. Pick the JAR for your loader and Minecraft version.

## Configuration

Slider limits are stored in `config/fgmplus/fgmplus.toml` on Forge and `config/fgmplus/fgmplus.json` elsewhere. The default `bustSizeMax` of 4.0 displays as 500% in FGM. Changes to the range leave saved slider values intact. Your shapes are stored in `config/fgmplus/shapes/`.

## Known limitations

- On Fabric 26.2, if rounded models show black patches with Iris, Photon and 3D Skin Layers, enable **Iris Compatibility Mode** in 3D Skin Layers. You can also set `"irisCompatibilityMode": true` in `config/skinlayers.json` while the game is closed.
- Black shading has also been reported with some Iris and Sodium setups on NeoForge 1.21.1.

## FAQ

**How do other players see my shape?** Install FGM and FGM Plus on the server and on each client that should see synced shapes.

**Where do hurt sounds go?** Put `.ogg` files in `config/fgmplus/sounds/`, which the mod creates on first launch.

**Why does the slider go past 100%?** FGM Plus widens the size range and keeps FGM's percentage scale.

## Install

1. Install Wildfire's Female Gender Mod for your loader. On Fabric, install Fabric API too.
2. Download the matching JAR from [GitHub Releases](https://github.com/NoWordz/FGM-Plus/releases) and place it in `mods/`.
3. In FGM's wardrobe, open **Shape Studio…** for your player.

## Building from source

`versions/targets.json` declares the target matrix. Build the Gradle project in `platforms/<mc>-<loader>/` with its wrapper and the Java version in the table. The JAR goes to `build/libs/` inside that project.

```bash
python tools/verify_targets.py
cd platforms/26.2-fabric
./gradlew build     # Windows PowerShell uses .\gradlew.bat build
```

On Windows with Git Bash, run `bash tools/build_all.sh` from the repository root to build the full matrix. `bash tools/collect_jars.sh` collects the JARs into `dist/<version>/`. Build outputs follow `fgmplus-<loader>-<minecraft>-v<release>.jar`.

## Credits

- FGM Plus builds on [Wildfire's Female Gender Mod](https://modrinth.com/mod/female-gender) and forks [Female Plastic Surgery](https://github.com/Rinko1231/Female-Plastic-Surgery-for-WFG) by Rinko1231.
- Thanks to [XyOfficial977](https://github.com/XyOfficial977) for the [Fabric 26.2 port PR](https://github.com/NoWordz/FGM-Plus/pull/1), which contributed the torso transform, player-only Shape Studio entry and client-local hurt sound fixes.
- XyOfficial977 also contributed the Fabric 26.3 pose rotation and resource-pack API updates. [Fabric 26.3 port PR](https://github.com/NoWordz/FGM-Plus/pull/2)

## License

GPLv3, same as the original and its ancestor. See [LICENSE](LICENSE).
