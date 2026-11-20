package com.ghosttune.capedisplay.command;

import com.ghosttune.capedisplay.CapeDataProvider;
import com.ghosttune.capedisplay.config.CapeConfig;
import com.ghosttune.capedisplay.texture.CapeTextureManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * 客户端命令：{@code /capedisplay help|status|reload|clear|debug|test}
 */
public final class CapeDisplayCommand {

    private CapeDisplayCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("capedisplay")
                .executes(CapeDisplayCommand::help)
                .then(ClientCommandManager.literal("help").executes(CapeDisplayCommand::help))
                .then(ClientCommandManager.literal("status").executes(CapeDisplayCommand::status))
                .then(ClientCommandManager.literal("reload").executes(CapeDisplayCommand::reload))
                .then(ClientCommandManager.literal("clear").executes(CapeDisplayCommand::clear))
                .then(ClientCommandManager.literal("debug").executes(CapeDisplayCommand::debug))
                .then(ClientCommandManager.literal("test")
                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                .executes(context -> test(context, null))
                                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> test(context, StringArgumentType.getString(context, "url")))))));
    }

    private static int help(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Text.literal("[披风显示] 命令一览（输入 /capedisplay 也会显示）").formatted(Formatting.GOLD));
        line(source, "help", "显示这份帮助");
        line(source, "status", "看开关、来源、每个玩家的状态和 URL");
        line(source, "reload", "重新读配置并清空缓存，不用重启游戏");
        line(source, "clear", "只清缓存后立刻重查，换了披风用这个");
        line(source, "debug", "切换调试日志，排查没有显示的原因");
        source.sendFeedback(Text.literal("  /capedisplay test <玩家> [直链]").formatted(Formatting.GREEN));
        cont(source, "强制查询该玩家；给了直链就直接用它");
        source.sendFeedback(Text.literal("  状态：READY 已显示 / PENDING 查询中 / MISSING 没有披风")
                .formatted(Formatting.GRAY));
        cont(source, "ERROR 下载或格式失败 / UNKNOWN 还没查");
        source.sendFeedback(Text.literal("  用法：改配置 → reload；换披风 → clear；没显示 → status")
                .formatted(Formatting.GRAY));
        return 1;
    }

    /** 一行帮助：绿色命令 + 灰色说明（说明列左对齐到第 24 列） */
    private static void line(FabricClientCommandSource source, String usage, String desc) {
        String head = "  /capedisplay " + usage;
        source.sendFeedback(Text.literal(head).formatted(Formatting.GREEN)
                .append(Text.literal(" ".repeat(Math.max(2, 24 - head.length()))).formatted(Formatting.GRAY))
                .append(Text.literal(desc).formatted(Formatting.GRAY)));
    }

    /** 续行：缩进到说明列 */
    private static void cont(FabricClientCommandSource source, String desc) {
        source.sendFeedback(Text.literal(" ".repeat(24) + desc).formatted(Formatting.GRAY));
    }

    private static int status(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        CapeConfig cfg = CapeConfig.get();
        source.sendFeedback(Text.literal("[披风显示] 开关=" + cfg.enabled + "，来源顺序=" + cfg.sources));
        source.sendFeedback(Text.literal("[披风显示] littleskinRoot=" + cfg.littleskinRoot));
        source.sendFeedback(Text.literal("[披风显示] customRoot=" + (cfg.customRoot == null || cfg.customRoot.isBlank() ? "（未设置）" : cfg.customRoot)));
        source.sendFeedback(Text.literal("[披风显示] " + CapeTextureManager.INSTANCE.summary()));

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                source.sendFeedback(Text.literal("  · " + player.getName().getString() + " → "
                        + CapeTextureManager.INSTANCE.describe(player.getUuid())));
            }
        }
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> context) {
        CapeConfig cfg = CapeConfig.reload();
        CapeTextureManager.INSTANCE.clear();
        context.getSource().sendFeedback(Text.literal("[披风显示] 已重新读取配置：开关=" + cfg.enabled
                + "，来源=" + cfg.sources + "，littleskinRoot=" + cfg.littleskinRoot));
        return 1;
    }

    private static int clear(CommandContext<FabricClientCommandSource> context) {
        CapeTextureManager.INSTANCE.clear();
        context.getSource().sendFeedback(Text.literal("[披风显示] 已清空披风缓存，20 tick 内会重新查询在线玩家"));
        return 1;
    }

    private static int debug(CommandContext<FabricClientCommandSource> context) {
        CapeConfig cfg = CapeConfig.get();
        cfg.debug = !cfg.debug;
        cfg.save();
        context.getSource().sendFeedback(Text.literal("[披风显示] debug = " + cfg.debug));
        return 1;
    }

    private static int test(CommandContext<FabricClientCommandSource> context, String forcedUrl) {
        FabricClientCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            source.sendFeedback(Text.literal("[披风显示] 不在世界里，无法测试"));
            return 0;
        }
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(name)) {
                CapeTextureManager.INSTANCE.request(player.getUuid(), player.getName().getString(), forcedUrl);
                source.sendFeedback(Text.literal("[披风显示] 已排队查询 " + player.getName().getString()
                        + (forcedUrl == null ? "（按配置来源）" : "（强制直链）")));
                if (forcedUrl != null) {
                    source.sendFeedback(Text.literal("[披风显示] url = " + forcedUrl));
                }
                return 1;
            }
        }
        // 不在在线列表里：允许用配置里的 override 或指定直链给自己挂上
        UUID self = client.player == null ? null : client.player.getUuid();
        if (self != null && forcedUrl != null) {
            CapeTextureManager.INSTANCE.request(self, client.player.getName().getString(), forcedUrl);
            source.sendFeedback(Text.literal("[披风显示] " + name + " 不在线，已把该直链挂到你自己身上做验证"));
            return 1;
        }
        source.sendFeedback(Text.literal("[披风显示] " + name + " 不在线。可以这样测："
                + "/capedisplay test " + name + " <披风PNG直链>，会把该披风挂到你自己身上做画面验证"));
        return 1;
    }
}