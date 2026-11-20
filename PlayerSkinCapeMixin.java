package com.ghosttune.capedisplay.mixin;

import com.ghosttune.capedisplay.texture.CapeTextureManager;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 披风注入点。
 *
 * <p>1.21.11 里 {@code PlayerEntityRenderer.updateRenderState} 会调用
 * {@code ClientPlayerLikeEntity.getSkin()} 并把结果塞进 {@code PlayerEntityRenderState.skinTextures}，
 * 随后 {@code CapeFeatureRenderer} 直接读 {@code state.skinTextures.cape().texturePath()} 去取渲染层。</p>
 *
 * <p>所以这里只改 {@code getSkin()} 的返回值：把 cape 字段换成本 mod 注册的纹理 Identifier，
 * 原版的披风模型、飘动动画、鞘翅逻辑全部原样复用，不需要自己写渲染器。</p>
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class PlayerSkinCapeMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void capedisplay$applyCape(CallbackInfoReturnable<SkinTextures> cir) {
        SkinTextures base = cir.getReturnValue();
        if (base == null) {
            return;
        }
        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
        CapeTextureManager.CapeAsset asset = CapeTextureManager.INSTANCE.capeFor(self.getUuid());
        if (asset == null) {
            return;
        }
        cir.setReturnValue(new SkinTextures(
                base.body(),
                new AssetInfo.TextureAssetInfo(asset.id(), asset.texture()),
                base.elytra(),
                base.model(),
                base.secure()
        ));
    }
}