package com.ghosttune.capedisplay;

import com.ghosttune.capedisplay.command.CapeDisplayCommand;
import com.ghosttune.capedisplay.config.CapeConfig;
import com.ghosttune.capedisplay.texture.CapeTextureManager;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口。
 *
 * <p>本 mod 纯客户端：不改服务端、不注册任何网络包，靠 {@link CapeDataProvider} 直接查公开接口拿披风，
 * 再用 mixin 把纹理塞进原版 {@code SkinTextures}。因此服务器上只需有一个能返回披风的皮肤站账号即可。</p>
 */
public class CapeDisplayMod implements ClientModInitializer {
    public static final String MOD_ID = "capedisplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        CapeConfig cfg = CapeConfig.get();
        CapeTextureManager.INSTANCE.init();
        CapeDisplayClient.register();
        CapeDisplayCommand.register();
        LOGGER.info("[capedisplay] 初始化完成：开关={} 来源={} littleskinRoot={}",
                cfg.enabled, cfg.sources, cfg.littleskinRoot);
    }

    /** debug 开关打开时才输出的日志。 */
    public static void dbg(String format, Object... args) {
        if (CapeConfig.get().debug) {
            LOGGER.info(format, args);
        }
    }
}