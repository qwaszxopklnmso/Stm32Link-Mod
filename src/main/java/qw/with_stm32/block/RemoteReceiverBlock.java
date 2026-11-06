package qw.with_stm32.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import qw.with_stm32.LinkSavedData;

/**
 * STM32 -> MC. Outputs redstone power (like a redstone block) while the
 * board's B4 button state is on. Never modified directly in-game.
 */
public class RemoteReceiverBlock extends Block {
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");

	public RemoteReceiverBlock(Properties settings) {
		super(settings);
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
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

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
			boolean movedByPiston) {
		// same-block state change: state was chosen deliberately, skip adoption
		if (level.isClientSide || oldState.is(this)) return;
		try {
			LinkSavedData.get((net.minecraft.server.level.ServerLevel) level).add("receiver", pos);
		} catch (Throwable e) {
			qw.with_stm32.With_Stm32.LOGGER.error("WithSTM32: failed to register receiver at {}", pos, e);
		}
		// a receiver placed while the board's B4 is already on must power up at once
		if (qw.with_stm32.LinkState.receiverState() && !state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, true), Block.UPDATE_ALL);
		}
	}
}
