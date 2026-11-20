package com.ghosttune.capedisplay.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘配置：{@code config/capedisplay.json}
 *
 * <p>改完文件后在游戏里执行 {@code /capedisplay reload} 即可生效，不需要重启游戏。</p>
 */
public final class CapeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("capedisplay/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** LittleSkin 的 CSL 兼容接口根地址（Blessing Skin 系列站点通用）。 */
    public static final String DEFAULT_LITTLESKIN_ROOT = "https://littleskin.cn/csl/";

    private static volatile CapeConfig instance;

    // ------------------------------------------------------------------ 配置项

    /** 总开关。 */
    public boolean enabled = true;

    /** 解析顺序，可选值：mojang / littleskin / custom。靠前的优先。 */
    public List<String> sources = new ArrayList<>(List.of("mojang", "littleskin"));

    /** LittleSkin（或任意 Blessing Skin 站点）的 CSL 根地址，必须以 / 结尾或不结尾都行，会自动补。 */
    public String littleskinRoot = DEFAULT_LITTLESKIN_ROOT;

    /** 自定义皮肤站 CSL 根地址，例如 https://skin.example.com/csl/ ；留空表示不启用。 */
    public String customRoot = "";

    /** 查到披风后的缓存秒数。 */
    public int positiveTtlSeconds = 1800;

    /** 查不到披风时的负缓存秒数（避免反复打接口）。 */
    public int negativeTtlSeconds = 300;

    /** 单次 HTTP 超时秒数。 */
    public int requestTimeoutSeconds = 8;

    /** 详细日志。 */
    public boolean debug = false;

    /**
     * 调试开关：进入世界后自动把视角切到第三人称背面。
     *
     * <p>第一人称根本看不见自己背后的披风，自动化验收（截图取证）时打开它即可，
     * 正常游玩保持 false。</p>
     */
    public boolean debugThirdPerson = false;

    /** 手动覆盖：玩家名 -> 披风 PNG 直链（调试或长期自定义用）。 */
    public Map<String, String> overrides = new LinkedHashMap<>();

    // ------------------------------------------------------------------ 读写

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("capedisplay.json");
    }

    /** 披风 PNG 的磁盘缓存目录。 */
    public static Path cacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("capedisplay").resolve("cache");
    }

    public static CapeConfig get() {
        CapeConfig cfg = instance;
        if (cfg == null) {
            synchronized (CapeConfig.class) {
                cfg = instance;
                if (cfg == null) {
                    cfg = load();
                    instance = cfg;
                }
            }
        }
        return cfg;
    }

    public static CapeConfig load() {
        Path file = configFile();
        CapeConfig cfg = null;
        if (Files.isRegularFile(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                cfg = GSON.fromJson(json, CapeConfig.class);
            } catch (Exception e) {
                LOGGER.warn("配置文件解析失败，改用默认值：{}", e.toString());
            }
        }
        boolean created = false;
        if (cfg == null) {
            cfg = new CapeConfig();
            created = true;
        }
        // 补齐可能缺失的字段
        if (cfg.sources == null || cfg.sources.isEmpty()) {
            cfg.sources = new ArrayList<>(List.of("mojang", "littleskin"));
        }
        if (cfg.littleskinRoot == null || cfg.littleskinRoot.isBlank()) {
            cfg.littleskinRoot = DEFAULT_LITTLESKIN_ROOT;
        }
        if (cfg.customRoot == null) {
            cfg.customRoot = "";
        }
        if (cfg.overrides == null) {
            cfg.overrides = new LinkedHashMap<>();
        }
        cfg.requestTimeoutSeconds = Math.max(3, cfg.requestTimeoutSeconds);
        cfg.positiveTtlSeconds = Math.max(30, cfg.positiveTtlSeconds);
        cfg.negativeTtlSeconds = Math.max(30, cfg.negativeTtlSeconds);
        if (created) {
            cfg.save();
            LOGGER.info("已生成默认配置：{}", file);
        }
        return cfg;
    }

    /** 重新从磁盘读取（/capedisplay reload 用）。 */
    public static CapeConfig reload() {
        synchronized (CapeConfig.class) {
            instance = load();
            return instance;
        }
    }

    public void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("配置写入失败：{}", e.toString());
        }
    }
}