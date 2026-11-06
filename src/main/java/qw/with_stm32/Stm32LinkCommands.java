package qw.with_stm32;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.literal;

public final class Stm32LinkCommands {
	private Stm32LinkCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("stm32link")
				.then(literal("status").executes(ctx -> {
					var source = ctx.getSource();
					boolean on = SerialLink.isConnected();
					boolean open = SerialLink.isPortOpen();
					String state = on ? "§a已连接" : (open ? "§e端口已打开(未握手)" : "§c未连接");
					source.sendSuccess(() -> Component.literal(String.format(
						"§6[WithSTM32]§r 连接: %s  端口: %s  波特率: %d  心跳: %s  LED1-3: %d%d%d  B4: %d",
						state,
						open ? SerialLink.connectedPortName() : (LinkConfig.portName.isEmpty() ? "自动" : LinkConfig.portName),
						LinkConfig.baud,
						on ? (System.currentTimeMillis() - SerialLink.lastHeartbeat() < 3000 ? "§a正常" : "§c超时") : "-",
						LinkState.ledState(1) ? 1 : 0, LinkState.ledState(2) ? 1 : 0,
						LinkState.ledState(3) ? 1 : 0, LinkState.receiverState() ? 1 : 0)), false);
					return 1;
				}))
				.then(literal("beep").executes(ctx -> {
					boolean ok = SerialLink.send("N:1319,120");
					ctx.getSource().sendSuccess(() -> Component.literal(ok
						? "§6[WithSTM32]§r 已发送测试音"
						: "§c[WithSTM32] STM32 未连接"), false);
					return 1;
				}))
				.then(literal("tune").executes(ctx -> {
					var source = ctx.getSource();
					ServerPlayer p = source.getPlayer();
					var level = source.getLevel();
					var pos = p != null ? p.blockPosition() : level.getSharedSpawnPos();
					MelodyPlayer.play(level, pos);
					return 1;
				}))
				.then(literal("tune2").executes(ctx -> {
					var source = ctx.getSource();
					ServerPlayer p = source.getPlayer();
					var level = source.getLevel();
					var pos = p != null ? p.blockPosition() : level.getSharedSpawnPos();
					MelodyPlayer.play(level, pos);
					return 1;
				}))
				.then(literal("audio").executes(ctx -> {
					boolean ok = SerialLink.send("W");
					ctx.getSource().sendSuccess(() -> Component.literal(ok
						? "§6[WithSTM32]§r 已发送 PCM 音频指令（蜂鸣器播放原声片段）"
						: "§c[WithSTM32] STM32 未连接"), false);
					return 1;
				}))
				.then(literal("reconnect")
					.requires(src -> src.hasPermission(2))
					.executes(ctx -> {
						SerialLink.reconnect();
						ctx.getSource().sendSuccess(() -> Component.literal("§6[WithSTM32]§r 正在重连..."), false);
						return 1;
					}))
				.then(literal("port")
					.requires(src -> src.hasPermission(2))
					.then(net.minecraft.commands.Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
						LinkConfig.portName = StringArgumentType.getString(ctx, "name").trim();
						LinkConfig.save();
						SerialLink.reconnect();
						ctx.getSource().sendSuccess(() -> Component.literal(
							"§6[WithSTM32]§r 端口已设为 " + (LinkConfig.portName.isEmpty() ? "自动" : LinkConfig.portName)), false);
						return 1;
					})))
		));
	}
}
