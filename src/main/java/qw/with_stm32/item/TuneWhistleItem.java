package qw.with_stm32.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import qw.with_stm32.MelodyPlayer;
import qw.with_stm32.Stm32Melody2;

/**
 * Right-click (air OR a block): plays the extracted pipa tune on the STM32
 * buzzer AND as in-game note block sounds, synchronized (server-tick scheduled).
 * useOn is needed because Item.use only fires when no block is targeted.
 */
public class TuneWhistleItem extends Item {
	public TuneWhistleItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return play(level, player, hand);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		return play(context.getLevel(), context.getPlayer(), context.getHand());
	}

	private static InteractionResult play(Level level, Player player, InteractionHand hand) {
		if (player == null) return InteractionResult.PASS;
		if (level instanceof ServerLevel serverLevel) {
			MelodyPlayer.play(serverLevel, player.blockPosition(),
				Stm32Melody2.VOICES, "8bit 琵琶曲（四声部）");
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 100);
		}
		return InteractionResult.SUCCESS;
	}
}
