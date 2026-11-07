# With_Stm32 — Stm32Link Minecraft Mod

[中文](README.zh-CN.md) | English

A Fabric 1.21.8 mod that connects Minecraft blocks to an STM32F103C8T6 over a
CH340 serial link. It is the Minecraft half of the
[Stm32Link](https://github.com/qwaszxopklnmso/Stm32Link) project.

## Features

- **Link lamps (red / green / blue)** — vanilla-redstone-lamp behaviour
  (instant on, 4-tick delayed off, placement signal check, piston handling)
  mirrored with the board's LED1–LED3. Redstone, the board buttons and the
  serial link all drive the same state.
- **Telemetry transmitter** — a redstone signal turns on the board's PA4
  T-LED and plays a short confirmation beep.
- **Telemetry receivers** — three variants, all listening to the board's B4
  button:
  - **Level**: the output follows B4 (a 15-level on/off switch).
  - **Pulse**: every B4 change emits a short redstone pulse (edge detector).
  - **Toggle**: every B4 change flips a persistent output latch.
- **Serial protocol** — 115200 8N1, line based (`L`, `T`, `N`, `W`, `Y`,
  `P`, `S`, `M`, `H`, `R`).
- Link lamps no longer toggle on right-click; use redstone or the board
  buttons.

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
