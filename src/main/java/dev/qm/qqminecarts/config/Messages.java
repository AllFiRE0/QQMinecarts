package dev.qm.qqminecarts.config;

import dev.qm.qqminecarts.QQMinecarts;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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
     * Пустое сообщение в lang -> возвращается "".
     */
    public String get(String path, String... placeholderPairs) {
        String text = replace(raw(path), placeholderPairs);
        if (text == null || text.isEmpty()) return "";
        return colorize(prefix + text);
    }

    /** Сообщение без префикса (например, для экшнбара).
     *  Пустое сообщение в lang -> возвращается "". */
    public String getPlain(String path, String... placeholderPairs) {
        String text = replace(raw(path), placeholderPairs);
        if (text == null || text.isEmpty()) return "";
        return colorize(text);
    }

    /** Переведённое имя моба (messages.mobs.<key>, где key = horse, skeleton-horse...).
     *  Если перевода нет — возвращается сам ключ (напр. "pig"). Можно красить через '&'. */
    public String mobName(String key) {
        String name = lang.getString("messages.mobs." + key, null);
        if (name == null || name.trim().isEmpty()) {
            return key;
        }
        return colorize(name);
    }

    /** Отправка чат-сообщения с префиксом. Пустое сообщение НЕ отправляется
     *  вообще (чтобы можно было отключить любой текст, указав "" в lang). */
    public void sendMessage(CommandSender target, String path, String... placeholderPairs) {
        if (target == null) return;
        String text = replace(raw(path), placeholderPairs);
        if (text == null || text.isEmpty()) return;
        target.sendMessage(colorize(prefix + text));
    }

    /** Отправка экшнбара без префикса. Пустое сообщение НЕ отправляется. */
    public void sendActionBar(Player target, String path, String... placeholderPairs) {
        if (target == null) return;
        String text = replace(raw(path), placeholderPairs);
        if (text == null || text.isEmpty()) return;
        target.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(colorize(text)));
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