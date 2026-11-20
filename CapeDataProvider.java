package com.ghosttune.capedisplay;

import com.ghosttune.capedisplay.config.CapeConfig;
import com.ghosttune.capedisplay.net.CapeHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * 披风地址解析：玩家名 -> 披风 PNG 直链。
 *
 * <p>解析顺序由 {@code config.sources} 决定，默认 {@code mojang} 优先、{@code littleskin} 兜底。</p>
 *
 * <p>三个来源的实际接口：</p>
 * <ul>
 *   <li><b>mojang</b>：{@code api.mojang.com/users/profiles/minecraft/<name>} 拿 UUID，
 *       再请求 {@code sessionserver.mojang.com/session/minecraft/profile/<uuid>}，
 *       base64 解出 {@code textures.CAPE.url}（形如 http://textures.minecraft.net/texture/&lt;hash&gt;，会自动升到 https）。</li>
 *   <li><b>littleskin / custom</b>：Blessing Skin 的 CSL 兼容接口
 *       {@code <root><name>.json} → {@code {"cape":"<文件名>"}}，直链为 {@code <root>textures/<文件名>}。
 *       实测 littleskin.cn 返回标准 64×32 PNG。</li>
 * </ul>
 *
 * <p>全部方法都是阻塞 IO，必须在后台线程调用。</p>
 */
