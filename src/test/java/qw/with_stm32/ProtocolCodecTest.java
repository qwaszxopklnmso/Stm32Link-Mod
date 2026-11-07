package qw.with_stm32;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {
	@Test
	void acceptsRealHandshakeLines() {
		assertTrue(ProtocolCodec.isHandshake("R"));
		assertTrue(ProtocolCodec.isHandshake("H"));
		assertTrue(ProtocolCodec.isHandshake("OK"));
		assertTrue(ProtocolCodec.isHandshake("L1:1"));
		assertTrue(ProtocolCodec.isHandshake("B4:0"));
	}

	@Test
	void parsesStateLines() {
		ProtocolCodec.Incoming led = ProtocolCodec.parse("L3:1");
		assertEquals('L', led.kind());
		assertEquals(3, led.channel());
		assertTrue(led.value());

		ProtocolCodec.Incoming button = ProtocolCodec.parse("B4:0");
		assertEquals('B', button.kind());
		assertEquals(4, button.channel());
		assertFalse(button.value());
	}

	@Test
	void rejectsNoiseAndMalformedLines() {
		assertNull(ProtocolCodec.parse(null));
		assertNull(ProtocolCodec.parse(""));
		assertNull(ProtocolCodec.parse("L1:1x"));
		assertNull(ProtocolCodec.parse("L5:1"));
		assertNull(ProtocolCodec.parse("L1:2"));
		assertNull(ProtocolCodec.parse("Y1:1"));
		assertFalse(ProtocolCodec.isHandshake("Y1:1"));
		assertFalse(ProtocolCodec.isHandshake("random"));
	}
}
