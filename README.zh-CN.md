# With_Stm32 — Stm32Link Minecraft 模组

[English](README.md) | 中文

Fabric 1.21.8 模组，通过 CH340 串口把 Minecraft 里的方块连接到
STM32F103C8T6。它是 [Stm32Link](https://github.com/qwaszxopklnmso/Stm32Link)
项目的 Minecraft 端。

## 功能

- **联控灯（红 / 绿 / 蓝）**：行为与原版红石灯一致（点亮瞬时、熄灭延迟 4 刻、
  放置时检测旁边信号、活塞推动等边界同原版），并与板上的 LED1–LED3 双向同步。
  红石、板上按键和串口都驱动同一份状态。
- **遥测发射器**：给它红石信号，板上 PA4 的 T-LED 点亮，蜂鸣器短响一声确认；
  多个发射器共用一路 T，任意一个有信号就亮。
- **遥测接收器（三种）**：每种用独立按键：
  - **电平**：输出跟随 **B4**，15 级红石开/关。
  - **脉冲**：每按一次 **B2** 发一个短红石脉冲。
  - **触发**：每按一次 **B3** 翻转一个持续输出的锁存器。
  B1 仍是红色联控灯按键。
- **串口协议**：115200 8N1，按行传输（`L`、`T`、`N`、`W`、`Y`、`P`、`S`、`M`、`H`、`R`）。
- 联控灯**不再支持右键切换**；请用红石或板上按键控制。

## 构建

需要 JDK 21+ 和 Gradle wrapper：

```bash
./gradlew build
```

重映射后的 jar 在 `build/libs/`。可选的 `copyJar` 任务还会把它复制到本机的
`1.21.8WithSTM` 实例；实例目录不存在时会自动跳过，所以干净检出也能构建。

## 相关仓库

- **STM32 固件 / Keil 工程**：
  <https://github.com/qwaszxopklnmso/Stm32Link-STM>
- **项目入口 / 接线与部署文档**：
  <https://github.com/qwaszxopklnmso/Stm32Link>

## 许可证

项目原始代码为 MIT，见 [LICENSE](LICENSE)。Fabric 模板为 CC0，见模板原始声明。
第三方资源保留各自许可证。
