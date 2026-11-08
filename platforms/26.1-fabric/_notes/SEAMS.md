# 26.1/26.2-fabric 接缝规格（P1 调研产出，2026-11-07）

上游基线：FGM **5.0.0 正式版**（2026-09-30），源码 `D:\Claude_ds\_fgm500_research`（tag 5.0.0 浅克隆）。
jar 缓存（classpath/探针用）：`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/female-gender/5.0.0+mc26.1-fabric/...`

## 0. 工具链（实测钉死）

- **fabric.loom.disableObfuscation=true**（loom 源码 `Constants.Properties.DISABLE_OBFUSCATION`）→ mappings 环节整体跳过；不设则 `Configuration 'mappings' has no dependencies` 红
- **modImplementation 已不存在**：mod 依赖走普通 `implementation`（FGM 5.0.0 fabric/build.gradle.kts 同款）；`minecraft "com.mojang:minecraft:26.1"` 照旧
- Gradle **9.7.1**（loom 1.17.21 要求插件 API ≥9.5，9.2.0 拒绝）；wrapper 腾讯镜像
- loom **1.17.21**（1.17-SNAPSHOT 解析结果）；JDK **25**：`C:\Program Files\Microsoft\jdk-25.0.5.21-hotspot`（winget Microsoft.OpenJDK.25，已写进平台 gradle.properties 的 org.gradle.java.home）
- pack format：26.1 **resource=84 / data=101**，26.2 **resource=88 / data=107**（client jar version.json `pack_version` 字段；piston-meta 已无此字段）
- MC java 版本 = 25（piston-meta javaVersion.majorVersion 实测，与上游 java.version=25 互证）
- FGM 5.0.0 fabric.mod.json：id=**female_gender_mod**（旧 id wildfire_gender 已废弃，上游声明旧 id 不兼容），minecraft dep `>=26.1 <26.2`，loader `>=0.19.5`，java `>=25`

## 1. 渲染接缝（相对 1.21.11 端 = 大简化）

类：`com.wildfire.client.render.GenderLayer`（common/src/main/java/com/wildfire/client/render/GenderLayer.java）

链路：`GenderLayer.submit(PoseStack, SubmitNodeCollector, light, STATE, ...)` → `setupRender`（读 GenderRenderState、resizeBox）→ `renderSides`（每 BreastSide：pushPose → **setupTransformations** → renderer.render → popPose）→ `renderBreast`（private）→ `nodeCollector.submitModel(new BreastModel(box), state, poseStack, type, light, overlay, baseColor, null, outlineColor)`。

**capture 方案：mixin `renderBreast` HEAD 即可拿到全部所需**（已变换 poseStack、nodeCollector、light/overlay、side、genderState）——1.21.11 的 setupTransformations capture + BodyWindow 静态透传机制**整体不需要**。renderBreast 签名（GenderLayer.java:214）：
```java
private void renderBreast(STATE state, GenderRenderState genderState, PoseStack poseStack,
                          SubmitNodeCollector nodeCollector, int lightCoords, int overlayCoords, BreastSide side)
```
- RenderType/color 取法（我们要复刻）：`renderer.isBodyVisible(state)` / `state.isInvisibleToPlayer` → forceTransparent；`renderer.getRenderType(state, bodyVisible, forceTransparent, glowing)`；baseColor = forceTransparent ? 0x26FFFFFF : WHITE（CommonColors.WHITE）；renderer = `(LivingEntityRenderer<?,STATE,?>) context`（context 字段 private，可 @Shadow）
- 皮肤盒字段：`lBreast/rBreast`（BreastModelBox）、`lBreastWear/rBreastWear`（OverlayModelBox，jacket），protected 可 @Shadow；jacket 分支 = translate(0,0,-0.015f) + scale(1.05f) 后再 submit
- cancel 方式：@Inject HEAD + cir.cancel()，然后自己 submitModel 我们的 RoundBreastModel
- RenderType 默认 `RenderTypes::entityCutout`（BreastModel 构造），mesh 无纹理走我们自己的 type 或沿用上游 type

顶点写出（WildfireModelRenderer.renderBox, WildfireModelRenderer.java:39）：
```java
vertexConsumer.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);  // 10 参复合
```
- 位置：`Vector4f(j,k,l,1).mul(matrix4f)`（j=vertex.x()/16f），法线 `Vector3f(normal).mul(matrix3f)`
- UV 零值守卫：quad.uvs 全 0 则跳过该 quad
- 几何类都在：`WildfireModelRenderer.BreastModelBox/OverlayModelBox/ModelBox/TexturedQuad/PositionTextureVertex`（嵌套类！import 用 `com.wildfire.client.render.WildfireModelRenderer.BreastModelBox`）；box 尺寸 4×5×3，posX1..posZ2 可读
- BreastModel extends Model<HumanoidRenderState>，DUMMY_PART 空 HumanoidModel.createMesh；我们 RoundBreastModel 照抄外壳

GenderRenderState（public 字段直接读）：breasts(BreastState: offsets/cleavage/bustSize 物理后的值)、uniboob、bustSize、leftBreastPhysics/rightBreastPhysics(getPositionX/Y/getBounceRotation/getBreastSize 已 lerp)、leftBreastUVLayout/rightBreastUVLayout + Overlay 版（com.wildfire.api.uvs.UVLayout）、armor(IGenderArmor)、hasJacketLayer、isBreathing、gender。构造挂在 fabric EntityRendererMixin（createRenderState TAIL，RenderStateDataKey `FabricClientHelper.STATE`）。
- **我们的 per-player shape 透传**：同款 RenderStateDataKey<ShapeRenderState>（shared 类）在 createRenderState TAIL 挂 entity.getUUID()→ShapeStore 查询结果