public final class CapeDataProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("capedisplay/provider");

    private static final String MOJANG_UUID_URL = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final String MOJANG_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s";

    private CapeDataProvider() {
    }

    /**
     * 玩家名编码：离线服务器的玩家名可能是中文、空格或其它非 ASCII 字符，
     * 直接拼进 URL 会抛异常或请求失败，所以统一按 UTF-8 百分号编码。
     */
    private static String encodeName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 解析结果：披风直链 + 来源名。 */
    public record Result(String url, String source) {
    }

    /**
     * 按配置顺序解析某个玩家的披风地址。
     *
     * @return 找到的披风直链；没找到返回 {@code null}
     */
    public static Result resolve(String playerName) {
        CapeConfig cfg = CapeConfig.get();
        if (!cfg.enabled) {
            return null;
        }
        String name = playerName == null ? "" : playerName.trim();
        if (name.isEmpty()) {
            return null;
        }

        String override = lookupOverride(cfg, name);
        if (override != null) {
            CapeDisplayMod.dbg("override {} -> {}", name, override);
            return new Result(override, "override");
        }

        for (String raw : cfg.sources) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            try {
                String url = switch (key) {
                    case "mojang" -> fromMojang(name, cfg);
                    case "littleskin" -> fromCsl(name, cfg.littleskinRoot, cfg, "littleskin");
                    case "custom" -> fromCsl(name, cfg.customRoot, cfg, "custom");
                    default -> {
                        LOGGER.warn("未知来源 {}，跳过", key);
                        yield null;
                    }
                };
                if (url != null) {
                    return new Result(url, key);
                }
            } catch (Exception e) {
                LOGGER.warn("来源 {} 解析 {} 失败：{}", key, name, e.toString());
            }
        }
        return null;
    }

    private static String lookupOverride(CapeConfig cfg, String name) {
        if (cfg.overrides == null || cfg.overrides.isEmpty()) {
            return null;
        }
        String url = cfg.overrides.get(name);
        if (url == null) {
            url = cfg.overrides.get(name.toLowerCase(Locale.ROOT));
        }
        if (url == null) {
            return null;
        }
        url = url.trim();
        return url.isEmpty() ? null : url;
    }

    // ---------------------------------------------------------------- Mojang

    private static String fromMojang(String name, CapeConfig cfg) throws Exception {
        int timeout = cfg.requestTimeoutSeconds;
        CapeHttp.HttpResult profileRes = CapeHttp.get(String.format(MOJANG_UUID_URL, encodeName(name)), timeout);
        if (!profileRes.ok()) {
            CapeDisplayMod.dbg("mojang 查名 {} 返回 HTTP {}", name, profileRes.status());
            return null;
        }
        JsonObject profile = JsonParser.parseString(profileRes.text()).getAsJsonObject();
        if (!profile.has("id")) {
            return null;
        }
        String uuid = profile.get("id").getAsString();

        CapeHttp.HttpResult sessionRes = CapeHttp.get(String.format(MOJANG_SESSION_URL, uuid), timeout);
        if (!sessionRes.ok()) {
            CapeDisplayMod.dbg("mojang 查皮肤 {} 返回 HTTP {}", uuid, sessionRes.status());
            return null;
        }
        JsonObject session = JsonParser.parseString(sessionRes.text()).getAsJsonObject();
        JsonObject textures = decodeTextures(session);
        if (textures == null || !textures.has("CAPE")) {
            return null;
        }
        JsonElement cape = textures.get("CAPE");
        if (!cape.isJsonObject()) {
            return null;
        }
        JsonElement urlElement = cape.getAsJsonObject().get("url");
        if (urlElement == null || urlElement.isJsonNull()) {
            return null;
        }
        String url = urlElement.getAsString();
        if (url.startsWith("http://")) {
            url = "https://" + url.substring("http://".length());
        }
        return url;
    }

    private static JsonObject decodeTextures(JsonObject session) {
        JsonArray properties = session.getAsJsonArray("properties");
        if (properties == null) {
            return null;
        }
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            if (!property.has("name") || !property.has("value")) {
                continue;
            }
            if (!"textures".equals(property.get("name").getAsString())) {
                continue;
            }
            try {
                String raw = new String(Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject decoded = JsonParser.parseString(raw).getAsJsonObject();
                JsonElement textures = decoded.get("textures");
                if (textures != null && textures.isJsonObject()) {
                    return textures.getAsJsonObject();
                }
            } catch (Exception e) {
                LOGGER.warn("textures 属性解码失败：{}", e.toString());
            }
        }
        return null;
    }

    // -------------------------------------------------------- Blessing Skin / CSL

    /**
     * 读取 Blessing Skin 的 CSL 兼容接口（LittleSkin 同样适用）。
     *
     * <p>请求 {@code <root><name>.json}，返回形如
     * {@code {"username":"ghosttune","skins":{"slim":"<hash>"},"cape":"<hash>"}}，
     * 披风直链是 {@code <root>textures/<hash>}。</p>
     */
    private static String fromCsl(String name, String root, CapeConfig cfg, String label) throws Exception {
        if (root == null || root.isBlank()) {
            return null;
        }
        String base = root.endsWith("/") ? root : root + "/";
        // Blessing Skin 的 /csl/<用户名>.json 对大小写敏感：大小写不对会返回空对象 {}，
        // 所以原名没命中的时候再用全小写试一次（LittleSkin 上的用户名都是小写）。
        String url = fetchCslCape(base, name, cfg, label);
        if (url == null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals(name)) {
                url = fetchCslCape(base, lower, cfg, label);
            }
        }
        return url;
    }

    private static String fetchCslCape(String base, String name, CapeConfig cfg, String label) throws Exception {
        String jsonUrl = base + encodeName(name) + ".json";
        CapeHttp.HttpResult res = CapeHttp.get(jsonUrl, cfg.requestTimeoutSeconds);
        if (!res.ok()) {
            CapeDisplayMod.dbg("{} 查 {} 返回 HTTP {}", label, name, res.status());
            return null;
        }
        JsonObject json = JsonParser.parseString(res.text()).getAsJsonObject();
        JsonElement cape = json.get("cape");
        if (cape == null || cape.isJsonNull()) {
            CapeDisplayMod.dbg("{} 上 {} 没有披风", label, name);
            return null;
        }
        String file = cape.getAsString();
        if (file == null || file.isBlank()) {
            return null;
        }
        return base + "textures/" + file;
    }
}