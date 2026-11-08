# 26.1-fabric → FGM 5.0.0 正式版移植记录（2026-11-07）

上游基线：FGM **5.0.0 正式版**，源码 `D:\Claude_ds\_fgm500_research`（下称 `<fgm>`）。
本平台源：`platforms/26.1-fabric/src/main/java/io/github/e33epus/fgmplus/`，从 1.21.11-fabric 整体拷入后按下表移植。
接缝规格：`_notes/SEAMS.md`（本文件的所有 file:line 证据均出自该克隆的 5.0.0 tag 源码）。

## 终态

| 验收门 | 结果 |
| --- | --- |
| 阶段1 compileJava | 绿（第 2 轮；第 1 轮唯一实锤 = fabric-api 26.1 把 `PayloadTypeRegistry.playC2S/playS2C` 改名 `serverboundPlay/clientboundPlay`） |
| 阶段2 `gradlew build` | 绿，产出 `build/libs/fgmplus-fabric-26.1-1.6.1.jar` |
| 阶段3 离线 harness | `RoundMeshProbe` ALL PASS（22 断言）+ `RoundMeshHarness` ALL PASS（9 断言，quadCount=74 与 1.21.11 端一致），退出码均 0 |

## 一、文件级移植决策

| 文件 | 决策（一句话） |
| --- | --- |
| `FgmPlusMod.java` | 服务器接收器语义不变（sender-uuid 校验 + 立即回播）；落点从 PlayerConfig 改为 ShapeCache；payload 注册改用新方法名 |
| `FgmPlusClient.java` | 新增 `STATE_KEY = RenderStateDataKey<ShapeData>`（对齐上游 `FabricClientHelper.STATE`，`<fgm>/fabric/.../FabricClientHelper.java:37`）；新增 custom_hurt 的 Holder 注册（仿 `FabricClientHelper.registerSounds():41-44`） |
| `config/FgmPlusConfig.java` | 原样保留（widenedMin/Max 表被新 BreastsMixin/BreastsPhysicsMixin 按参数指纹查询） |
| `shape/ShapeCache.java` | **新增**：uuid→live ShapeData 并发缓存 + 磁盘 miss 回源。5.0.0 删除了 PlayerConfig，duck 接口无处可挂，这是它的替代品 |
| `shape/ShapeStore.java` | 原样保留（磁盘格式不变） |
| `net/ShapeSyncPayload.java` | 携带方改为 ShapeCache；新增 `broadcast(Avatar, uuid, except)` 对齐上游 `WildfireSync.sendToAllClients` 的 except 语义（`<fgm>/common/.../WildfireSync.java:42-65`） |
| `render/RenderCapture.java` | 原样保留（ThreadLocal 窗口 + 按命令弱键注册的机制在新接缝下仍然成立，见 BreastModelMixin） |
| `render/RoundBreastMesh.java` | 仅换 import（`com.wildfire.render` → `com.wildfire.client.render`）；数学零改动 |
| `render/DebugPlaneCommand.java` | 原样保留（`SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)` 与 `CustomGeometryRenderer.render(PoseStack.Pose, VertexConsumer)` 在 26.1 javap 逐字核对存在） |
| `gui/ShapeStudioScreen.java` | 构造参数 UUID→`AbstractAvatarConfigHolder`（`<fgm>/common/.../BaseWildfireScreen.java:53-57`）；`render/renderBackground` → `extractRenderState/extractBackground` + `extractTransparentBackground`；`GuiGraphics` → `GuiGraphicsExtractor`（`drawString→text`、`drawCenteredString→centeredText`，javap 核对）；控件走 `addButton/addSlider`（内部 `build(lastMSInitialized)`，5.0.0 的 Builder.build 强制带时间戳，`WildfireButton.java:155`/`WildfireSlider.java:321`） |
| `sound/HurtSoundManager.java` | 兜底音源 `WildfireSounds.FEMALE_HURT` → `ClientHelper.INSTANCE.femaleHurt().value()`（`ClientHelper.java:33-40`）；`resolveHurtSound` 返回 `Holder<SoundEvent>`；custom_hurt 走 `Registry.registerForHolder`（与上游同款）；PACK_FORMAT 75→**84**（SEAMS §0 实测） |
| mixin 六删：`BreastRenderCommandMixin`/`GenderRenderStateMixin`/`FloatConfigKeyMixin`/`NumberConfigKeyMixin`/`PlayerConfigMixin`/`WildfireEventHandlerMixin` | 目标类在 5.0.0 全部删除或改名（BreastRenderCommand 删、PlayerConfig 删、FloatConfigKey/NumberConfigKey → ConfigKey record、onEntityHurt → tryPlayHurtSound），按新接缝重写为下表 mixin |
| `mixin/GenderLayerMixin.java` | 三钩保留（setupTransformations HEAD zOffset 冻结+体视角捕获 / translate redirect 指纹分路 / TAIL perk+scale），参数签名按 5.0.0 加 `GenderRenderState genderState` 形参；shape 来源改为 `state.getData(FgmPlusClient.STATE_KEY)`；debug plane 注入点从 renderBreast 旧签名迁移到新 7 参签名 |
| `mixin/BreastModelMixin.java` | **新增**，替代 BreastRenderCommandMixin：BreastModel 构造 TAIL 注册 RenderCapture 窗口 + `renderToBuffer(PS,VC,III)` HEAD cancel 发射（扁平钳制 + roundness 超椭球），light/overlay/color 由方法参数携带 |
| `mixin/FgmPlusRenderStateMixin.java` | **新增**：`EntityRenderer.createRenderState` TAIL 挂 `STATE_KEY→ShapeData`（对齐上游 `EntityRendererMixin.java:40-48`；返回值用 `cir.getReturnValue()`，不用 @Local） |
| `mixin/BreastsMixin.java` | **新增**：`Breasts.<clinit>` 的 5 个 `ConfigKey.create(FFF)` 调用做 redirect；BUST_SIZE（指纹 0.6/0/0.8，require=1）拓宽 max→FgmPlusConfig.bustSizeMax；OFFSET_Y（ordinal=1+指纹）、OFFSET_Z（指纹）require=0 |
| `mixin/BreastsPhysicsMixin.java` | **新增**：`Breasts$Physics.<clinit>` redirect（BOUNCE 指纹 0.333/0/0.5 → bounceMultiplierMax；FLOPPINESS 0.75/0.25/1 → floppyMultiplierMax；两指纹互异无需 ordinal） |
| `mixin/HurtSoundMixin.java` | **新增**：redirect `AbstractAvatarConfigHolder.tryPlayHurtSound` 内的 `ClientHelper.hurtSound(Gender)`（`AbstractAvatarConfigHolder.java:54-67`），null 透传保留 |
| `mixin/WildfireSyncClientMixin.java` | 重写：钩 `WildfireSync.sendToServer(Connection, AbstractAvatarConfigHolder)Z` TAIL，返回值 true 且是 PlayerConfigHolder 才随包（上游 tick 循环 `<fgm>/common/.../WildfireClientEventHandler.java:159-162`） |
| `mixin/WildfireSyncServerMixin.java` | 重写：钩 `sendToAllClients(Avatar, holder, ServerPlayer)`（2 参重载委托 3 参，一钩双覆盖）与 `sendToClient(ServerPlayer, holder)` TAIL |
| `mixin/BaseWildfireScreenAccessor.java` | `playerUUID` → `config`（AbstractAvatarConfigHolder，uuid 在其上） |
| `mixin/WardrobeBrowserScreenMixin.java` | 按钮位置/尺寸不变（appearance 按钮在 height/2-63，157×20）；build 补 `Util.getMillis()` 时间戳 |
| `mixin/PackRepositoryMixin.java` | 原样保留——`PackRepository.discoverAvailable` 在 26.1 仍是私有 Map 方法且逐字含 `ImmutableMap.copyOf(Map)` 调用（javap -c 核对） |
| `fgmplus.mixins.json` | 列表同步重写（common 4 + client 7），`defaultRequire=0`、`JAVA_25` 不变 |

