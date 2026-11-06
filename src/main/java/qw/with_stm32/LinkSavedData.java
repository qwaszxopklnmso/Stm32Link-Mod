package qw.with_stm32;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Per-dimension positions of link lamps / receivers / transmitters, persisted in world data. */
public class LinkSavedData extends SavedData {
	// Codec.unboundedMap decodes into a Guava ImmutableMap - must wrap in a HashMap or
	// every add() on a world reloaded from disk throws UnsupportedOperationException.
	private static final Codec<LinkSavedData> CODEC = Codec
		.unboundedMap(Codec.STRING, Codec.LONG_STREAM.xmap(LinkSavedData::toSet, LinkSavedData::toStream))
		.xmap(m -> new LinkSavedData(new HashMap<>(m)), data -> data.positions);

	private final Map<String, Set<Long>> positions;

	public LinkSavedData() {
		this(new HashMap<>());
	}

	private LinkSavedData(Map<String, Set<Long>> positions) {
		this.positions = positions;
	}

	public static LinkSavedData get(ServerLevel level) {
		try {
			return level.getDataStorage().computeIfAbsent(new SavedDataType<>(
				"with_stm32_link", LinkSavedData::new, CODEC, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES));
		} catch (Throwable e) {
			With_Stm32.LOGGER.error("WithSTM32: failed to load link positions, starting fresh", e);
			return level.getDataStorage().computeIfAbsent(new SavedDataType<>(
				"with_stm32_link_fresh", LinkSavedData::new, CODEC, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES));
		}
	}

	public Set<Long> get(String key) {
		return positions.computeIfAbsent(key, k -> new HashSet<>());
	}

	public void add(String key, BlockPos pos) {
		get(key).add(pos.asLong());
		setDirty();
	}

	private static Set<Long> toSet(LongStream stream) {
		Set<Long> set = new HashSet<>();
		stream.forEach(set::add);
		return set;
	}

	private static LongStream toStream(Set<Long> set) {
		return set.stream().mapToLong(Long::longValue);
	}
}
