package qw.with_stm32;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fazecast.jSerialComm.SerialPort;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Serial port connection + reader thread for the STM32 link (ASCII line protocol). */
public final class SerialLink {
	private static final long HANDSHAKE_TIMEOUT_MS = 5000;    // firmware must greet us within this
	private static final long BAD_PORT_MS = 30000;            // skip a port that failed to handshake
	private static final int PORT_TIMEOUT_MS = 1000;          // native read/write cap

	private static volatile boolean running = false;
	private static volatile boolean wanted = false;   // server wants a connection
	private static volatile SerialPort port;
	private static volatile boolean handshaked = false;   // got at least one line from the board
	private static volatile long openedAt = 0;
	private static volatile String connectedName = null;
	private static volatile long lastHeartbeat = 0;
	private static volatile boolean reconnectRequested = false;
	private static final ConcurrentHashMap<String, Long> BAD_PORTS = new ConcurrentHashMap<>();
	private static int portCursor = 0;
	private static Thread thread;

	// Lines waiting to go out. The server thread only ever appends here - the
	// port itself is touched exclusively by the serial thread and never while
	// holding the class monitor, so a wedged USB driver can never stall a
	// server tick or world saving.
	private static final ConcurrentLinkedQueue<byte[]> OUTBOX = new ConcurrentLinkedQueue<>();

	private SerialLink() {}

	public static void init() {}

	/** True only after the board answered (port open + firmware handshake). */
	public static boolean isConnected() {
		SerialPort p = port;
		return p != null && p.isOpen() && handshaked;
	}

	/** Port open but the board may not have answered yet. */
	public static boolean isPortOpen() {
		SerialPort p = port;
		return p != null && p.isOpen();
	}

	public static String connectedPortName() {
		return connectedName;
	}

	public static long lastHeartbeat() {
		return lastHeartbeat;
	}