## 2. 滑条拓宽（单点方案）

`ConfigKey` 是 record（common/config/value/ConfigKey.java）：`(Either defaultSupplier, Codec codec, StreamCodec streamCodec, ConfigValidator validator)`。
- `Breasts.BUST_SIZE = ConfigKey.create(0.6F, 0, 0.8F)`（Breasts.java:55）；BREASTS_CLEAVAGE create(0,0,0.1F)（:56）
- clinit 里 CODEC/PROMOTER 提前捕获 validator（Breasts.java:60-67）→ 事后换静态字段无效（老 FGM5 教训复现）
- **方案：@Mixin(Breasts.class) @Redirect 打在 clinit 的 `ConfigKey.create(0.6F, 0, 0.8F)` 调用**，返回加宽后的 ConfigRange 键对象；codec/slider/validate 三路消费者从出生就吃到拓宽值，单点解决。descriptor 与参数指纹守卫必带（同 clinit 有 5 个 create 调用）
- 消费端：`WildfireSlider.java:276` `validator instanceof ConfigRange<Float>(Float min, Float max)` 模式匹配取范围 → 拓宽自动生效
- ConfigRange（common/config/validator/ConfigRange.java）：`validate` 范围检查 + `codecValidation` clamp/promote，都读自身字段 → 出生即拓宽则两层天然放行（无需第二层 mixin，与 FGM5 Beta 时代的双层拦截不同——但 code-reviewer 需复核无第三处裸字段 clamp）

## 3. 玩家数据 / 存储

- `com.wildfire.entitydata.PlayerConfig` **已删**。新：`common/entities/players/PlayerConfigHolder.java`（volatile needsSync/needsCloudSync + per-uuid Configuration 文件）、`common/entities/avatars/AvatarConfig.java`（STREAM_CODEC/COMPACT_STREAM_CODEC）、`common/entities/EntityConfigHolder.java`、client 读 `com.wildfire.api.EntityCache.get(UUID)/getOrCreate` 或 `WildfireClientAPI.players()`、`WildfireClientAPI.getConfig(LivingEntity)`（EntityRendererMixin 同款）
- 读写：`holder.breasts().bustSize().get()/.update(v)`；`holder.gender()`；Breasts 字段：bustSize/xOffset/yOffset/zOffset/cleavage ConfigValue<Float> + Physics
- 我们 ShapeStore 挂点：自己的文件目录照旧（config/fgmplus/shapes/<uuid>.json），渲染端经 ShapeRenderState 透传；FGM 配置目录不再是接缝

## 4. 网络

- 上游：`fabric/.../networking/FabricSync.java` —— PayloadTypeRegistry 双向注册 + 配置期 hello 握手（SyncHelloPacket.VERSION，ServerConfigurationNetworking）；play 期 receiver 由 `versionMatches(connection)` 门控
- 我们的 `fgmplus:shape_sync` payload：playC2S+playS2C 双向注册（PayloadTypeRegistry.playBidirectional 若可用则单注册）；StreamCodec<RegistryFriendlyByteBuf?> 按新 API 核对；C2S 保留 sender-uuid 校验语义（对齐上游 ServerboundSyncPacket）
- StreamCodec 泛型：AvatarConfig.COMPACT_STREAM_CODEC 用 StreamCodec<ByteBuf,..>（ConfigKey record 自带 streamCodec 字段，netty ByteBuf 血统）——shared ShapeData 的 netty wire 兼容性高

## 5. 音效

- 上游自己接管受伤音：`common/client/mixins/LivingEntityMixin` @Inject(handleDamageEvent, INVOKE playSound) → tryPlayHurtSound；Fabric 侧 FabricClientHelper.registerSounds() 注册 variable-range `female_hurt`
- 我们 HurtSoundManager 接缝：同款 INVOKE 点或拦 `AbstractAvatarConfigHolder.tryPlayHurtSound`（common/entities/avatars/AbstractAvatarConfigHolder.java）——实施时二选一探针定

## 6. GUI（26.1 范式）

- `Screen#render` → **`extractRenderState(GuiGraphicsExtractor graphics, mouseX, mouseY, delta)`**；背景 `extractBackground`；BaseWildfireScreen.java:167/182 有实例
- 上游屏：WildfireBreastCustomizationScreen / WardrobeBrowserScreen / BaseWildfireScreen（common/client/gui/screen/）；控件 WildfireButton/WildfireSlider（common/client/gui/）
- GuiGraphicsExtractor 方法名集实施时按 javap/源码核对（勿凭 1.21.11 GuiGraphics 印象拼调用）
- 造型工作室 ShapeStudioScreen = 本轮最大单体重写项

## 7. 已知不复用清单（相对 1.21.11-fabric）

- render/RenderCapture.java（BodyWindow）——整套机制废除
- mixin: BreastRenderCommandMixin / GenderRenderStateMixin / WildfireEventHandlerMixin / FloatConfigKeyMixin / NumberConfigKeyMixin / PlayerConfigMixin / WildfireSyncClientMixin / WildfireSyncServerMixin —— 全部按新接缝重写
- PackRepositoryMixin（available ImmutableMap redirect）——26.1 是否还有此坑未知，实施时探
- BaseWildfireScreenAccessor / WardrobeBrowserScreenMixin —— 按新屏类重探

## 8. 26.1 vs 26.2 差异

上游仅 stonecutter `<=26.2` 分支内小差异（mulPose vs rotateDegrees 等），对 26.1/26.2 两者完全同分支 → 两平台源码同构，先做 26.1，26.2 同源复制。
