package qw.with_stm32.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import qw.with_stm32.LinkRegistry;
import qw.with_stm32.LinkSavedData;
import qw.with_stm32.LinkState;
import qw.with_stm32.With_Stm32;

/**
 * A vanilla redstone lamp (behaviour copied from RedstoneLampBlock: instant on,
 * 4-tick delayed off, signal check at placement time) that is mirrored with an
 * STM32 LED channel.
 * Output-only link: redstone (or another MC lamp on the same channel) turns it
 * on; the board LED follows. The board never drives this lamp back.
 * Right-click has no effect.
 */
public class LinkLampBlock extends Block {
	public static final BooleanProperty LIT = BooleanProperty.create("lit");

	private final int channel;

	public LinkLampBlock(int channel, Properties settings) {
		super(settings);
		this.channel = channel;
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// placement itself fires no neighbor update towards the new block, so the
		// redstone input must be evaluated here (same as the vanilla redstone lamp)
		return defaultBlockState().setValue(LIT,
			context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos,
			Block neighborBlock, net.minecraft.world.level.redstone.Orientation orientation,
			boolean movedByPiston) {
		if (!level.isClientSide) {
			boolean lit = state.getValue(LIT);
			if (lit != level.hasNeighborSignal(pos)) {
				if (lit) {
					level.scheduleTick(pos, this, 4);   // vanilla 4-tick turn-off delay
				} else {
					turnOn(level, pos, state);
				}
			}
		}
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (state.getValue(LIT) && !level.hasNeighborSignal(pos)) {
			turnOff(level, pos, state);
		}
	}

	private void turnOn(Level level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state.setValue(LIT, true), Block.UPDATE_CLIENTS);
		LinkState.setLed(channel, true);
	}

	private void turnOff(Level level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_CLIENTS);
		LinkState.setLed(channel, false);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
			boolean movedByPiston) {
		// same-block state change (turnOn/turnOff/sync): the new state was chosen
		// deliberately, and ledState() may still be lagging this very setBlock -
		// adopting it here would re-light a lamp that is being switched off
		if (level.isClientSide || oldState.is(this)) return;
		try {
			LinkSavedData.get((net.minecraft.server.level.ServerLevel) level).add("lamp" + channel, pos);
		} catch (Throwable e) {
			With_Stm32.LOGGER.error("WithSTM32: failed to register lamp at {}", pos, e);
		}
		BlockState now = level.getBlockState(pos);
		// Placement next to a live signal produces a lit state directly from
		// getStateForPlacement(), so there is no lit -> dark transition here. It
		// must still be pushed to the board, otherwise the block only *looks* lit
		// and the STM32 LED stays dark. Dark placement still adopts a channel
		// that is already lit in MC, but never switches one off.
		if (state.getValue(LIT)) {
			LinkState.setLed(channel, true);
		} else if (level.hasNeighborSignal(pos) || LinkState.ledState(channel)) {
			turnOn(level, pos, now);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level,
			BlockPos pos, boolean movedByPiston) {
		// a lit lamp disappeared (broken / destroyed): the board LED must not stay
		// on with nothing to show for it. Pistons carry the state to the destination
		// block, which re-syncs in onPlace.
		if (!movedByPiston && state.getValue(LIT) && !level.getBlockState(pos).is(this)) {
			LinkRegistry.lampRemoved(channel, pos);
		}
	}

	/** Current channel of this lamp (1..3). */
	public int channel() {
		return channel;
	}
}