	/** Called when a server starts. */
	public static synchronized void start() {
		wanted = true;
		if (thread != null && thread.isAlive()) {
			running = true;
			return;
		}
		running = true;
		thread = new Thread(SerialLink::runLoop, "withstm32-serial");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Called when the server stops. Keep the port open: the worker and the
	 * native handle survive a world unload, so a rejoin needs no reopen. The OS
	 * closes it when the game process exits.
	 */
	public static synchronized void stop() {
		wanted = false;
		reconnectRequested = false;
	}

	/**
	 * Drop the current connection; the serial thread closes it and scans again.
	 * Never close from the caller: a rapid triple /reconnect used to spawn three
	 * closer threads that raced the next open and wedged the COM port.
	 */
	public static synchronized void reconnect() {
		reconnectRequested = true;
		OUTBOX.clear();
	}

	private static boolean isOpen() {
		SerialPort p = port;
		return p != null && p.isOpen();
	}

	/** Detach the current port object (field cleared under the monitor). */
	private static SerialPort takePort() {
		synchronized (SerialLink.class) {
			SerialPort p = port;
			port = null;
			handshaked = false;
			connectedName = null;
			return p;
		}
	}

	/** Reader-thread path: close inline (never called while inside a native call). */
	private static void closePort() {
		SerialPort p = takePort();
		if (p != null) try { p.closePort(); } catch (Exception ignored) {}
		OUTBOX.clear();
	}

	private static void runLoop() {
		long lastAttempt = 0;
		while (running) {
			try {
				if (reconnectRequested) {
					reconnectRequested = false;
					closePort();
					lastAttempt = 0;
					continue;
				}
				if (!wanted) { sleep(500); continue; }
				if (!isOpen()) {
					if (System.currentTimeMillis() - lastAttempt >= 5000) {
						lastAttempt = System.currentTimeMillis();
						tryOpen();
					}
					sleep(1000);
					continue;
				}
				drainOutbox();
				readLoop();
				if (running && wanted && isOpen()) {
					With_Stm32.LOGGER.info("STM32 link lost, will retry");
					announce(null, "§e[WithSTM32] 串口连接断开，重试中...");
					closePort();
				}
			} catch (Throwable t) {
				// never let the link thread die (e.g. native lib init failure)
				With_Stm32.LOGGER.debug("STM32 link error", t);
				closePort();
				sleep(2000);
			}
		}
	}

	private static void readLoop() {
		StringBuilder line = new StringBuilder();
		try {
			SerialPort p = port;
			if (p == null) return;
			java.io.InputStream in = p.getInputStream();
			while (running && wanted && !reconnectRequested && isOpen()) {
				while (running && wanted && !reconnectRequested && isOpen() && in.available() == 0) {
					drainOutbox();   // keep sending even while waiting for the first reply
					if (!handshaked && System.currentTimeMillis() - openedAt > HANDSHAKE_TIMEOUT_MS) {
						markBadPort(connectedName);
						announce(null, "§e[WithSTM32] " + connectedName
							+ " 无 STM32 应答（固件没烧录 / 板子没上电？见 STM32_Set.md），继续扫描...");
						closePort();
						return;
					}
					sleep(50);
				}
				if (!running || !wanted || reconnectRequested || !isOpen()) return;
				int c = in.read();   // data is available, will not block
				if (c == -1) return;
				// the timeout check above only runs while idle - a constant garbage
				// stream (floating TX / wrong baud) must not pin the port either
				if (!handshaked && System.currentTimeMillis() - openedAt > HANDSHAKE_TIMEOUT_MS) {
					markBadPort(connectedName);
					announce(null, "§e[WithSTM32] " + connectedName
						+ " 有数据但不是 STM32 协议（波特率不对 / 不是本固件？见 STM32_Set.md），继续扫描...");
					closePort();
					return;
				}
				if (c == '\n' || c == '\r') {
					if (!line.isEmpty()) handleLine(line.toString());
					line.setLength(0);
				} else if (line.length() < 64) {
					line.append((char) c);
				}
			}
		} catch (Exception e) {
			// port vanished (unplugged) - fall through to reconnect
		}
	}

	/** Send everything queued. Runs on the serial thread only. */
	private static void drainOutbox() {
		if (OUTBOX.isEmpty()) return;
		SerialPort p = port;
		if (p == null || !p.isOpen()) { OUTBOX.clear(); return; }
		byte[] chunk;
		while ((chunk = OUTBOX.poll()) != null) {
			int n = p.writeBytes(chunk, chunk.length);
			if (n != chunk.length) {   // wedged driver or port died - drop and reconnect
				With_Stm32.LOGGER.info("STM32 serial write failed, reconnecting");
				OUTBOX.clear();
				closePort();
				return;
			}
		}
	}

	private static void tryOpen() {
		SerialPort target = nextCandidate();
		if (target == null) return;
		target.setBaudRate(LinkConfig.baud);
		target.setNumDataBits(8);
		target.setNumStopBits(SerialPort.ONE_STOP_BIT);
		target.setParity(SerialPort.NO_PARITY);
		// cap native blocking so a silent/dead board can never wedge the driver
		target.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, PORT_TIMEOUT_MS, PORT_TIMEOUT_MS);
		if (!target.openPort(1500)) {
			With_Stm32.LOGGER.debug("STM32 link: failed to open {}", target.getSystemPortName());
			markBadPort(target.getSystemPortName());
			return;
		}
		port = target;
		connectedName = target.getSystemPortName();
		handshaked = false;
		openedAt = System.currentTimeMillis();
		With_Stm32.LOGGER.info("STM32 link port {} opened ({} baud), waiting for handshake", connectedName, LinkConfig.baud);
		announce(null, "§e[WithSTM32] 串口 " + connectedName + " 已打开，等待 STM32 应答...");
		OUTBOX.add("S\n".getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * Round-robin over every serial port. A configured port is tried first, but
	 * it no longer hides the rest: if COM5 is a different CH340, it is marked
	 * bad and the next candidate (the real board) is tried immediately.
	 */
	private static SerialPort nextCandidate() {
		List<SerialPort> candidates = candidates();
		if (candidates.isEmpty()) return null;
		SerialPort p = candidates.get(portCursor % candidates.size());
		portCursor = (portCursor + 1) % candidates.size();
		return p;
	}

	private static List<SerialPort> candidates() {
		SerialPort[] ports = SerialPort.getCommPorts();
		String cfg = LinkConfig.portName.trim();
		List<SerialPort> ordered = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		java.util.function.Consumer<SerialPort> add = p -> {
			if (p != null && seen.add(p.getSystemPortName())) ordered.add(p);
		};
		if (!cfg.isEmpty()) {
			String needle = cfg.toLowerCase();
			for (SerialPort p : ports) {
				String d = p.getDescriptivePortName();
				if (p.getSystemPortName().equalsIgnoreCase(cfg)
						|| (d != null && d.toLowerCase().contains(needle))) add.accept(p);
			}
		}
		for (SerialPort p : ports) {
			String d = p.getDescriptivePortName();
			if (d == null) continue;
			d = d.toLowerCase();
			if (d.contains("ch340") || d.contains("ch341")) add.accept(p);
		}
		for (SerialPort p : ports) {
			String d = p.getDescriptivePortName();
			if (d == null) continue;
			d = d.toLowerCase();
			if (d.contains("usb-serial") || d.contains("usb serial") || d.contains("cdc")) add.accept(p);
		}
		for (SerialPort p : ports) {
			if (isUsbSerial(p)) add.accept(p);
		}
		List<SerialPort> good = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (SerialPort p : ordered) {
			Long until = BAD_PORTS.get(p.getSystemPortName());
			if (until == null || until <= now) good.add(p);
		}
		return good.isEmpty() ? ordered : good;
	}

	private static void markBadPort(String name) {
		if (name != null && !name.isEmpty()) BAD_PORTS.put(name, System.currentTimeMillis() + BAD_PORT_MS);
	}

	/**
	 * Only real USB-serial adapters are auto-opened. Bluetooth/virtual COM
	 * ports (COM5 here) accept openPort but can wedge on the first write, which
	 * was the "three reconnects then freeze" failure. An explicitly configured
	 * port is still tried first even if it is not in this list.
	 */
	private static boolean isUsbSerial(SerialPort p) {
		String d = (p.getDescriptivePortName() + " " + p.getPortDescription()).toLowerCase();
		return d.contains("ch340") || d.contains("ch341") || d.contains("usb")
				|| d.contains("ftdi") || d.contains("cp210") || d.contains("pl2303")
				|| d.contains("cdc") || d.contains("acm");
	}

	/** Lines from STM32 arrive on the reader thread - hop onto the server thread. */
	private static void handleLine(String line) {
		if (!handshaked) {
			// only a genuine protocol line counts as the handshake - random noise
			// (or a terminal echo) must not flip us into "connected"
			if (!ProtocolCodec.isHandshake(line)) return;
			handshaked = true;
			BAD_PORTS.remove(connectedName);
			announce(null, "§a[WithSTM32] STM32 已连接 (" + connectedName + ")");
			// the board reported its state ("S" reply): mirror it into the world.
			// the reverse direction too - lamps and transmitters are MC-driven, so
			// push the world's state to the board (restores the T-LED and keeps the
			// all-off "S" reply from darkening lamps after a game/board restart).
			MinecraftServer server = With_Stm32.server();
			if (server != null) server.execute(() -> {
				LinkRegistry.pushTransmitterState(null);
				LinkRegistry.pushLampStates();
			});
		}
		if ("H".equals(line)) {
			lastHeartbeat = System.currentTimeMillis();
			return;
		}
		MinecraftServer server = With_Stm32.server();
		if (server == null) return;
		server.execute(() -> LinkState.handleIncoming(line));
	}

	/** Queue one protocol line for the board (safe from any thread, never blocks). */
	public static boolean send(String s) {
		if (!isOpen()) return false;
		OUTBOX.add((s + "\n").getBytes(StandardCharsets.US_ASCII));
		return true;
	}

	/** Chat announcement to everyone (falls back to the log when no server). */
	public static void announce(ServerPlayer except, String text) {
		MinecraftServer server = With_Stm32.server();
		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
		} else {
			With_Stm32.LOGGER.info(text.replaceAll("§.", ""));
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
