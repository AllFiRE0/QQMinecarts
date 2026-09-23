package dev.qm.qqminecarts.config;

import dev.qm.qqminecarts.QQMinecarts;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Языковые сообщения плагина.
 * Считываются из папки lang/ (например lang/ru.yml), при отсутствии —
 * из встроенных ресурсов jar. Поддерживаются плейсхолдеры {ключ} и
 * цветовые коды через '&'.
 */
public final class Messages {

    private final String prefix;
    private final YamlConfiguration lang;

    private Messages(String prefix, YamlConfiguration lang) {
        this.prefix = prefix;
        this.lang = lang;
    }

    /** Загрузка языкового файла; фолбэк: русский -> английский -> пустой. */
    public static Messages load(QQMinecarts plugin, String language) {
        String name = (language == null || language.trim().isEmpty())
                ? "ru"
                : language.trim().toLowerCase(Locale.ROOT);

        YamlConfiguration yml = null;
        File file = new File(plugin.getDataFolder(), "lang" + File.separator + name + ".yml");
        if (file.isFile()) {
            try {
                yml = YamlConfiguration.loadConfiguration(file);
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка чтения lang/" + name + ".yml: " + t.getMessage());
                yml = null;
            }
        }
        if (yml == null) yml = fromResource(plugin, "lang/" + name + ".yml");
        if (yml == null) yml = fromResource(plugin, "lang/en.yml");
        if (yml == null) yml = new YamlConfiguration();

        String prefix = yml.getString("messages.prefix", "&8[&bQQMinecarts&8] &r");
        return new Messages(prefix, yml);
    }

    private static YamlConfiguration fromResource(QQMinecarts plugin, String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // ресурс отсутствует — вернём null
        }
        return null;
    }

    /** Сырое сообщение без преобразований (для отладки). */
    String raw(String path) {
        return lang.getString(path, path);
    }

    /**
     * Сообщение с префиксом плагина.
     * Плейсхолдеры передаются парами: "level", "3", "speed", "1.00".
     */
    public String get(String path, String... placeholderPairs) {
        return colorize(prefix + replace(raw(path), placeholderPairs));
    }

    /** Сообщение без префикса (например, для экшнбара). */
    public String getPlain(String path, String... placeholderPairs) {
        return colorize(replace(raw(path), placeholderPairs));
    }

    private static String replace(String input, String... pairs) {
        if (input == null || pairs == null || pairs.length == 0) return input;
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1] == null ? "" : pairs[i + 1];
            values.put("{" + key + "}", value);
        }
        String out = input;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static String colorize(String text) {
        return text == null ? ""
                : LegacyComponentSerializer.legacySection().serialize(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }
}