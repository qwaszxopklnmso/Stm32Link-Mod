package qw.with_stm32;

import net.minecraft.core.BlockPos;

/**
 * Mirrored STM32 state (always updated on the server thread):
 * LED1-3  -> link lamps channels 1-3 (MC -> STM only; incoming L is ignored)
 * B4      -> remote receiver redstone output (STM32 -> MC)
 * T       -> remote transmitter (MC -> STM32), fire-and-forget
 */
public final class LinkState {
	private static final boolean[] led = new boolean[3];
	private static boolean button4 = false;
	// The first B4 report after entering a world is a state sync, not a button
	// press. Without this flag, a board that was already on would fire a pulse
	// or flip a toggle just because the player changed dimension.
	private static boolean receiverSeen = false;

	private LinkState() {}

	/** Called when a world starts; the next B4 line only syncs the level. */
	public static void beginWorld() {
		receiverSeen = false;
	}

	/** Called on the server thread for every protocol line from the board. */
	public static void handleIncoming(String line) {
		ProtocolCodec.Incoming msg = ProtocolCodec.parse(line);
		if (msg == null) return;
		if (msg.kind() == 'L') {
			// Output-only link: MC is authoritative for the lamps, so a board LED
			// report must never drive one back. Keep accepting L from old firmware.
			return;
		} else if (msg.kind() == 'B') {
			switch (msg.channel()) {
				case 1 -> {
					// B1 is a panic reset for the telemetry outputs, not a lamp control.
					if (msg.value()) LinkRegistry.resetReceivers();
				}
				case 2 -> {
					// B2 is a press event: the pulse receiver gets one short pulse.
					if (msg.value()) LinkRegistry.onReceiverPulse();
				}
				case 3 -> {
					// B3 is a press event: the toggle receiver flips its latch.
					if (msg.value()) LinkRegistry.onReceiverToggle();
				}
				case 4 -> {
					// B4 is still the level source for the original receiver.
					boolean old = button4;
					button4 = msg.value();
					if (!receiverSeen) {
						receiverSeen = true;
						LinkRegistry.syncReceiverLevel(button4);   // initial state, no edge
					} else if (old != button4) {
						LinkRegistry.syncReceiverLevel(button4);
					}
				}
				default -> { }
			}
		}
	}

	/** Last state MC pushed for this channel (not a board report). */
	public static boolean ledState(int channel) {   // 1..3
		if (channel < 1 || channel > 3) return false;
		return led[channel - 1];
	}

	public static boolean receiverState() {
		return button4;
	}

	/** Push an LED change to the board (called from lamp interactions). */
	public static void setLed(int channel, boolean v) {
		if (channel < 1 || channel > 3) return;
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
