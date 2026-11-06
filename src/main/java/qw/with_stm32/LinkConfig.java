package qw.with_stm32;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Persistent config: config/with_stm32.json */
public final class LinkConfig {
	public static String portName = "";   // "" = auto-detect (prefers CH340)
	public static int baud = 115200;

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("with_stm32.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Data(String port, int baud) {
		Data { if (port == null) port = ""; if (baud <= 0) baud = 115200; }
	}

	public static void load() {
		if (!Files.exists(PATH)) {
			save();
			return;
		}
		try {
			Data d = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), Data.class);
			if (d != null) {
				portName = d.port();
				baud = d.baud();
			}
		} catch (Exception e) {
			With_Stm32.LOGGER.warn("Failed to read with_stm32.json, using defaults", e);
		}
	}

	public static void save() {
		try {
			Files.writeString(PATH, GSON.toJson(new Data(portName, baud)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			With_Stm32.LOGGER.warn("Failed to write with_stm32.json", e);
		}
	}
}
