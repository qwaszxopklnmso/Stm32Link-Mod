package qw.with_stm32.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import qw.with_stm32.LinkSavedData;
import qw.with_stm32.LinkState;

/**
 * MC -> STM32. While fed with redstone power it sends T:1 to the board
 * (LED on PA4 mirrors it, plus a short beep on the rising edge).
 */
public class RemoteTransmitterBlock extends Block {
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");

	public RemoteTransmitterBlock(Properties settings) {
		super(settings);
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos,
			Block neighborBlock, net.minecraft.world.level.redstone.Orientation orientation,
			boolean movedByPiston) {
		if (!level.isClientSide) {
			boolean powered = level.hasNeighborSignal(pos);
			if (powered != state.getValue(POWERED)) {
				// not a redstone source: UPDATE_CLIENTS is enough and avoids feeding
				// a neighbour update back into adjacent link lamps.
				level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
				LinkState.recomputeTransmit(null);   // shared T channel = OR of all transmitters
			}
		}
	}

	@Override
	public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
		// placement itself fires no neighbor update towards the new block
		return defaultBlockState().setValue(POWERED,
			context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
			boolean movedByPiston) {
		// same-block state change: state was chosen deliberately, skip adoption
		if (level.isClientSide || oldState.is(this)) return;
		try {
			LinkSavedData.get((net.minecraft.server.level.ServerLevel) level).add("transmitter", pos);
		} catch (Throwable e) {
			qw.with_stm32.With_Stm32.LOGGER.error("WithSTM32: failed to register transmitter at {}", pos, e);
		}
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
		}
		// adopt/push the current channel state (covers placement next to a live
		// signal and also repairs a stuck T channel before this block existed).
		// Also retry one tick later: on some placement paths the new block state
		// is not visible to LinkRegistry yet when onPlace runs, which used to make
		// a pre-powered transmitter look unpowered and send T:0.
		LinkState.recomputeTransmit(null);
		level.scheduleTick(pos, this, 1);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		LinkState.recomputeTransmit(null);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level,
			BlockPos pos, boolean movedByPiston) {
		// a powered transmitter disappeared: recompute so the board T-LED does not
		// stay on with nothing driving it. Exclude pos: it may still read the old
		// state; it is no longer a transmitter either way.
		if (!movedByPiston && state.getValue(POWERED) && !level.getBlockState(pos).is(this)) {
			LinkState.recomputeTransmit(pos);
		}
	}
}
