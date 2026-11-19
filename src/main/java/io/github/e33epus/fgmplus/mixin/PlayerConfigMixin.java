package io.github.e33epus.fgmplus.mixin;

import com.wildfire.common.entities.players.PlayerConfigHolder;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeHolder;
import io.github.e33epus.fgmplus.shape.ShapeStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 给主模组的逐玩家配置挂接 FGM Plus 造型数据。 */
@Mixin(value = PlayerConfigHolder.class, remap = false)
public abstract class PlayerConfigMixin implements ShapeHolder {
    @Unique private volatile ShapeData fgmplus$shape;

    @Override
    public ShapeData fgmplus$getShape() {
        if (fgmplus$shape == null) fgmplus$shape = new ShapeData();
        return fgmplus$shape;
    }

    @Override
    public void fgmplus$setShape(ShapeData shape) {
        fgmplus$shape = shape == null ? new ShapeData() : shape;
    }

    @Inject(method = "loadFromDisk", at = @At("RETURN"), require = 1, remap = false)
    private void fgmplus$loadShape(boolean markForSync, CallbackInfo ci) {
        var uuid = ((PlayerConfigHolder) (Object) this).uuid;
        ShapeData loaded = ShapeStore.load(uuid);
        if (markForSync || (fgmplus$getShape().isDefault() && !loaded.isDefault())) {
            fgmplus$setShape(loaded);
        }
    }
}
