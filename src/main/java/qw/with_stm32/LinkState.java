package qw.with_stm32;

import net.minecraft.core.BlockPos;

/**
 * Mirrored STM32 state (always updated on the server thread):
 * LED1-3  -> link lamps channels 1-3 (bidirectional)
 * B4      -> remote receiver redstone output (STM32 -> MC)
 * T       -> remote transmitter (MC -> STM32), fire-and-forget
 */
public final class LinkState {
	private static final boolean[] led = new boolean[3];
	private static boolean button4 = false;

	private LinkState() {}

	/** Called on the server thread for every protocol line from the board. */
	public static void handleIncoming(String line) {
		if (line.length() >= 4 && line.charAt(2) == ':') {
			char kind = line.charAt(0);
			char num = line.charAt(1);
			boolean v = line.charAt(3) == '1';
			if (kind == 'L' && num >= '1' && num <= '3') {
				int ch = num - '1';
				led[ch] = v;
				LinkRegistry.syncLamp(ch + 1, v);
			} else if (kind == 'B' && num == '4') {
				button4 = v;
				LinkRegistry.syncReceiver(v);
			}
		}
	}

	public static boolean ledState(int channel) {   // 1..3
		return led[channel - 1];
	}

	public static boolean receiverState() {
		return button4;
	}

	/** Push an LED change to the board (called from lamp interactions). */
	public static void setLed(int channel, boolean v) {
		led[channel - 1] = v;
		SerialLink.send("L" + channel + ":" + (v ? 1 : 0));
	}

	/**
	 * A transmitter edge happened (or one was removed): recompute the shared T
	 * channel from all registered transmitters and tell the board. The channel
	 * is the OR of every transmitter block, so one block losing power must not
	 * silence another block that is still fed.
	 */
	public static void recomputeTransmit(BlockPos exclude) {
		LinkRegistry.pushTransmitterState(exclude);
	}
}
