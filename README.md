# 披风显示 Mod（capedisplay）

Minecraft **1.21.11** · **Fabric** · **仅客户端** · 作者 **ghosttune**

不装 CSL、不改服务端、不动皮肤系统，纯客户端把玩家的披风渲染出来。

---

## 它做什么

按优先级依次尝试，命中就显示：

1. **Mojang 官方披风** —— `api.mojang.com/users/profiles/minecraft/<name>` 拿 UUID，再 `sessionserver.mojang.com/session/minecraft/profile/<uuid>` 解 Base64 纹理属性里的 `textures.CAPE.url`
2. **LittleSkin / 任意 Blessing Skin 的 CSL 接口** —— `<root><用户名>.json` 取 `cape` 字段，纹理在 `<root>textures/<cape>`
3. **手动 override** —— 配置里的 `overrides` 表，玩家名 → PNG 直链
4. 都没有 → 不干预，保留原版行为（默认皮肤/官方披风）

命中后走**原版披风渲染管线**：模型、摆动动画、鞘翅滑翔姿态、披风可见性设置全部复用，本 mod 不自己画一个披风。

---

## 工作原理

| 层 | 类 | 职责 |
|---|---|---|
| 数据层 | `CapeDataProvider` | 按 `sources` 顺序查询，返回 PNG 直链 |
| 网络层 | `CapeHttp` | `java.net.http.HttpClient`，8 秒超时，跟随重定向 |
| 纹理层 | `CapeTextureManager` | 后台下载 → 统一成 64×32（大图最近邻缩放）→ 主线程 `NativeImageBackedTexture` + `TextureManager.registerTexture`；磁盘缓存 + 正/负 TTL |
| 注入层 | `PlayerSkinCapeMixin` | `@Mixin(AbstractClientPlayerEntity)` `@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)`，把 `SkinTextures.cape` 换成我们的 `AssetInfo.TextureAssetInfo(id, texturePath)` |

关键细节（踩过的坑，别改回去）：

- `AssetInfo.TextureAssetInfo` **必须用双参构造**。单参构造会自动推出 `textures/<path>.png`，而 `TextureManager.registerTexture` 用的 key 本身就带 `textures/…png`，两者叠加会报
  `Missing resource capedisplay:textures/capes/xxx.png referenced from capedisplay:textures/capes/xxx.png`，披风静默不显示。
  正确写法：id = `capedisplay:capes/<sha1前16>`，texture = `capedisplay:textures/capes/<sha1前16>.png`。
- Blessing Skin 的 `/csl/<用户名>.json` **对大小写敏感**：`Ghosttune.json` 返回 `{}`，`ghosttune.json` 才带 `cape`。所以原名没命中的时候会自动再用全小写试一次。
- 不用原版 `PlayerSkinTextureDownloader`：它的 `remapTexture` 会按皮肤（64×64 / 旧 64×32）改写像素，把标准 64×32 披风误当旧皮肤处理。

---

## 离线服务器（online-mode=false）

**能用，而且这是本 mod 的主要使用场景。** 原因很简单：整个 mod **不使用玩家 UUID，只按玩家名查**。

| 关心的问题 | 结论 |
|---|---|
| 离线服玩家 UUID 是 `OfflinePlayer:<名字>` 派生的，不是正版 UUID | 无影响，mod 不用 UUID 查任何接口 |
| 离线服客户端拿不到服务端签名的皮肤属性 | 无影响，mod 自己走 HTTP 取披风纹理，不依赖 `PlayerListEntry` 的纹理数据 |
| 服务端需要装 mod 吗 | 不需要，纯客户端 |
| 玩家名是中文/带空格 | 已做 UTF-8 URL 编码，不会拼坏请求 |
| 玩家名大小写（`Ghosttune` vs `ghosttune`） | LittleSkin 的 CSL 接口大小写敏感，已自动回退到全小写再查一次 |

实测：单人存档（integrated server，玩家 UUID 同样是离线派生、没有正版签名）里披风正常显示，说明离线 UUID 这条链路本来就是通的。

**两个必须知道的取舍：**

1. **正版同名会「借」到别人的披风。** 离线服里玩家叫 `Notch` 或 `Ghosttune`，但没有正版账号时，Mojang 查询会按名字命中真正的正版玩家，于是显示**那个正版玩家**的披风。这是「优先 Mojang」的必然结果。如果你希望离线服只认 LittleSkin（服主自己维护的皮肤站），把配置改成：
   ```json
   "sources": ["littleskin"]
   ```
   或者反过来说：正因为按名字查，离线服玩家只要在 LittleSkin 上用自己的名字传了披风，就一定能显示。

2. **只换披风不换皮肤。** 离线服里原版皮肤是默认 Steve/Alex，所以视觉上会是「默认皮肤 + 自定义披风」。本 mod 按需求只做披风，不做皮肤替换。

**给小服主的建议**：让玩家用 LittleSkin 同名的 ID 进服，配置 `"sources": ["mojang", "littleskin"]`（默认值）即可；如果服务器是纯离线、玩家都是自己人，直接把 `sources` 设成 `["littleskin"]` 更可控。

---

## 安装

