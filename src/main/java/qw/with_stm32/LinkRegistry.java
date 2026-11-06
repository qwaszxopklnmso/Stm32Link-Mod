package qw.with_stm32;

import java.util.function.BiFunction;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import qw.with_stm32.block.LinkLampBlock;
import qw.with_stm32.block.RemoteReceiverBlock;
import qw.with_stm32.block.RemoteTransmitterBlock;
import qw.with_stm32.item.TuneWhistleItem;

/** All blocks / items / creative tab + syncing board state into placed blocks. */
public final class LinkRegistry {
	public static Block LINK_LAMP_RED;
	public static Block LINK_LAMP_GREEN;
	public static Block LINK_LAMP_BLUE;
	public static Block REMOTE_RECEIVER;
	public static Block REMOTE_TRANSMITTER;
	public static Item TUNE_WHISTLE;

	private LinkRegistry() {}

	public static void init() {
		LINK_LAMP_RED = registerLamp("link_lamp_red", 1, MapColor.COLOR_RED);
		LINK_LAMP_GREEN = registerLamp("link_lamp_green", 2, MapColor.COLOR_GREEN);
		LINK_LAMP_BLUE = registerLamp("link_lamp_blue", 3, MapColor.COLOR_BLUE);

		REMOTE_RECEIVER = registerBlock("remote_receiver",
			(key, props) -> new RemoteReceiverBlock(props.setId(key)));
		REMOTE_TRANSMITTER = registerBlock("remote_transmitter",
			(key, props) -> new RemoteTransmitterBlock(props.setId(key)));

		TUNE_WHISTLE = Registry.register(BuiltInRegistries.ITEM, With_Stm32.id("tune_whistle"),
			new TuneWhistleItem(new Item.Properties()
				.stacksTo(1)
				.setId(ResourceKey.create(BuiltInRegistries.ITEM.key(), With_Stm32.id("tune_whistle")))));

		CreativeModeTab tab = FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.with_stm32"))
			.icon(() -> new ItemStack(LINK_LAMP_RED))
			.displayItems((params, out) -> {
				out.accept(LINK_LAMP_RED);
				out.accept(LINK_LAMP_GREEN);
				out.accept(LINK_LAMP_BLUE);
				out.accept(REMOTE_RECEIVER);
				out.accept(REMOTE_TRANSMITTER);
				out.accept(TUNE_WHISTLE);
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, With_Stm32.id("main"), tab);
	}

	private static BlockBehaviour.Properties baseSettings() {
		return BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(2.0f)
			.sound(SoundType.METAL);
	}

	private static Block registerLamp(String name, int channel, MapColor color) {
		return registerBlock(name, (key, props) -> new LinkLampBlock(channel,
			props.mapColor(color)
				.strength(0.3f)
				.sound(SoundType.GLASS)
				.lightLevel(state -> state.getValue(LinkLampBlock.LIT) ? 15 : 0)
				.setId(key)));
	}

	private static Block registerBlock(String name,
			BiFunction<ResourceKey<Block>, BlockBehaviour.Properties, ? extends Block> factory) {
		ResourceLocation id = With_Stm32.id(name);
		ResourceKey<Block> key = ResourceKey.create(BuiltInRegistries.BLOCK.key(), id);
		Block block = Registry.register(BuiltInRegistries.BLOCK, id, factory.apply(key, baseSettings()));
		Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block,
			new Item.Properties().setId(ResourceKey.create(BuiltInRegistries.ITEM.key(), id))
				.useBlockDescriptionPrefix()));
		return block;
	}

	// ---- state syncing (always on the server thread, from LinkState) ----

	private static java.util.stream.Stream<ServerLevel> levels() {
		var server = With_Stm32.server();
		if (server == null) return java.util.stream.Stream.empty();
		return java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false);
	}

	/** Set every placed link lamp of this channel to the given state. */
	public static void syncLamp(int channel, boolean lit) {
		boolean[] redstoneKept = {false};
		levels().forEach(level -> {
			LinkSavedData data = LinkSavedData.get(level);
			var it = data.get("lamp" + channel).iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.of(it.next());
				if (!level.isLoaded(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof LinkLampBlock lamp) || lamp.channel() != channel) {
					it.remove();   // block was broken while we were offline
					data.setDirty();
					continue;
				}
				if (state.getValue(LinkLampBlock.LIT) != lit) {
					// a redstone-fed lamp is locally powered: the board must not turn it off
					if (!lit && level.hasNeighborSignal(pos)) {
						redstoneKept[0] = true;
						continue;
					}
					// UPDATE_CLIENTS, not UPDATE_ALL: a link lamp is not a redstone
					// source, and notifying its neighbours here would feed the lamp's
					// own vanilla-style neighbourChanged back into itself (or into an
					// adjacent lamp), scheduling the 4-tick off and making a manual /
					// board-driven light blink and die.
					level.setBlock(pos, state.setValue(LinkLampBlock.LIT, lit), Block.UPDATE_CLIENTS);
				}
			}
		});
		// redstone is holding the channel on: tell the board (firmware echoes, no loop)
		if (redstoneKept[0]) LinkState.setLed(channel, true);
	}

	/** A lit lamp was removed - if no lamp of the channel remains lit, tell the board. */
	public static void lampRemoved(int channel, BlockPos removed) {
		if (!anyLampLit(channel, removed)) LinkState.setLed(channel, false);
	}

	/** True if any registered lamp of this channel is currently lit (one pos may be excluded). */
	private static boolean anyLampLit(int channel, BlockPos exclude) {
		for (ServerLevel level : levels().toList()) {
			LinkSavedData data = LinkSavedData.get(level);
			var it = data.get("lamp" + channel).iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.of(it.next());
				if (pos.equals(exclude)) continue;
				if (!level.isLoaded(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof LinkLampBlock lamp) || lamp.channel() != channel) {
					it.remove();   // stale entry
					data.setDirty();
					continue;
				}
				if (state.getValue(LinkLampBlock.LIT)) return true;
			}
		}
		return false;
	}

	/**
	 * Handshake: the world is the master for the lamp channels - push each
	 * channel's actual state to the board. Without this, the firmware's "S"
	 * reply (all-off right after boot) would dark every lamp on reconnect.
	 * While connected, board button events still win as usual.
	 */
	public static void pushLampStates() {
		for (int ch = 1; ch <= 3; ch++) {
			LinkState.setLed(ch, anyLampLit(ch, null));
		}
	}

	/**
	 * Recompute the transmitter OR and tell the board. One T channel is shared by
	 * all transmitter blocks: it is on while ANY of them is powered (a pos may be
	 * excluded - used when removing a block whose state may still be visible).
	 */
	public static void pushTransmitterState(BlockPos exclude) {
		boolean any = false;
		for (ServerLevel level : levels().toList()) {
			LinkSavedData data = LinkSavedData.get(level);
			var it = data.get("transmitter").iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.of(it.next());
				if (pos.equals(exclude)) continue;
				if (!level.isLoaded(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof RemoteTransmitterBlock)) {
					it.remove();   // stale entry
					data.setDirty();
					continue;
				}
				if (state.getValue(RemoteTransmitterBlock.POWERED)) {
					any = true;
					break;
				}
			}
			if (any) break;
		}
		SerialLink.send("T:" + (any ? 1 : 0));
	}

	/** Set every placed remote receiver to the given powered state. */
	public static void syncReceiver(boolean powered) {
		levels().forEach(level -> {
			LinkSavedData data = LinkSavedData.get(level);
			var it = data.get("receiver").iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.of(it.next());
				if (!level.isLoaded(pos)) continue;
				BlockState state = level.getBlockState(pos);
				if (!(state.getBlock() instanceof RemoteReceiverBlock)) {
					it.remove();
					data.setDirty();
					continue;
				}
				if (state.getValue(RemoteReceiverBlock.POWERED) != powered) {
					level.setBlock(pos, state.setValue(RemoteReceiverBlock.POWERED, powered), Block.UPDATE_ALL);
				}
			}
		});
	}
}
