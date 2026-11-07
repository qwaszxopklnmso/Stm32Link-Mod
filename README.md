# With_Stm32 — Stm32Link Minecraft Mod

[中文](README.zh-CN.md) | English

A Fabric 1.21.8 mod that connects Minecraft blocks to an STM32F103C8T6 over a
CH340 serial link. It is the Minecraft half of the
[Stm32Link](https://github.com/qwaszxopklnmso/Stm32Link) project.

## Features

- **Link lamps (red / green / blue)** — vanilla-redstone-lamp behaviour
  (instant on, 4-tick delayed off, placement signal check, piston handling)
  mirrored with the board's LED1–LED3. Redstone and the serial link drive the
  same state; B1 is the only local lamp button (red).
- **Telemetry transmitter** — a redstone signal turns on the board's PA4
  T-LED and plays a short confirmation beep.
- **Telemetry receivers** — three variants, each on its own board button:
  - **Level**: the output follows **B4** while it is held (15); release -> 0.
  - **Pulse**: each press of **B2** emits a short redstone pulse.
  - **Toggle**: each press of **B3** flips a persistent output latch.
  B1 is the red link-lamp button only; B2/B3 no longer toggle lamps.
- **Serial protocol** — 115200 8N1, line based (`L`, `T`, `N`, `W`, `Y`,
  `P`, `S`, `M`, `H`, `R`). Ports are auto-detected: every USB-serial port
  (CH340/FTDI/CP210x/PL2303/CDC) is tried, CH340 first; a configured `port` is
  only a preference, not a filter. A port that fails the handshake is skipped
  for 30 s, so a wrong CH340 on COM3/COM5 cannot hide the board on another port.
  Bluetooth/virtual COM ports are skipped because some wedge on the first write.
  A port is opened **read-only first**; the `S` state request is sent only after
  the firmware's own `H`/`R`/`L`/`B` line proves the peer is ours.
- Link lamps no longer toggle on right-click; use redstone. B1 toggles only
  the red lamp locally.

## Build

Requires JDK 21+ and the Gradle wrapper.

```bash
./gradlew build
```

The remapped jar is written to `build/libs/`. The optional `copyJar` task
also copies it to a local `1.21.8WithSTM` instance; it is skipped when that
instance directory does not exist, so a clean checkout still builds.

## Related repositories

- **STM32 firmware / Keil project**:
  <https://github.com/qwaszxopklnmso/Stm32Link-STM>
- **Project hub and wiring/deployment docs**:
  <https://github.com/qwaszxopklnmso/Stm32Link>

## License

The original project code is MIT; see [LICENSE](LICENSE). The Fabric template
is CC0; see the template's original notice. Third-party assets keep their own
licenses.