## 二、关键 API 换名对照（全部经克隆源码或 jar javap 核对）

| 旧（1.21.11 / FGM 5.0.0-Beta.3） | 新（MC 26.1 / FGM 5.0.0 正式版） | 证据 |
| --- | --- | --- |
| `com.wildfire.main.WildfireGender` | `com.wildfire.common.WildfireGender` | `<fgm>/common/src/main/java/com/wildfire/common/WildfireGender.java:23` |
| `com.wildfire.render.*`（GenderLayer/GenderRenderState/BreastSide/WildfireModelRenderer/BreastModel） | `com.wildfire.client.render.*` | 同目录 |
| `com.wildfire.main.entitydata.PlayerConfig` | 删除；`com.wildfire.common.entities.players.PlayerConfigHolder` + `EntityConfigHolder<AvatarConfig>` | `PlayerConfigHolder.java:37` |
| `PlayerConfig.uuid` / `PlayerConfig.needsSync` | `holder.uuid`（EntityConfigHolder）/ `holder.needsSync`（PlayerConfigHolder，仍 volatile public） | `EntityConfigHolder.java:47`、`PlayerConfigHolder.java:41` |
| `WildfireGender.getOrAddPlayerById` | `com.wildfire.api.client.WildfireClientAPI.players().getOrCreate(uuid)` | `WildfireClientAPI.java` |
| `GenderRenderState.get(state)`（静态查表） | `state.getData(RenderStateDataKey)`（Fabric interface injection，`FabricRenderState.setData/getData`） | `FabricClientHelper.java:37,52-54`；classtweaker `transitive-inject-interface ...EntityRenderState ...FabricRenderState` |
| `setupTransformations(state, model, matrixStack, side)` | `(state, model, genderState, matrixStack, side)` | `GenderLayer.java:181` |
| `renderBreast(state, matrixStack, queue, overlay, side)` | `(state, genderState, poseStack, nodeCollector, lightCoords, overlayCoords, side)`（仍 private） | `GenderLayer.java:237` |
| `BreastRenderCommand`（命令对象 + consumerOperator） | 删除；几何发射统一走 `BreastModel extends Model<HumanoidRenderState>`（Model.renderToBuffer 被上游 classtweaker `extendable` 放行覆写） | `female_gender_mod.classtweaker`、`BreastModel.java` |
| `WildfireModelRenderer` 嵌套类 | 不变但包名变；`addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)` 10 参复合仍在（VertexConsumer default 方法，javap 核对） | `WildfireModelRenderer.java:39-60` |
| `PlayerConfig.loadFromDisk` 钩子 | 删；ShapeCache 磁盘 miss 回源（渲染态每帧重建，天然等价 per-frame 刷新） | 本文 ShapeCache |
| `WildfireSync.sendToServer(PlayerConfig)void` / `sendToAllClients(ServerPlayer, PlayerConfig)` | `sendToServer(Connection, AbstractAvatarConfigHolder)boolean` / `sendToAllClients(Avatar, AbstractAvatarConfigHolder, @Nullable ServerPlayer)` / `sendToClient(ServerPlayer, AbstractAvatarConfigHolder)` | `WildfireSync.java:64-88,42-62` |
| `Gender.getHurtSound()`（WildfireEventHandler.onEntityHurt 内） | `ClientHelper.hurtSound(Gender)`（AbstractAvatarConfigHolder.tryPlayHurtSound 内，返回 `Holder<SoundEvent>`） | `AbstractAvatarConfigHolder.java:59` |
| `WildfireSounds.FEMALE_HURT`（SoundEvent 常量） | `ClientHelper.INSTANCE.femaleHurt()`（Holder，`Registry.registerForHolder` 注册） | `FabricClientHelper.java:41-49` |
| `FloatConfigKey/NumberConfigKey.getMinInclusive/validate` 双钩 | `ConfigKey.create(FFF)` 单点 redirect（record 出生即带 ConfigRange，codec/slider/validate 三路同源） | `ConfigKey.java:103-105`、`ConfigRange.java`、`WildfireSlider.java:275-282` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()/clientboundPlay()` | fabric-networking-api-v1 6.3.x javap |
| `GuiGraphics.drawString/drawCenteredString/fill` | `GuiGraphicsExtractor.text(Font,Component,int,int,int,boolean)/centeredText(Font,String,int,int,int)/fill` | MC 26.1 merged jar javap |
| `Screen.render/renderBackground/renderTransparentBackground` | `Screen.extractRenderState/extractBackground/extractTransparentBackground` | 同上（`extractRenderState(GuiGraphicsExtractor,int,int,float)`、`extractTransparentBackground(GuiGraphicsExtractor)`） |
| `BaseWildfireScreen(title, parent, playerUUID)` | `(title, parent, AbstractAvatarConfigHolder)`；`playerUUID` 字段没了，`config`（protected final）替代 | `BaseWildfireScreen.java:48-57` |
| `WildfireButton/WildfireSlider.Builder.build()` | `build(long msInitialized)`（BaseWildfireScreen 的 addButton/addSlider 自动传） | `WildfireButton.java:155`、`WildfireSlider.java:321` |
| `BreastSide.isLeft`（public→private） | `side == BreastSide.LEFT` | `BreastSide.java:23-26` |
| `minecraft.setScreen(...)` | 仍存在（`Minecraft.setScreen(Screen)` javap 核对；上游走 `minecraft.gui.setScreen` 的 Gui 注入接口，我们不需要） | MC 26.1 javap |
| pack format 75 | 84 | SEAMS §0（client jar version.json） |

## 三、mixin 集清单（目标类 / 方法 / 注入点 / require）

| Mixin | 目标 | 注入点 | require | 侧 |
| --- | --- | --- | --- | --- |
| `BreastsMixin` | `com.wildfire.common.entities.Breasts` | `<clinit>` @Redirect `ConfigKey.create(FFF)` ordinal=3（BUST_SIZE，指纹 0.6/0/0.8） | 1 | common |
| `BreastsMixin` | 同上 | `<clinit>` @Redirect 同 target ordinal=1（OFFSET_Y，指纹 0/-1/1） | 0 | common |
| `BreastsMixin` | 同上 | `<clinit>` @Redirect 同 target ordinal=2（OFFSET_Z，指纹 0/-1/0） | 0 | common |
| `BreastsPhysicsMixin` | `Breasts$Physics` | `<clinit>` @Redirect 同 target（指纹分路 0.333/0/0.5 与 0.75/0.25/1） | 0 | common |
| `HurtSoundMixin` | `AbstractAvatarConfigHolder` | `tryPlayHurtSound` @Redirect INVOKE `ClientHelper.hurtSound(Gender)` | 1 | common |
| `WildfireSyncServerMixin` | `WildfireSync` | `sendToAllClients(Avatar;AbstractAvatarConfigHolder;ServerPlayer)V` TAIL；`sendToClient(ServerPlayer;AbstractAvatarConfigHolder)V` TAIL | 1/1 | common |
| `BaseWildfireScreenAccessor` | `BaseWildfireScreen` | @Accessor `config` | — | client |
| `BreastModelMixin` | `BreastModel` | `<init>(WildfireModelRenderer$ModelBox;)V` TAIL；`renderToBuffer(PS;VC;III)V` HEAD cancel | 1/1 | client |
| `FgmPlusRenderStateMixin` | `EntityRenderer`（vanilla） | `createRenderState(Entity;F)` TAIL | 1 | client |
| `GenderLayerMixin` | `GenderLayer` | `setupTransformations` HEAD（zOffset 冻结 + 体视角捕获）+ @Redirect `PoseStack.translate(FFF)V`（hang/main 指纹分路）+ TAIL（perk+scale）；`renderBreast` TAIL（debug plane） | 1/1/1/1 | client |
| `PackRepositoryMixin` | `PackRepository`（vanilla） | `discoverAvailable` @Redirect INVOKE `ImmutableMap.copyOf(Map)` | 0 | client |
| `WardrobeBrowserScreenMixin` | `WardrobeBrowserScreen` | `init()V` TAIL | 1 | client |
| `WildfireSyncClientMixin` | `WildfireSync` | `sendToServer(Connection;AbstractAvatarConfigHolder)Z` TAIL（return 值门控） | 1 | client |

纪律自查：@Unique 字段全部 private；每个 @Redirect 带参数值指纹守卫（Breasts clinit 5 个 create 调用只命中目标指纹，X/Y 同参的 OFFSET_Y 额外用 ordinal=1 并在注释里写明降级后果）；核心路径 require=1；`defaultRequire=0` 保留；handler 擦除签名与目标一致（redirect 静态方法用纯参数表、实例方法首参收接收者）。

## 四、渲染接缝（与 SEAMS §1 的差异说明）

SEAMS 原方案是「mixin renderBreast HEAD cancel + 自建 RoundBreastModel extends Model」。实施时改走**更保守的等价路线**：不 cancel renderBreast，改钩 `BreastModel`（上游 renderBreast/renderArmor 实际提交的 Model 子类）的构造 + renderToBuffer——理由：

1. `Model.renderToBuffer` 在 26.1 原版 jar 是 `ACC_FINAL`（javap -v 核对），上游靠自家 classtweaker `extendable` 才能覆写；我们复刻它就得引入自己的 accessWidener。钩 BreastModel 只做注入（注入不覆写），无需 AW。
2. renderBreast cancel 需要自己重算 `isBodyVisible/getRenderType`（两者都是 protected，同样要 AW），还要复刻 baseColor/jacket 分支；BreastModel 路线下这些全部由上游自己完成，forceTransparent 颜色、glowing、jacket 的 order(1) 都免费继承。
3. `Model.renderToBuffer` 是 final 但注入合法；且上游 armor 路径（`GenderArmorLayer.renderArmor:79` 的 `new BreastModel(...)`）与 jacket 路径同样构造 BreastModel，构造钩天然覆盖全部 pass，layerIndex 语义与 1.21.11 的 BreastRenderCommand 完全同构。

per-player shape 透传按任务规格落实：`HumanoidRenderState` 在 26.1 **没有** uuid 字段（javap 核对），故自建 `RenderStateDataKey<ShapeData>`（不是 ShapeRenderState——它只有 static debugPlane，挂它没有信息量；ShapeData 才是有效载荷）。DataKey 与 shape 数据分离：`ShapeRenderState.debugPlane` 保持 static 共享，不经 DataKey。

## 五、离线 harness（_probe/）

- `RoundMeshProbe`（纯数学）：原样复用，对 26.1 端 RoundBreastMesh 跑 22 断言 ALL PASS。
- `RoundMeshHarness`（真盒）：import 换到 `com.wildfire.api.uvs.UVLayout/UVQuad` + `com.wildfire.client.render.WildfireModelRenderer`（UVLayout 构造签名 `(east, west, down, up, north)` 与 UVQuad record 在 5.0.0 逐字一致，`UVLayout.java:51`/`UVQuad.java:30`）；断言集与 1.21.11 对齐：nonempty-mesh / quad-count=74（4x5x3 按几何边长细分）/ cache-stable / cache-invalidates / envelope / normals-and-uvs / curvature-applied（n=2/r 理论值 sqrt(3^0.15)）/ cleavage-bridge（bridged 越线、plain 不越线——同时证明缓存键含 cleavage 位）/ overlay-mesh-nonempty。ALL PASS，quadCount=74 与 1.21.11 端逐位一致。
- classpath：FGM `5.0.0+mc26.1-fabric.jar` + `minecraft-merged-26.1.jar` + 平台 build/classes + `_probe/lib/*`；javac/java 用 `C:\Program Files\Microsoft\jdk-25.0.5.21-hotspot\bin\`；路径全部 `C:/`、`D:/` 风格。
- 本轮在 lib 追加的 26.1 依赖（1.21.11 不需要）：`gson-2.13.2`（26.1 ExtraCodecs clinit 需要它+commons-lang3）、`commons-lang3-3.20.0`（ExtraCodecs 的 StringEscapeUtils）、`fabric-loader-0.19.5`（我们的 RoundBreastMesh 引 FgmPlusMod.LOGGER → ModInitializer）。authlib 未被触达，未换版本。

## 六、妥协与未决项

1. **BreastsMixin 的 OFFSET_X/OFFSET_Y 同参歧义**：两者的 `(0,-1,1)` 指纹相同，只能靠 ordinal=1 区分（源序）。若上游将来调换 X/Y 声明顺序，拓宽会落到 X 而非 Y——守卫使参数仍合法，纯属 cosmetic 降级，require=0 不炸。BUST_SIZE（核心）指纹唯一，require=1 硬校验。
2. **ShapeStudioScreen 的 offsetZ/roundness tooltip**：addSlider 帮助方法吞掉返回值后的第二个滑条无法直接拿句柄，已改为捕获 `addSlider` 返回的 WildfireSlider 再 setTooltip，与 1.21.11 行为一致（已核对 AbstractWidget.setTooltip 在 26.1 仍在）。
3. **服务端 ShapeCache 不落盘**：1.21.11 端服务端也不落盘（PlayerConfig 内存缓存），行为等价；远程玩家磁盘文件只在客户端渲染端被懒加载。
4. **BreastModelMixin 的 armor trim/glint 多次渲染**：同一 BreastModel 实例若被管线以不同 consumerOperator 复用多次（trim 等装饰），将复用同一捕获窗口——与 1.21.11 的 per-command 注册语义相同，未观察到回归路径；上游 5.0.0 的 BreastModel 已不带 consumerOperator，说明 trim 装饰不再走模型内包装。
5. **上游 classtweaker 的 `InventoryScreen.extractRenderState` accessible**：我们未用到（renderPlayerInFrame 经 BaseWildfireScreen 继承获得），无需引入任何 AW。
6. **dev smoke（FGMPLUS_QUICKPLAY 进世界）未跑**：本交付仅到 artifact smoke（build + 离线 harness）；进世界的渲染冒烟与 26.2 平台复制留待后续（SEAMS §8：26.2 与 26.1 同分支，可整目录复制 + pack format 调整）。

## Code review 修复记录（2026-11-07 第二轮）

- **P1-1**：`FgmPlusRenderStateMixin` 改为 player-only 门（原来所有 humanoid 生物都进 ShapeCache：无界增长 + 渲染线程首帧磁盘 miss）。对齐上游 EntityRendererMixin 的"只挂数据给被跟踪实体"思想。
- **P2-1**：删除 `FgmPlusConfig.widenedMin/widenedMax` 死代码及其误导注释（26.1 消费方是 BreastsMixin/BreastsPhysicsMixin 直接读字段）。
- **P2-2**：`WildfireSyncServerMixin` 双入口加 `instanceof PlayerConfigHolder` 门，人偶不再触发 shape 广播/缓存条目。
- **P2-3**：`GenderLayerMixin.fp$capture` 补 `model.root().translateAndRotate` 等效项（root 在 body 之前、26.1 同款 /16 + 守护旋转）；vanilla humanoid root=0 时恒等，防上游将来挂非恒等 root。
- **P2-5**：RoundBreastMesh 缓存槽 3→8（4+ 人同屏异 roundness 不再每帧全量重建）。
- **P2-4 有意偏离**：HurtSoundMixin / WardrobeBrowserScreenMixin 保持 require=1，与 1.21.11 兄弟端同类注入一致（受伤音、工作室入口属功能主干；静默缺失比崩栈更难诊断）。

## 26.2 同构复制差异（2026-11-07）

- vanilla API 漂移：26.2 把当前屏状态从 `Minecraft.screen` 字段 + `Minecraft.setScreen()` 挪到 `Minecraft.gui`（`net.minecraft.client.gui.Gui`）——`gui.screen()` getter + `gui.setScreen(Screen)`（javap 实测：26.1 Minecraft 有 `public Screen screen; setScreen(...)`，26.2 只剩 `setScreenAndShow`）。26.2 平台三处改动：WardrobeBrowserScreenMixin:37（gui.setScreen）、HurtSoundManager:177/192（gui.screen()）。26.1 保持原 API（26.1 上 `Minecraft.screen` 仍在）。
- 其余源码与 26.1 逐文件相同（上游 26.1/26.2 无 java 分歧）。

## 11-07 追加：Photon 光影下圆度 mesh 全黑（实机报告 #1）

- 症状：Photon 1.4 + 圆度>0 → 胸部 mesh 全黑+噪点；圆度=0 或关光影正常。用户实参：bustSize 0.964（s=1.164）、shape scale (1.29/1.11/1.51) 非均匀、roundness 0.158、cleavage 1.0。
- 根因链（全部有实据）：① Iris `MixinBufferBuilder#fillExtendedData`（_iris_src common/src/main/java/net/irisshaders/iris/mixin/vertices/MixinBufferBuilder.java:241-260）对 in-world 实体**逐 quad 用顶点位置重算面法线并覆写**我们写入的逐顶点法线（`recalculateNormal = ImmediateState.isRenderingLevel`）；② 重算=对角叉积 `(v2-v0)x(v3-v1)` + JOML normalize（`NormalHelper.computeFaceNormalManual`）——被逐顶点拉回压扁的弯曲 quad 重算出**旋转 40-60° 的错方向法线**（离线仿真 DegenerateQuadProbe：8/74 quad 被拉、可见的 3 个全在底/外缘，与截图噪点位置吻合）；③ Photon `gbuffers_all_solid` 延迟管线用 gbuffer 法线做方向性光照+阴影 bias（fsh:449-458 `get_directional_lightmaps(scene_pos, normal)`），错法线→光照≈0→黑+dither 噪点；vanilla `minecraft_mix_light` 有 0.4 环境光底且不 normalize（26.2 entity.vsh + light.glsl 反编译）→ 同样的坏法线在 vanilla 只是轻微变暗，看不出来。physics 弹跳（绕 Y 旋转，floppiness 0.75）逐帧改变拉回集合 → 黑区随帧变化。flat 端只有背面不可见 quad 被拉，所以 flat+Photon 正常。
- 修复：`BreastModelMixin` mesh 分支**按 quad 刚性拉回**（整 quad 平移其最深顶点的深度→形状不变→重算法线不歪）；flat 分支保持逐顶点（平面 quad 拉回后仍共面）。新增 config：`meshPullMode`（per_quad 默认/per_vertex/off 三态 A/B）+ `meshDebugLog`（5s 节流的拉回统计 INFO 日志）。26.1 平台同构同步。
- 修复后：26.1/26.2 build 绿，RoundMeshProbe/RoundMeshHarness 回归 ALL PASS，26.2 jar 部署至实例（sha256 653ceea9…双端一致）。
- 若 per_quad 仍黑：让用户开 `meshDebugLog` 收拉回统计，再按 Photon #184 家族（NORMAL_MAPPING off 实验）分流。

## 11-07 追加2：per_quad 无效 → 二号嫌疑=阴影贴图曲面自遮蔽（acne）
- per_quad 修复后用户实测仍黑 → 法线拉伸理论不是主因（或非全部）。
- 新头号嫌疑：**vanilla 实体几何全是平面盒** → 各光影包的阴影贴图 normal-bias（`get_filtered_shadows(position_scene, flat_normal, …)`）按平面深度梯度调校；圆度 mesh 是全游戏唯一曲面实体几何，凸面在阴影贴图里自深度逐 texel 变化超出 bias 补偿 → 自遮蔽黑斑 + PCF 噪点。吻合全部现象：flat 平面完美补偿、vanilla 无阴影贴图、per_quad 无关、噪点=PCF/IGN 抖动、黑区随物理逐帧变。1.21.11+Photon+圆度从未实测过，按此理论也会黑（此前验收只做了 vanilla）。
- 修复：BreastModelMixin 检测 Iris 阴影 pass（反射 `IrisApi.getInstance().isRenderingShadowPass()`，Iris 缺席安全降级+负缓存），**阴影 pass 内圆度 mesh 退化为平面盒**（影子轮廓仍胸部形，bias 重新完美补偿；本体不动）。config `meshShadowFlat` 默认 true。
- 部署 ba49c0d8…。若仍黑：①用户跑 Photon 关阴影实验（确认 acne）②关 NORMAL_MAPPING 实验（#184 家族=法线贴图采样）③收 meshDebugLog 拉回统计。

## 11-07 追加3：三连修复无效 → 转向 A/B 二分定位
- 阴影压平（meshShadowFlat）实测也无效。三个理论（法线拉伸/阴影acne/…）均未命中，停止源码推理，改**配置开关二分**：mesh 与 flat 路径的差异逐项做成开关。
- 新增诊断开关（FgmPlusConfig，改 json 后**重启游戏**生效）：`meshDebugNoMorph`（细分网格留在原盒面位置+面法线，跳过超椭球投影与桥接）、`meshDebugNoBridge`（只关乳沟桥接）；已有 `meshPullMode: off`（无贴背拉回）、`meshShadowFlat`。
- 缓存键纳入 noMorph 位（+20）。RoundBreastMesh 新增 resolvePoint 分流。回归 ALL PASS。部署 d426453b。
- 用户测试矩阵：①noMorph=true ②noBridge=true ③meshPullMode=off ④meshShadowFlat=false，各自是否仍黑；结果决定下一层二分方向（noMorph 仍黑→问题在发射/细分/UV/顶点数；noMorph 变好→问题在形变/桥接/拉回）。
- 兜底方案（若二分仍无果）：检测 shader 包启用时圆度退化（功能性降级，最后手段）。

## 11-07 追加4：noMorph 仍黑 → 二分收敛到「细分发射」层
- 用户实测 meshDebugNoMorph=true 仍黑（且符合预期模型不变圆）→ 形变/桥接/拉回全部排除，问题在细分发射本身（与 flat 路径的数据逐像素等价、Iris 扩展属性模拟全干净）。
- IrisSim 离线复算 Iris fillExtendedData（重算法线+切算切线+midUV）：flat/mesh × skin/overlay 全部 0 garbage。
- 新增二分开关：`meshDebugMaxQuads`（N=-1 全部；逐级调小定位黑斑出现的 quad 数阈值）、`meshDebugFullUv`（每个子 quad 用源面四角 UV 直传，纹理糊但隔离 UV 插值）。缓存键 +40 位。
- 部署（见哈希）。判读：maxQuads=1 仍黑→计数无关、只剩 UV 值→再结合 fullUv；fullUv 变好→UV 插值触发；maxQuads 存在阈值→buffer/计数层级问题。

## 11-08 子代理全面分析结论 + 热重载
- 子代理（读 Iris 26.2 实装 jar 反编译/Sodium 0.9.2/Photon 1.4 设置实况）：①用户 Photon 设置 NORMAL_MAPPING/PBR 全关→#184 家族判死；②entityId 逐顶点同值→mask 判死；③shadow 展平测试有效→shadow 判死；④staging 4MB 无扩容问题；⑤**实数探针 0 退化法线**。唯一活变量=quad 数/Draw 装配层（H1）；H2=meshPullMode=off 从未测；H3=Sodium push 与 vanilla 混写的 fillExtendedData 组错位（skinlayers/trender 交互嫌疑）。
- 修复候选（子代理提出）：mesh 改走 Sodium push 路径（VertexBufferWriter.push，玩家身体走此路正常，绕过 Iris 逐 quad 重算且保留平滑法线）——待 H1/H3 判定后实施。
- 新增：FgmPlusConfig.maybeReload()（mtime 检查，1s 节流，render 线程调用）→ **调试开关热重载，改 json 不用重启**；maxQuads 移到 BreastModelMixin 发射侧（避免缓存陈旧）。部署见哈希。

## 11-08 追加6：Sodium push 发射路径（第 4 次修复尝试）
前三次（per-quad 拉回 / shadow 展平 / maxQuads 热重载矩阵）均未消黑。转实施子代理头号候选：mesh 顶点改走 Sodium `VertexBufferWriter.push` 内存直写——Iris `MixinBufferBuilder#fillExtendedData` 的逐 quad 法线重算只挂在 vanilla 链式 addVertex 路径上，push 直达 StagedVertexBuffer，平滑法线原样保留；玩家身体（CubeMixin）一直走这条路且 Photon 下正常。

探针钉死的 API（javap sodium-fabric-0.9.2+mc26.2.jar）：
- `VertexBufferWriter.of/tryOf(VertexConsumer)`；`push(MemoryStack, long ptr, int count, VertexFormat)`（同格式 memcpy，异格式走 VertexSerializerRegistry 动态转换）
- `EntityVertex.write(ptr, x,y,z, color, u,v, overlay, light, normalPacked)`；FORMAT=DefaultVertexFormat.ENTITY，STRIDE=36
- 法线打包 = BufferBuilder#normalIntValue：`(int)clamp(-1,1)*127 & 255`，字节序 x|y<<8|z<<16（minecraft-merged 26.2 javap 核对）
- push 实现：`net/.../sodium/mixin/core/render/immediate/consumer/BufferBuilderMixin.class`

实现：BreastModelMixin.fp$emitPush——反射软依赖（Sodium 缺席/tryOf 失败/任何 Throwable → 静默回退旧链式发射）；32 quad 分块 push 防 MemoryStack 溢出；meshPushPath 配置开关（默认 true，热重载）。注意：push 中途失败不回退（防重复几何），实际风险极低。
构建双平台绿；26.2 部署 055c20b2（旧包备份 6e31fbe6）。判别实验：若 Photon 下胸部恢复 → 根因锁定 Iris vanilla 路径逐 quad 重算；若仍黑 → 根因在 Draw 层更深（H1），只能上游报。

## 11-08 追加7：诊断结束 → 换写法 texel 中心 UV（c8572020）
诊断期教训（重大）：
1. flatNormal/fullLight/skinOnly/shadowNone/float 五个判别开关的 load/save 接线从未写入 FgmPlusConfig → 配置翻键全部空转，对应几轮「还黑」不是测试结果。
2. 部署时游戏在运行会导致 cp 覆盖失败/旧 jar 残留（662d2537 崩溃轮即旧 jar + 新 dump 代码混跑的疑点）。
3. float 位移试验：渲染空间单位=px（v0≈-3.7 为 px），0.5/16px 位移不可见，128px 出屏。
真正有效的实测：noMorph（圆度失效根因=残留 true）、fullUv（胸部被照亮！唯一证明「黑在 UV 采样侧」的实证）、meshPullMode=off（仍黑）、push 路径不可见（builder 内数据正常但不上屏，判死）。
换写法：RoundBreastMesh 新增 meshTexelCenterUv（默认 true）——每个子 quad 四角 UV 塌缩到所覆盖 texel 的中心（texW/texH 从源面像素矩形 vs 归一化角 UV 反推，兼容 HD 皮肤），采样彻底避开 atlas texel 边界；保留 1:1 texel 映射，观感与 vanilla flat box 一致。同时删除 push 全家（emitPush/反射/日志群）与五个死开关，BreastModelMixin 回归纯链式发射。26.2 部署 c8572020。
