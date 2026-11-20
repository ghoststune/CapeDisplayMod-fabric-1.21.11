package com.ghosttune.capedisplay.texture;

import com.ghosttune.capedisplay.CapeDataProvider;
import com.ghosttune.capedisplay.CapeDisplayMod;
import com.ghosttune.capedisplay.config.CapeConfig;
import com.ghosttune.capedisplay.net.CapeHttp;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 披风纹理注册表：把「玩家 UUID」映射到「已注册到 TextureManager 的披风纹理」。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>{@link #tick(MinecraftClient)} 每 20 tick 扫一遍在线玩家，给还没查过的玩家排队；</li>
 *   <li>后台线程 {@link CapeDataProvider#resolve(String)} 查披风直链；</li>
 *   <li>下载（或命中磁盘缓存）PNG，非 64×32 的等比大图（如 1280×640）自动缩放到 64×32；</li>
 *   <li>回到主线程用 {@link NativeImageBackedTexture} 注册到 {@code capedisplay:textures/capes/<hash>.png}；</li>
 *   <li>mixin 在 {@code AbstractClientPlayerEntity#getSkin()} 里把 cape 换成这个纹理，
 *       原版 {@code CapeFeatureRenderer} 就会用标准模型/动画把披风画出来。</li>
 * </ol>
 *
 * <p>注意：{@code AssetInfo.TextureAssetInfo} 有两个 Identifier —— 逻辑 id 和"真正的纹理路径"。
 * 单参构造会自己推导 {@code textures/<path>.png}，这里显式写双参，避免推错。</p>
 */
public final class CapeTextureManager {
    public static final CapeTextureManager INSTANCE = new CapeTextureManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("capedisplay/texture");
    private static final String NAMESPACE = "capedisplay";

    public enum State {
        /** 还没查过。 */
        UNKNOWN,
        /** 查询中。 */
        PENDING,
        /** 已拿到纹理，可以显示。 */
        READY,
        /** 这个玩家（在配置的来源上）没有披风。 */
        MISSING,
        /** 查询出错（网络/格式）。 */
        ERROR
    }

    /**
     * 一张已注册的披风图。
     *
     * @param id      逻辑资源 id，例如 {@code capedisplay:capes/c1ca16b8f223c990}
     * @param texture TextureManager 里真正的注册键，例如 {@code capedisplay:textures/capes/c1ca16b8f223c990.png}
     */
    public record CapeAsset(Identifier id, Identifier texture) {
    }

    public record PlayerEntry(State state, CapeAsset asset, String source, String url, long expiresAt, String note) {
    }

    private final ConcurrentMap<UUID, PlayerEntry> players = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CapeAsset> textureByUrl = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> urlLocks = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "capedisplay-worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private CapeTextureManager() {
    }

    public void init() {
        try {
            Files.createDirectories(CapeConfig.cacheDir());
        } catch (IOException e) {
            LOGGER.warn("创建缓存目录失败：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 调度

    /** 每个 tick 周期调用（本 mod 每 20 tick 一次）。 */
    public void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return;
        }
        if (!CapeConfig.get().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            UUID uuid = player.getUuid();
            PlayerEntry entry = players.get(uuid);
            if (entry != null) {
                if (entry.state() == State.PENDING) {
                    continue;
                }
                if (entry.expiresAt() > now) {
                    continue;
                }
            }
            request(uuid, player.getName().getString(), null);
        }
    }

    /** 排队解析某个玩家；{@code forcedUrl} 非空时跳过接口查询，直接用这个直链。 */
    public void request(UUID uuid, String playerName, String forcedUrl) {
        players.put(uuid, new PlayerEntry(State.PENDING, null, null, forcedUrl, Long.MAX_VALUE, "queued"));
        pool.submit(() -> work(uuid, playerName, forcedUrl));
    }

    private void work(UUID uuid, String playerName, String forcedUrl) {
        try {
            String url = forcedUrl;
            String source = forcedUrl == null ? null : "manual";
            if (url == null) {
                CapeDataProvider.Result result = CapeDataProvider.resolve(playerName);
                if (result == null) {
                    players.put(uuid, new PlayerEntry(State.MISSING, null, null, null,
                            expire(CapeConfig.get().negativeTtlSeconds), "no cape"));
                    CapeDisplayMod.dbg("{} 没有可用的披风", playerName);
                    return;
                }
                url = result.url();
                source = result.source();
            }
            CapeAsset asset = ensureTexture(url);
            players.put(uuid, new PlayerEntry(State.READY, asset, source, url,
                    expire(CapeConfig.get().positiveTtlSeconds), "ok"));
            LOGGER.info("披风就绪：{} <- {}（来源 {}，纹理 {}）", playerName, url, source, asset.texture());
        } catch (Exception e) {
            LOGGER.warn("解析 {} 的披风失败：{}", playerName, e.toString());
            players.put(uuid, new PlayerEntry(State.ERROR, null, null, forcedUrl,
                    expire(CapeConfig.get().negativeTtlSeconds), String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 给 mixin 用：这个玩家当前该用的披风，没有则 {@code null}。 */
    public CapeAsset capeFor(UUID uuid) {
        PlayerEntry entry = players.get(uuid);
        if (entry == null || entry.state() != State.READY) {
            return null;
        }
        return entry.asset();
    }

    public PlayerEntry entryFor(UUID uuid) {
        return players.get(uuid);
    }

    public String describe(UUID uuid) {
        PlayerEntry entry = players.get(uuid);
        if (entry == null) {
            return "UNKNOWN（未查询）";
        }
        return switch (entry.state()) {
            case READY -> "READY ← " + entry.source() + " " + shorten(entry.url())
                    + "（" + (entry.asset() == null ? "?" : entry.asset().texture()) + "）";
            case MISSING -> "MISSING（该来源没有披风）";
            case ERROR -> "ERROR " + entry.note();
            case PENDING -> "PENDING（查询中）";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    public String summary() {
        int ready = 0;
        int pending = 0;
        int missing = 0;
        int error = 0;
        for (PlayerEntry entry : players.values()) {
            switch (entry.state()) {
                case READY -> ready++;
                case PENDING -> pending++;
                case MISSING -> missing++;
                case ERROR -> error++;
                default -> {
                }
            }
        }
        return String.format(Locale.ROOT, "纹理 %d 张｜玩家 ready=%d pending=%d missing=%d error=%d",
                textureByUrl.size(), ready, pending, missing, error);
    }

    // ------------------------------------------------------------------ 纹理

    private CapeAsset ensureTexture(String url) throws Exception {
        CapeAsset cached = textureByUrl.get(url);
        if (cached != null) {
            return cached;
        }
        Object lock = urlLocks.computeIfAbsent(url, key -> new Object());
        synchronized (lock) {
            cached = textureByUrl.get(url);
            if (cached != null) {
                return cached;
            }
            String hash = sha1(url);
            Path file = CapeConfig.cacheDir().resolve(hash + ".png");
            byte[] bytes = null;
            if (Files.isRegularFile(file)) {
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException e) {
                    LOGGER.warn("读取缓存 {} 失败：{}", file, e.toString());
                    bytes = null;
                }
            }
            boolean fromCache = bytes != null;
            if (bytes == null) {
                CapeHttp.HttpResult response = CapeHttp.get(url, CapeConfig.get().requestTimeoutSeconds);
                if (!response.ok()) {
                    throw new IOException("下载披风失败 HTTP " + response.status() + "：" + url);
                }
                bytes = response.body();
            }
            validateCape(bytes, url);
            if (!fromCache) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.write(file, bytes);
                } catch (IOException e) {
                    LOGGER.warn("写入缓存 {} 失败：{}", file, e.toString());
                }
            }
            String name = "capes/" + hash.substring(0, 16);
            Identifier id = Identifier.of(NAMESPACE, name);
            Identifier texture = Identifier.of(NAMESPACE, "textures/" + name + ".png");
            registerOnMainThread(texture, bytes);
            CapeAsset asset = new CapeAsset(id, texture);
            textureByUrl.put(url, asset);
            return asset;
        }
    }

    private void registerOnMainThread(Identifier texture, byte[] bytes) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            NativeImage image = null;
            NativeImage normalized = null;
            try {
                image = NativeImage.read(new ByteArrayInputStream(bytes));
                normalized = normalizeCape(image);
                NativeImageBackedTexture backed = new NativeImageBackedTexture(
                        () -> "capedisplay cape " + texture.getPath(), normalized);
                MinecraftClient.getInstance().getTextureManager().registerTexture(texture, backed);
                done.complete(null);
            } catch (Throwable t) {
                if (normalized != null) {
                    normalized.close();
                } else if (image != null) {
                    image.close();
                }
                done.completeExceptionally(t);
            }
        });
        done.get(15, TimeUnit.SECONDS);
    }

    /**
     * 把贴图规整成原版披风需要的 64×32。
     *
     * <p>皮肤站（Bearer Skin 系）保存的是玩家上传的原图，常见 1280×640 这种 20 倍放大版。
     * 原版模型用 UV 0..1 采样，等比放大图其实能正确显示，但直接注册会让显存按原图大小分配，
     * 所以这里用最近邻缩放到 64×32（保持像素风，不做插值模糊）。</p>
     *
     * <p>返回的可能是入参本身（标准尺寸），也可能是新图；入参在缩放时会被关闭。</p>
     */
    private static NativeImage normalizeCape(NativeImage source) throws IOException {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w == 64 && h == 32) {
            return source;
        }
        if (h <= 0 || w != h * 2) {
            source.close();
            throw new IOException("披风尺寸 " + w + "x" + h + " 不是 64x32 的等比放大（宽应为高的 2 倍）");
        }
        NativeImage target = new NativeImage(64, 32, false);
        for (int y = 0; y < 32; y++) {
            int sy = y * h / 32;
            for (int x = 0; x < 64; x++) {
                target.setColorArgb(x, y, source.getColorArgb(x * w / 64, sy));
            }
        }
        source.close();
        return target;
    }

    private static void validateCape(byte[] bytes, String url) throws IOException {
        if (bytes == null || bytes.length < 24) {
            throw new IOException("披风数据为空：" + url);
        }
        boolean png = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        boolean jpg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        if (!png && !jpg) {
            throw new IOException("不是 PNG/JPEG 图片：" + url);
        }
        if (png) {
            int width = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
            int height = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
            if (height <= 0 || width != height * 2) {
                throw new IOException("披风尺寸 " + width + "x" + height + " 不是 64x32 的等比放大（宽应为高的 2 倍）：" + url);
            }
        }
    }

    // ------------------------------------------------------------------ 维护

    /** 清空解析结果与纹理映射（纹理本体留在 TextureManager 里，下次会复用 Identifier）。 */
    public void clear() {
        players.clear();
        textureByUrl.clear();
        LOGGER.info("披风缓存已清空");
    }

    public void onDisconnect() {
        players.clear();
    }

    private static long expire(int seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private static String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String shorten(String url) {
        if (url == null) {
            return "(null)";
        }
        return url.length() <= 72 ? url : url.substring(0, 69) + "...";
    }
}