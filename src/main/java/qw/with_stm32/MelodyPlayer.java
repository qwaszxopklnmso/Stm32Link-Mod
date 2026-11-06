package qw.with_stm32;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Streams the arranged multi-voice song to the STM32 buzzers (N<voice>:freq,ms)
 * and plays matching note block sounds in-world, both driven from the same
 * server-tick schedule (=> synchronized across voices and across MC/STM32).
 */
public final class MelodyPlayer {
	private record Note(int tick, int voice, ServerLevel level, BlockPos pos, float hz, int ms) {}

	// Only touched on the server thread (schedule + tick both happen there).
	private static final List<Note> QUEUE = new ArrayList<>();

	private static final SoundEvent[] VOICE_SOUND = {
		SoundEvents.NOTE_BLOCK_HARP.value(),
		SoundEvents.NOTE_BLOCK_BELL.value(),
		SoundEvents.NOTE_BLOCK_FLUTE.value(),
		SoundEvents.NOTE_BLOCK_BASS.value(),
	};
	private static final float[] VOICE_VOLUME = {3.0f, 2.2f, 1.8f, 2.5f};

	private MelodyPlayer() {}

	public static void init() {}

	/** Play the 8-bit pipa song (the only song). */
	public static void play(ServerLevel level, BlockPos pos) {
		play(level, pos, Stm32Melody2.VOICES, "8bit 琵琶曲（四声部）");
	}

	/** Play any arranged song (all voices). */
	public static void play(ServerLevel level, BlockPos pos, float[][][] voices, String label) {
		for (int v = 0; v < voices.length; v++) {
			schedule(v, level, pos, voices[v]);
		}
		notifyNearby(level, pos, "§d[曲哨] 开始向 STM32 同步播放 " + label);
	}

	/** Short motif when a player dies (melody voice only). */
	public static void onPlayerDeath(ServerPlayer player) {
		schedule(0, player.level(), player.blockPosition(), new float[][] {
			{659.3f, 180}, {523.3f, 180}, {440.0f, 360}, {0, 120}, {349.2f, 300}, {293.7f, 500}
		});
	}

	private static synchronized void schedule(int voice, ServerLevel level, BlockPos pos, float[][] notes) {
		MinecraftServer server = level.getServer();
		if (server == null) return;
		int t = server.getTickCount() + 2;
		for (float[] n : notes) {
			int ms = (int) n[1];
			int durTicks = Math.max(1, Math.round(ms / 50f));
			if (n[0] > 0) QUEUE.add(new Note(t, voice, level, pos.immutable(), n[0], ms));
			t += durTicks;
		}
	}

	/** One server tick of the scheduler. */
	public static void tick(MinecraftServer server) {
		if (QUEUE.isEmpty()) return;
		int now = server.getTickCount();
		Iterator<Note> it = QUEUE.iterator();
		while (it.hasNext()) {
			Note n = it.next();
			if (n.tick() > now) continue;
			it.remove();
			SerialLink.send(String.format("N%d:%d,%d", n.voice() + 1, Math.round(n.hz()), n.ms()));
			ServerLevel level = n.level();
			if (level.isLoaded(n.pos())) {
				float pitch = noteBlockPitch(n.hz());
				level.playSound(null, n.pos(), VOICE_SOUND[n.voice()],
					SoundSource.RECORDS, VOICE_VOLUME[n.voice()], pitch);
			}
		}
	}

	/** Note block pitch is clamped to 0.5..2.0 around F#4 (=midi 66). */
	private static float noteBlockPitch(float hz) {
		float midi = (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
		while (midi > 90) midi -= 12;
		while (midi < 42) midi += 12;
		float pitch = (float) Math.pow(2, (midi - 66) / 12.0);
		return Math.clamp(pitch, 0.5f, 2.0f);
	}

	private static void notifyNearby(ServerLevel level, BlockPos pos, String text) {
		for (Player p : level.players()) {
			if (p.blockPosition().distSqr(pos) < 32 * 32) {
				p.displayClientMessage(Component.literal(text), true);
			}
		}
	}
}
