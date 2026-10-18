package com.phistudio.build;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Build-only compiler. Runtime code uses Android resource IDs, never JSON keys. */
public final class I18nGenerator {
    private static final Set<String> QUANTITIES = Set.of("zero", "one", "two", "few", "many", "other");
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([-#+ 0,(<]*)(\\d+)?(?:\\.(\\d+))?([tT])?([a-zA-Z%])");
    private final SortedMap<String, JsonObject> languages = new TreeMap<>();
    private final JsonObject english;

    public I18nGenerator(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String tag = path.getFileName().toString().replaceFirst("\\.json$", "");
                Locale locale;
                try { locale = new Locale.Builder().setLanguageTag(tag).build(); }
                catch (IllformedLocaleException e) { throw invalid(path + ": invalid BCP 47 locale"); }
                if (!tag.equals(locale.toLanguageTag()) || !locale.getExtensionKeys().isEmpty()
                        || !tag.matches("[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?(?:-[a-zA-Z0-9]{5,8}|-[0-9][a-zA-Z0-9]{3})*"))
                    throw invalid(path + ": use a canonical Android-compatible BCP 47 filename");
                JsonObject root;
                try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
                    reader.setStrictness(Strictness.STRICT);
                    JsonElement parsed = read(reader);
                    if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject())
                        throw invalid(path + ": expected one JSON object");
                    root = parsed.getAsJsonObject();
                } catch (RuntimeException | IOException e) { throw invalid(path + ": " + e.getMessage()); }
                fields(root, Set.of("meta", "strings", "plurals", "arrays", "options"));
                JsonObject meta = object(root, "meta");
                fields(meta, Set.of("name", "author"));
                if (!meta.has("name") || string(meta.get("name")).isBlank()) throw invalid(tag + ": meta.name required");
                if (meta.has("author")) string(meta.get("author"));
                languages.put(tag, root);
            }
        }
        english = languages.get("en");
        if (english == null) throw invalid("languages/en.json is required");
        Set<String> names = new HashSet<>();
        for (String kind : List.of("strings", "plurals", "arrays")) {
            for (String key : object(english, kind).keySet()) {
                if (!key.matches("[a-z][a-z0-9_]*") || key.startsWith("i18n_locale_") || !names.add(key))
                    throw invalid("Invalid or duplicate resource name: " + key);
            }
        }
        for (var entry : object(english, "options").entrySet()) {
            if (!names.contains(entry.getKey())) throw invalid("Options for unknown resource: " + entry.getKey());
            JsonObject option = entry.getValue().getAsJsonObject();
            fields(option, Set.of("formatted", "translatable", "preserve"));
            for (String flag : List.of("formatted", "translatable"))
                if (option.has(flag) && (!option.get(flag).isJsonPrimitive() || !option.getAsJsonPrimitive(flag).isBoolean()))
                    throw invalid(flag + " must be boolean");
            if (option.has("preserve")) for (JsonElement token : option.getAsJsonArray("preserve"))
                if (string(token).isEmpty()) throw invalid("Empty protected token");
        }
        for (var language : languages.entrySet()) validate(language.getKey(), language.getValue());
    }

    private void validate(String tag, JsonObject root) {
        if (!tag.equals("en") && root.has("options")) throw invalid(tag + ": only en.json may define options");
        for (String kind : List.of("strings", "plurals", "arrays")) {
            JsonObject base = object(english, kind);
            for (var entry : object(root, kind).entrySet()) {
                String key = entry.getKey();
                if (!base.has(key)) throw invalid(tag + ": unknown " + kind + " key " + key);
                if (!tag.equals("en") && !flag(key, "translatable", true)) throw invalid(tag + ": protected resource " + key);
                try {
                    if (kind.equals("strings")) check(key, string(base.get(key)), string(entry.getValue()));
                    else if (kind.equals("arrays")) {
                        JsonArray source = base.getAsJsonArray(key), value = entry.getValue().getAsJsonArray();
                        if (source.size() != value.size()) throw invalid("array length differs");
                        for (int i = 0; i < value.size(); i++) check(key, string(source.get(i)), string(value.get(i)));
                    } else {
                        JsonObject source = base.getAsJsonObject(key), value = entry.getValue().getAsJsonObject();
                        if (!source.has("other") || !value.has("other")) throw invalid("plural requires other");
                        for (var item : source.entrySet()) {
                            if (!QUANTITIES.contains(item.getKey())) throw invalid("invalid quantity " + item.getKey());
                            check(key, string(source.get("other")), string(item.getValue()));
                        }
                        for (var item : value.entrySet()) {
                            if (!QUANTITIES.contains(item.getKey())) throw invalid("invalid quantity " + item.getKey());
                            check(key, string(source.get("other")), string(item.getValue()));
                        }
                    }
                } catch (RuntimeException e) { throw invalid(tag + "/" + key + ": " + e.getMessage()); }
            }
        }
    }

    private void check(String key, String base, String value) {
        if (flag(key, "formatted", true) && !contract(base).equals(contract(value)))
            throw invalid("format arguments differ");
        JsonObject option = object(object(english, "options"), key);
        if (option.has("preserve")) for (JsonElement token : option.getAsJsonArray("preserve"))
            if (!value.contains(string(token))) throw invalid("missing protected token " + string(token));
    }

    static Map<Integer, String> contract(String text) {
        Map<Integer, String> result = new TreeMap<>();
        Map<Integer, Object> arguments = new TreeMap<>();
        int implicit = 0, previous = 0;
        Matcher matcher = FORMAT.matcher(text);
        for (int offset = 0; offset < text.length();) {
            int percent = text.indexOf('%', offset);
            if (percent < 0) break;
            matcher.region(percent, text.length());
            if (!matcher.lookingAt()) throw invalid("invalid format token near " + text.substring(percent));
            String conversion = matcher.group(6).toLowerCase(Locale.ROOT);
            if (!conversion.equals("%") && !conversion.equals("n")) {
                int index = matcher.group(2).contains("<") ? previous
                        : matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : ++implicit;
                if (index < 1 || index > 1000) throw invalid("invalid argument index");
                previous = index;
                String type = matcher.group(5) != null ? "date:" + matcher.group(6)
                        : "dox".contains(conversion) ? "integer:" + conversion
                        : "efga".contains(conversion) ? "decimal:" + conversion
                        : "sbhc".contains(conversion) ? conversion : null;
                if (type == null) throw invalid("invalid conversion " + conversion);
                String prior = result.put(index, type);
                if (prior != null && !prior.equals(type)) throw invalid("incompatible argument reuse");
                arguments.put(index, matcher.group(5) != null ? new Date(0)
                        : "dox".contains(conversion) ? 1L
                        : "efga".contains(conversion) ? 1.0
                        : conversion.equals("c") ? 'x' : "text");
            }
            for (int part : new int[]{3, 4}) if (matcher.group(part) != null && Integer.parseInt(matcher.group(part)) > 10000)
                throw invalid("format width or precision exceeds limit");
            offset = matcher.end();
        }
        Object[] values = new Object[arguments.isEmpty() ? 0 : Collections.max(arguments.keySet())];
        arguments.forEach((index, value) -> values[index - 1] = value);
        try { String.format(Locale.ROOT, text, values); }
        catch (IllegalFormatException error) { throw invalid("invalid format: " + error.getMessage()); }
        return result;
    }

    public void generate(Path output) throws IOException {
        for (var language : languages.entrySet()) {
            String tag = language.getKey();
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
            for (String kind : List.of("strings", "plurals", "arrays")) {
                JsonObject resources = object(language.getValue(), kind);
                for (String key : new TreeSet<>(resources.keySet())) {
                    String element = kind.equals("strings") ? "string" : kind.equals("arrays") ? "string-array" : "plurals";
                    xml.append("    <").append(element).append(" name=\"").append(key).append('"');
                    if (kind.equals("strings") && !flag(key, "formatted", true)) xml.append(" formatted=\"false\"");
                    if (!flag(key, "translatable", true)) xml.append(" translatable=\"false\"");
                    xml.append('>');
                    if (kind.equals("strings")) xml.append(escape(string(resources.get(key))));
                    else {
                        xml.append('\n');
                        if (kind.equals("arrays")) for (JsonElement item : resources.getAsJsonArray(key))
                            xml.append("        <item>").append(escape(string(item))).append("</item>\n");
                        else for (String quantity : new TreeSet<>(resources.getAsJsonObject(key).keySet()))
                            xml.append("        <item quantity=\"").append(quantity).append("\">")
                                    .append(escape(string(resources.getAsJsonObject(key).get(quantity)))).append("</item>\n");
                        xml.append("    ");
                    }
                    xml.append("</").append(element).append(">\n");
                }
            }
            if (tag.equals("en")) {
                List<String> tags = new ArrayList<>(languages.keySet()); tags.remove("en"); tags.add(0, "en");
                for (String field : List.of("codes", "names")) {
                    xml.append("    <string-array name=\"i18n_locale_").append(field).append("\" translatable=\"false\">\n");
                    for (String code : tags) xml.append("        <item>").append(escape(field.equals("codes") ? code
                            : string(object(languages.get(code), "meta").get("name")))).append("</item>\n");
                    xml.append("    </string-array>\n");
                }
            }
            xml.append("</resources>\n");
            Path directory = output.resolve(tag.equals("en") ? "values" : "values-b+" + tag.replace('-', '+'));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("strings.xml"), xml, StandardCharsets.UTF_8);
        }
    }

    private boolean flag(String key, String name, boolean fallback) {
        JsonObject option = object(object(english, "options"), key);
        return option.has(name) ? option.get(name).getAsBoolean() : fallback;
    }
    private static String escape(String value) {
        // Android quoted strings preserve whitespace and literal @/? prefixes.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "\"";
    }
    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) ? parent.get(key).getAsJsonObject() : new JsonObject();
    }
    private static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("expected string");
        String text = value.getAsString();
        text.codePoints().forEach(code -> {
            if (code < 0x20 && code != 9 && code != 10 && code != 13 || code == 0xfffe || code == 0xffff
                    || code >= 0xd800 && code <= 0xdfff) throw invalid("character is not valid in Android XML");
        });
        return text;
    }
    private static void fields(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw invalid("unknown field " + key);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static JsonElement read(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw invalid("duplicate key " + name + " at " + reader.getPath());
                    result.add(name, read(reader));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) result.add(read(reader));
                reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid("unexpected JSON token at " + reader.getPath());
        };
    }
}
