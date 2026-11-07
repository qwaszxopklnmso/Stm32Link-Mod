package qw.with_stm32.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import qw.with_stm32.LinkSavedData;
import qw.with_stm32.LinkState;

/**
 * STM32 -> MC. LEVEL reads B4, PULSE reads B2, TOGGLE reads B3, and each
 * processes the transition differently:
 *
 * <ul>
 *   <li>{@link Mode#LEVEL} - output follows B4 (the original receiver).</li>
 *   <li>{@link Mode#PULSE} - every B4 change emits a short redstone pulse.</li>
 *   <li>{@link Mode#TOGGLE} - every B4 change flips the output latch.</li>
 * </ul>
 *
 * This turns one physical button into three useful redstone components without
 * changing the wire protocol: the firmware already reports B4 as a level, and
 * the edge behaviour is derived on the Minecraft side.
 */
public class RemoteReceiverBlock extends Block {
	public enum Mode { LEVEL, PULSE, TOGGLE }

	private static final int PULSE_TICKS = 2;

	public static final BooleanProperty POWERED = BooleanProperty.create("powered");

	private final Mode mode;

	public RemoteReceiverBlock(Mode mode, Properties settings) {
		super(settings);
		this.mode = mode;
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
	}

	public Mode mode() {
		return mode;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(POWERED) ? 15 : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(POWERED) ? 15 : 0;
	}

	/** Called by LinkRegistry for every B4 transition (after the initial sync). */
	public void onSignal(ServerLevel level, BlockPos pos, BlockState state, boolean value) {
		switch (mode) {
			case LEVEL -> setPowered(level, pos, state, value);
			case PULSE -> pulse(level, pos, state);
			case TOGGLE -> setPowered(level, pos, state, !state.getValue(POWERED));
		}
	}

	private void setPowered(ServerLevel level, BlockPos pos, BlockState state, boolean powered) {
		if (state.getValue(POWERED) != powered) {
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_ALL);
		}
	}

	private void pulse(ServerLevel level, BlockPos pos, BlockState state) {
		if (!state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, true), Block.UPDATE_ALL);
		}
		level.scheduleTick(pos, this, PULSE_TICKS);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, false), Block.UPDATE_ALL);
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
			boolean movedByPiston) {
		// same-block state change: state was chosen deliberately, skip adoption
		if (level.isClientSide || oldState.is(this)) return;
		try {
			LinkSavedData.get((ServerLevel) level).add("receiver", pos);
		} catch (Throwable e) {
			qw.with_stm32.With_Stm32.LOGGER.error("WithSTM32: failed to register receiver at {}", pos, e);
		}
		// A level receiver placed while B4 is already on must power up at once.
		// Pulse/toggle variants start idle: their semantics are edge-based.
		if (mode == Mode.LEVEL && LinkState.receiverState() && !state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, true), Block.UPDATE_ALL);
		}
	}
}