1. Minecraft 1.21.11 + Fabric Loader ≥ 0.15
2. 把 `capedisplay-0.1.1.jar` 丢进 `.minecraft/mods/`
3. 装 Fabric API（`net.fabricmc:fabric-api`，1.21.11 对应 0.141.6+1.21.11）
4. 客户端 only，**不需要**装到服务端；多人服务器上装在自己客户端就能看到别人的披风（只影响本地画面）

---

## 配置 `config/capedisplay.json`

首次启动自动生成。

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `sources` | `["mojang","littleskin"]` | 查询顺序，可只留一个，可加 `"custom"` |
| `littleskinRoot` | `https://littleskin.cn/csl/` | LittleSkin 的 CSL 根地址 |
| `customRoot` | `""` | 自建 Blessing Skin 的 CSL 根地址，例如 `https://skin.example.com/csl/` |
| `positiveTtlSeconds` | `1800` | 查到披风后的缓存时间（秒） |
| `negativeTtlSeconds` | `300` | 查不到时的负缓存时间（秒） |
| `requestTimeoutSeconds` | `8` | 单次 HTTP 超时 |
| `debug` | `false` | 打印每个玩家的查询过程 |
| `overrides` | `{}` | 玩家名 → PNG 直链，优先级最高 |
| `debugThirdPerson` | `false` | 仅自动化验收用：自动切第三人称（正常玩不需要） |

改完用 `/capedisplay reload` 生效，或直接重启游戏。

---

## 命令（客户端）

```
/capedisplay help                         # 命令一览（输入 /capedisplay 也一样）
/capedisplay status                       # 开关、来源顺序、每个玩家的状态与纹理
/capedisplay reload                       # 重载配置并清缓存（改完配置不用重启游戏）
/capedisplay clear                        # 清空运行时缓存（20 tick 内自动重查）
/capedisplay debug                        # 切换 debug 日志
/capedisplay test <玩家> [PNG直链]         # 给指定玩家强制指定披风；玩家不在线且给了直链就挂到自己身上
```

状态含义：`READY` 披风已就绪 / `PENDING` 查询中 / `MISSING` 没有披风 / `ERROR` 下载或格式失败 / `UNKNOWN` 还没查。

---

## 已验证（真实客户端画面）

| 场景 | 结果 |
|---|---|
| Mojang 来源（用户名 `Ghosttune`，官方披风） | ✅ 第三人称背面可见彩虹披风，日志 `披风就绪 ← mojang` |
| LittleSkin 来源（`sources=["littleskin"]`） | ✅ 显示 LittleSkin 那张蓝灰披风，日志 `披风就绪 ← littleskin` |
| **离线服务器**（`online-mode=false`，客户端连 `127.0.0.1:25566`） | ✅ 真实远程连接登录（服务端日志 `Ghosttune[/127.0.0.1:11903] logged in`），披风正常显示 |
| 离线服上的来源兜底 | ✅ 日志实录：`mojang 解析失败：EOF reached while reading` → `littleskin 大写没命中` → `披风就绪 ← littleskin` |
| 无披风玩家（`notch` / `Steve`） | ✅ 记 `MISSING`，不注入，原版行为 |
| `/capedisplay status` / `test` / `reload` / `clear` | ✅ 均有正确输出 |
| `/capedisplay help`（含无参数 `/capedisplay`） | ✅ 游戏内实测：金标题 + 绿命令 + 灰说明，6 条命令 + 状态码 + 用法提示 |
| 尺寸处理 | 标准 64×32 直接用；1280×640 这类 2:1 等比大图自动缩放到 64×32（实测 1280×640 → 正常显示）；比例不是 2:1 才拒收并记 `ERROR` |

日志样例：

```
[capedisplay] 初始化完成：开关=true 来源=[mojang, littleskin] littleskinRoot=https://littleskin.cn/csl/
披风就绪：Ghosttune <- https://textures.minecraft.net/texture/7c418dfb…（来源 mojang，纹理 capedisplay:textures/capes/c1ca16b8f223c990.png）
```

---

## 已知限制

- 真机验收做过两种环境：单人世界、以及一台真实的离线服（`online-mode=false`，客户端走网络协议登录）。但两种环境里都**只有我自己一个玩家**，多玩家同屏（看到别人身上的披风）走的是同一段代码，未实机多开验证。
- 披风图长宽比必须是 **2:1**（标准 64×32，或它的整数倍放大，如 1280×640）；皮肤站存的是玩家上传的原图，常见这种放大版，程序会自动最近邻缩放到 64×32。比例不是 2:1 的图会被拒收（避免原版模型贴错 UV）。
- 披风的显示受原版「披风」皮肤部件设置影响：如果玩家自己在皮肤设置里关掉披风，本 mod 也不会强显。
- 进世界到披风出现有 1–3 秒延迟（异步查询 + 下载）；之后从磁盘缓存读。
- 离线服务器的玩家名如果和正版/皮肤站的用户名对不上，Mojang 查不到，只能靠 `overrides` 手动指定。
- 不做皮肤（SKIN）替换，只做披风。

---

## 开发

```bash
./gradlew build        # 产物 build/libs/capedisplay-0.1.1.jar
./gradlew runClient    # 开发客户端
```

`build.gradle` 末尾的 `loom.runs.client.programArgs`（`--username Ghosttune`、`--quickPlaySingleplayer capedev`）是验收自动化用的，只影响 dev 运行配置，awa，头次发布可能不太熟练awa
