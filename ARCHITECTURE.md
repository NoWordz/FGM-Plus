# 仓库架构：单分支多目标（e33chat / AtomChat 同款）

一个 `main` 分支，出所有加载器的 jar。旧的 `1.20.1Forge` 分支保留为
独立单平台布局的归档，不再演进。

## 目录

```
gradle.properties        仓库级身份（mod_id / mod_version / …）—— 唯一一份，平台不许定义
versions/
  targets.json           目标矩阵：每个 MC 版本 × 加载器一条；buildable 决定发不发
  layers.json            共享层声明（since / loader / mappings 三轴谓词；当前为空，文件保留）
shared/src/              纯 Java + 映射中立：全目标共用（形状数据类在这里）
platforms/
  1.20.1-forge/          ForgeGradle 6 / Gradle 8.4 / Java 17 / 官方 mappings（FGM 3.1）
  1.21.11-fabric/        Loom 1.14 / Gradle 9.2 / Java 21 / 官方 mappings（FGM 5.0-beta3）
  1.21.1-fabric/         Loom 1.14 / Gradle 9.2 / Java 21 / 官方 mappings（FGM 3.2.1）
  1.21.1-neoforge/       ModDevGradle 2 / Gradle 9.2 / Java 21 / 官方 mappings（FGM 3.2.2）
  26.1-fabric/          Loom 1.17 / Gradle 9.7 / Java 25 / 无需映射（FGM 5.0.0）
  26.2-fabric/          Loom 1.17 / Gradle 9.7 / Java 25 / 无需映射（FGM 5.0.0）
  26.3-fabric/          Loom 1.17 / Gradle 9.7 / Java 25 / 无需映射（FGM 5.0.0）
gradle/fgmplus-layers.gradle   挂载脚本：读根身份，按 targets.json 把 shared+层接进源集
tools/
  verify_targets.py      守卫闸（本地与 CI 同一份；--list-buildable 供脚本消费）
  build_all.sh           本地全端构建（先跑守卫闸）
  collect_jars.sh        把构建产物收进 dist/<mod_version>/
dist/<mod_version>/      部署 / Release 以此目录为准（不进 git）
```

## 常用命令

```bash
# 全端自检（推送前跑这个）
bash tools/build_all.sh

# 单端构建（在平台目录里）
cd platforms/1.21.11-fabric && ./gradlew.bat build --no-daemon

# 守卫闸 + 出 CI 矩阵
python tools/verify_targets.py --matrix-out _matrix.json
```

## 三条铁律

1. **身份只有一份**：`mod_version` 等只在仓库根；平台 `gradle.properties` 只放
   平台事实（loader 版本、JVM 参数、产物名）。守卫闸抓第二份。
2. **挂载即声明**：目标挂哪几层写在 `targets.json`，谓词不满足在配置期就红；
   工程目录存在就必须在矩阵里有条目。
3. **副本必须有闸**：同一段代码出现两份，要么进 `shared/`、要么进映射层 + 孪生、
   要么进钉等清单。没有第四种状态。现状：`ShapeData` / `ShapeHolder` /
   `ShapeStateHolder` / `ShapeRenderState` 在 shared/；`ShapeStore` 每个平台各一份
   （配置目录来源是平台 API：FMLPaths vs FabricLoader），属「有意双份」，将来
   做 facade 化（构造注入 base dir）即可上提。

## 映射家族的现实

1.21.x 及更早的目标使用 Mojang 官方映射，26.x 直接使用未混淆的 Mojang 类名。
所以 shared/ 不需要映射孪生。哪天加 Yarn 家族目标，
官方名共享代码进 `layers/mapping/official/`，Yarn 侧留同路径孪生（同 e33chat）。

## 平台差异速览（改代码前先看这行）

- 上游 FGM：Forge 1.20.1 端对 **FGM 3.1**（`GenderPlayer` / `renderBreastWithTransforms`
  / 包尾追加同步），Fabric 1.21.11 端对 **FGM 5.0-beta**（`PlayerConfig` /
  `BreastRenderCommand` 提交式渲染 / 独立 `fgmplus:shape_sync` payload），
  1.21.1 双端对 **FGM 3.2.x**（3.1 血统的即时渲染线，mixin 集按 NeoForge / Fabric
  API 各自适配）。Fabric 26.1、26.2 和 26.3 使用 **FGM 5.0.0 正式版**，通过
  `BreastModel` 渲染挂钩应用造型。形状数据在各平台共享。
- 版本号：全平台同一个 `mod_version`。上游 FGM 大版本升级（如 5.0 出正式版）
  通常要重验对应平台的 mixin，届时 bump `mod_version`。
