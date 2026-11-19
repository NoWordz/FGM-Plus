package io.github.e33epus.fgmplus.mixin;

import com.mojang.serialization.DataResult;
import com.wildfire.common.config.validator.ConfigRange;
import com.wildfire.common.entities.Breasts;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 胸围上限解除：把 FGM 26.2 五个身材配置键的边界换成 FgmPlusConfig 里的拓宽值。
 *
 * <p>26.2 的校验体系（替代 1.21.11 的 Float/NumberConfigKey）全部汇聚到每个键持有的
 * 那一个 {@link ConfigRange} 实例上：衣柜滑条的范围（WildfireSlider#range 的
 * record 解构读取 minInclusive()/maxInclusive()）、活值写入校验
 * （KeyBackedConfigValue#update → validate）、磁盘/网络编解码钳位
 * （ConfigKey 构造时把 codecValidation 包进 codec/streamCodec）、模特命令参数
 * （MannequinComponents#boundedFloat）与旧组件的 floatRange（BreastDataComponent）
 * 都从这里取值。拦这一个类的四个入口，等于同时放宽显示端和写入校验，
 * 不会再出现"滑条拖得动、存档时被弹回"的老毛病。</p>
 *
 * <p>ConfigRange 是通用 record，实例本身不带键名，因此按<b>对象身份</b>匹配：
 * 懒建一张 IdentityHashMap，把 Breasts.BUST_SIZE / BREASTS_OFFSET_Y /
 * BREASTS_OFFSET_Z 和 Physics.BOUNCE_MULTIPLIER / FLOPPINESS 各自的 validator()
 * 实例映射到 FgmPlusConfig 的边界表键名。未命中的范围（比如乳沟 0..0.1）原样放行；
 * 在 Breasts/Physics 类初始化尚未完成时读到 null 则不缓存、按原版边界处理。</p>
 */
@Mixin(value = ConfigRange.class, remap = false)
public abstract class ConfigRangeMixin {

	//validator 实例 -> FgmPlusConfig.widenedMin/Max 的键名；懒建，volatile 发布（良性竞争，最多多建一份）
	@Unique private static volatile Map<Object, String> fgmplus$targets;

	@Unique private static String fgmplus$keyName(ConfigRange<?> self) {
		Map<Object, String> map = fgmplus$targets;
		if(map == null) {
			try {
				Object bust = Breasts.BUST_SIZE.validator();
				Object offY = Breasts.BREASTS_OFFSET_Y.validator();
				Object offZ = Breasts.BREASTS_OFFSET_Z.validator();
				Object bounce = Breasts.Physics.BOUNCE_MULTIPLIER.validator();
				Object floppy = Breasts.Physics.FLOPPINESS.validator();
				if(bust == null || offY == null || offZ == null || bounce == null || floppy == null) {
					return null;
				}
				map = new IdentityHashMap<>();
				map.put(bust, "bust_size");
				map.put(offY, "breasts_yOffset");
				map.put(offZ, "breasts_zOffset");
				map.put(bounce, "bounce_multiplier");
				map.put(floppy, "floppy_multiplier");
				fgmplus$targets = map;
			} catch(Throwable t) {
				//reentrant call from a class we resolve against (its static field not yet
				//assigned) or an upstream layout change: stay stock this time, retry later
				return null;
			}
		}
		return map.get(self);
	}

	/** 本实例若是要拓宽的键则返回其放宽下界，否则 NaN。 */
	@Unique private static float fgmplus$widenedMin(ConfigRange<?> self) {
		String key = fgmplus$keyName(self);
		return key == null ? Float.NaN : FgmPlusConfig.widenedMin(key);
	}

	/** 本实例若是要拓宽的键则返回其放宽上界，否则 NaN。 */
	@Unique private static float fgmplus$widenedMax(ConfigRange<?> self) {
		String key = fgmplus$keyName(self);
		return key == null ? Float.NaN : FgmPlusConfig.widenedMax(key);
	}

	//滑条范围 / 命令参数 / 旧数据 floatRange 都经这对 record 访问器解构取值
	@Inject(method = "minInclusive", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fgmplus$widenMinInclusive(CallbackInfoReturnable<Comparable<?>> cir) {
		float min = fgmplus$widenedMin((ConfigRange<?>)(Object)this);
		if(!Float.isNaN(min)) {
			cir.setReturnValue(min);
		}
	}

	@Inject(method = "maxInclusive", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fgmplus$widenMaxInclusive(CallbackInfoReturnable<Comparable<?>> cir) {
		float max = fgmplus$widenedMax((ConfigRange<?>)(Object)this);
		if(!Float.isNaN(max)) {
			cir.setReturnValue(max);
		}
	}

	//KeyBackedConfigValue#update 与 WildfireHelper#validatedVector 的活值写入校验。
	//擦除后的正主方法描述符是 (Ljava/lang/Comparable;)Z，接口桥 validate(Object) 转发到它，
	//用全名限定避免连桥方法一起注入
	@Inject(method = "validate(Ljava/lang/Comparable;)Z", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fgmplus$widenValidate(Comparable<?> value, CallbackInfoReturnable<Boolean> cir) {
		String key = fgmplus$keyName((ConfigRange<?>)(Object)this);
		if(key == null || !(value instanceof Float f)) {
			return; //非目标键，或类型不是 Float：交给原版逻辑
		}
		float min = FgmPlusConfig.widenedMin(key);
		float max = FgmPlusConfig.widenedMax(key);
		//放宽表两条都必须齐，缺一条就退回原版校验，绝不当成无界放行
		if(Float.isNaN(min) || Float.isNaN(max)) {
			return;
		}
		cir.setReturnValue(f >= min && f <= max);
	}

	//ConfigKey 构造时包进 codec/streamCodec 的编解码钳位（存档读回、网络解码都走这里）。
	//落在放宽窗内直接放行；窗外按放宽窗钳位（原版是按裸字段钳位），与 validate 保持同一套边界
	@Inject(method = "codecValidation(Ljava/lang/Comparable;)Lcom/mojang/serialization/DataResult;",
			at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fgmplus$widenCodecValidation(Comparable<?> value, CallbackInfoReturnable<DataResult<?>> cir) {
		String key = fgmplus$keyName((ConfigRange<?>)(Object)this);
		if(key == null || !(value instanceof Float f)) {
			return;
		}
		final float min = FgmPlusConfig.widenedMin(key);
		final float max = FgmPlusConfig.widenedMax(key);
		if(Float.isNaN(min) || Float.isNaN(max)) {
			return;
		}
		if(f >= min && f <= max) {
			cir.setReturnValue(DataResult.success(value));
		} else {
			float clamped = Math.max(min, Math.min(max, f));
			cir.setReturnValue(DataResult.error(
					() -> "Value must be within range [" + min + ";" + max + "]: " + f, (Object) clamped));
		}
	}
}
