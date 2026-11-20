package com.ghosttune.capedisplay;

import com.ghosttune.capedisplay.config.CapeConfig;
import com.ghosttune.capedisplay.texture.CapeTextureManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.Perspective;

/**
 * 客户端事件接线：每 20 tick 扫一次在线玩家，断开连接时清掉解析结果。
 */
public final class CapeDisplayClient {
    private static int tickCounter;

    private CapeDisplayClient() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter % 20 != 0) {
                return;
            }
            CapeTextureManager.INSTANCE.tick(client);
            // 调试用：第一人称看不见背后的披风，开了 debugThirdPerson 就自动转第三人称便于截图
            if (CapeConfig.get().debugThirdPerson && client.player != null
                    && client.options.getPerspective() == Perspective.FIRST_PERSON) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CapeTextureManager.INSTANCE.onDisconnect());
    }
}