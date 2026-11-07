package qw.with_stm32;

/**
 * Wire format of lines coming from the STM32.
 *
 * <p>Firmware lines are intentionally tiny and fixed-shape:
 * {@code L<1-3>:<0|1>}, {@code B<1-4>:<0|1>}, {@code H}, {@code R}, {@code OK}.
 * Keeping the parser in one place means the handshake check and the state
 * update cannot disagree about what a valid line is (the old code used a
 * regex for the handshake and a separate, looser char check for the update).
 */
public final class ProtocolCodec {
	public record Incoming(char kind, int channel, boolean value) {}

	private ProtocolCodec() {}

	/** A real firmware line - not terminal echo or line noise. */
	public static boolean isHandshake(String line) {
		if (line == null) return false;
		if (line.equals("H") || line.equals("R") || line.equals("OK")) return true;
		return parse(line) != null;
	}

	/** Parse a state line; returns null for anything malformed. */
	public static Incoming parse(String line) {
		if (line == null || line.length() != 4 || line.charAt(2) != ':') return null;
		char kind = line.charAt(0);
		if (kind != 'L' && kind != 'B') return null;
		char n = line.charAt(1);
		if (n < '1' || n > '4') return null;
		char v = line.charAt(3);
		if (v != '0' && v != '1') return null;
		return new Incoming(kind, n - '0', v == '1');
	}
}
