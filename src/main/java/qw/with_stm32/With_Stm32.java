package qw.with_stm32;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class With_Stm32 implements ModInitializer {
	public static final String MOD_ID = "with_stm32";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile net.minecraft.server.MinecraftServer server;

	/** Current MinecraftServer (null when no server is running). */
	public static net.minecraft.server.MinecraftServer server() {
		return server;
	}

	@Override
	public void onInitialize() {
		LinkConfig.load();
		LinkRegistry.init();
		SerialLink.init();
		MelodyPlayer.init();
		Stm32LinkCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			LinkState.beginWorld();
			SerialLink.start();
			// A fresh world (or a re-entered single-player world) must win over
			// whatever lamp/transmitter/receiver state the still-running board
			// had from the previous world. The link is intentionally kept open
			// across SERVER_STOPPING, so no serial reopen / 12s handshake is
			// needed here; push the new world's state immediately.
			LinkRegistry.pushTransmitterState(null);
			LinkRegistry.pushLampStates();
			LinkRegistry.syncReceiverLevel(LinkState.receiverState());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			// Keep the serial port open while leaving a world. Closing it here
			// made the next join race the OS/jSerialComm close and wait for the
			// 5s retry + 12s handshake. The board is external; only the server
			// reference is world-scoped.
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(MelodyPlayer::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) MelodyPlayer.onPlayerDeath(player);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			String msg;
			if (SerialLink.isConnected()) {
				msg = "§a[WithSTM32] STM32 已连接 (" + SerialLink.connectedPortName() + ")";
			} else if (SerialLink.isPortOpen()) {
				msg = "§e[WithSTM32] 串口 " + SerialLink.connectedPortName()
					+ " 已打开但 STM32 没应答（检查固件是否烧录 / 板子是否上电）";
			} else {
				msg = "§e[WithSTM32] 未检测到 STM32（检查 USB 线 / 接线，见 STM32_Set.md）";
			}
			handler.getPlayer().displayClientMessage(Component.literal(msg), false);
		});

		LOGGER.info("WithSTM32 initialized - MC <-> STM32 serial link");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
