package com.minestorm.guilds.bukkit;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AUTO-MERGE for yml files.
 *
 * After a plugin update the jar may contain keys that the server's config.yml / messages.yml
 * does not have yet. This class adds ONLY those missing keys (together with their comments)
 * to the server's file:
 *
 *  - existing values, comments, ordering and formatting are never changed or removed
 *  - a backup (config.yml.bak / messages.yml.bak) is written before the file is touched
 *  - the merged text is validated; if anything looks wrong the file is left untouched
 *
 * Toggle in config.yml:  auto-merge.config / auto-merge.messages  (default true)
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    /** key line:  <indent>key: [value]   (list items "- x" are not keys) */
    private static final Pattern KEY =
            Pattern.compile("^(\\s*)(\"[^\"]+\"|'[^']+'|[A-Za-z0-9_.\\-]+)\\s*:(\\s.*)?$");

    // ------------------------------------------------------------------ plugin entry points

    public static void mergeAll(JavaPlugin plugin) {
        boolean cfg = plugin.getConfig().getBoolean("auto-merge.config", true);
        boolean msg = plugin.getConfig().getBoolean("auto-merge.messages", true);
        if (cfg) merge(plugin, "config.yml");
        if (msg) merge(plugin, "messages.yml");
    }

    /** @return number of keys that were added */
    public static int merge(JavaPlugin plugin, String resource) {
        File target = new File(plugin.getDataFolder(), resource);
        try {
            if (!target.exists()) {
                plugin.saveResource(resource, false);
                return 0;
            }
            String defText = readResource(plugin, resource);
            if (defText == null) return 0;
            String userText = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            if (userText.length() > 0 && userText.charAt(0) == '\uFEFF') userText = userText.substring(1);

            String nl = userText.contains("\r\n") ? "\r\n" : "\n";
            List<String> added = new ArrayList<String>();
            List<String> merged = mergeLines(splitLines(defText), splitLines(userText), added);
            if (added.isEmpty()) return 0;

            String out = joinLines(merged, nl);

            // ---- safety net: result must be valid YAML and must not change any existing value
            YamlConfiguration before = new YamlConfiguration();
            before.loadFromString(userText);
            YamlConfiguration after = new YamlConfiguration();
            after.loadFromString(out);
            for (String k : before.getKeys(true)) {
                if (before.isConfigurationSection(k)) continue;
                Object a = before.get(k);
                Object b = after.get(k);
                if (a == null ? b != null : !a.equals(b)) {
                    plugin.getLogger().warning("[auto-merge] " + resource
                            + ": merge would change '" + k + "' - file left untouched.");
                    return 0;
                }
            }

            File bak = new File(plugin.getDataFolder(), resource + ".bak");
            Files.write(bak.toPath(), userText.getBytes(StandardCharsets.UTF_8));
            Files.write(target.toPath(), out.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("[auto-merge] " + resource + ": added " + added.size()
                    + " missing key(s): " + summarize(added) + "  (backup: " + bak.getName() + ")");
            return added.size();
        } catch (InvalidConfigurationException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource
                    + ": merged result is not valid YAML, file left untouched. " + ex.getMessage());
        } catch (IOException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource + ": " + ex);
        }
        return 0;
    }

    private static String summarize(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i == 8) { sb.append(", ... +").append(keys.size() - 8).append(" more"); break; }
            if (i > 0) sb.append(", ");
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pure text algorithm

    /** One "key:" line of a yml file and the block that belongs to it. */
    static final class Entry {
        String path;
        int indent;
        int line;    // the key line
        int start;   // first line incl. directly attached comment lines above
        int end;     // exclusive end of the block
    }

    /**
     * Returns a copy of {@code user} with every key of {@code def} that is missing added.
     * Missing keys are inserted at the end of their parent section (or at the end of the file for
     * top-level keys). {@code added} receives the key paths that were inserted.
     */
    static List<String> mergeLines(List<String> def, List<String> user, List<String> added) {
        Map<String, Entry> di = index(def);
        List<String> out = new ArrayList<String>(user);

        // top-most missing paths, in the order of the default file
        List<String> missing = new ArrayList<String>();
        Map<String, Entry> ui = index(out);
        for (String p : di.keySet()) {
            if (ui.containsKey(p)) continue;
            boolean covered = false;
            for (String m : missing) {
                if (p.startsWith(m + ".")) { covered = true; break; }
            }
            if (!covered) missing.add(p);
        }

        for (String path : missing) {
            Entry de = di.get(path);
            int dot = path.lastIndexOf('.');
            String parent = dot < 0 ? null : path.substring(0, dot);

            Map<String, Entry> cur = index(out);
            Entry pe = null;
            if (parent != null) {
                pe = cur.get(parent);
                if (pe == null) continue; // parent is not a plain section in the user's file -> skip safely
            }

            // siblings (same parent) in the order of the default file
            List<String> sibs = new ArrayList<String>();
            for (String p : di.keySet()) {
                boolean same = parent == null
                        ? p.indexOf('.') < 0
                        : p.startsWith(parent + ".") && p.indexOf('.', parent.length() + 1) < 0;
                if (same) sibs.add(p);
            }
            int me = sibs.indexOf(path);

            // place it right after the closest previous sibling that exists (else right before the next one)
            int insertAt = -1;
            for (int k = me - 1; k >= 0 && insertAt < 0; k--) {
                Entry u = cur.get(sibs.get(k));
                if (u != null) insertAt = u.end;
            }
            for (int k = me + 1; k < sibs.size() && insertAt < 0; k++) {
                Entry u = cur.get(sibs.get(k));
                if (u != null) insertAt = u.start;
            }
            if (insertAt < 0) {
                if (parent == null) {
                    while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
                    insertAt = out.size();
                } else {
                    insertAt = pe.end;
                }
            }

            int indent = 0;
            if (parent != null) {
                indent = pe.indent + 2;
                for (Entry x : cur.values()) {
                    if (x.path.startsWith(parent + ".") && x.path.indexOf('.', parent.length() + 1) < 0) {
                        indent = x.indent;
                        break;
                    }
                }
            }

            List<String> ins = reindent(new ArrayList<String>(def.subList(de.start, de.end)), indent - de.indent);
            boolean blankBefore = de.start > 0 && def.get(de.start - 1).trim().isEmpty();
            boolean blankAfter = de.end < def.size() && def.get(de.end).trim().isEmpty();
            if (blankBefore && insertAt > 0 && !out.get(insertAt - 1).trim().isEmpty()) ins.add(0, "");
            if (blankAfter && insertAt < out.size() && !out.get(insertAt).trim().isEmpty()) ins.add("");

            out.addAll(insertAt, ins);
            added.add(path);
        }
        return out;
    }

    /** Builds path -> block for every "key:" line. */
    static Map<String, Entry> index(List<String> lines) {
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        List<String> pathStack = new ArrayList<String>();
        List<Integer> indentStack = new ArrayList<Integer>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
            Matcher m = KEY.matcher(line);
            if (!m.matches()) continue;

            int indent = m.group(1).length();
            String key = m.group(2);
            if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
                key = key.substring(1, key.length() - 1);
            }
            while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }
            String path = pathStack.isEmpty() ? key : pathStack.get(pathStack.size() - 1) + "." + key;
            pathStack.add(path);
            indentStack.add(indent);

            Entry e = new Entry();
            e.path = path;
            e.indent = indent;
            e.line = i;
            if (!map.containsKey(path)) map.put(path, e);
        }

        for (Entry e : map.values()) {
            // block end = last non-blank, non-comment line that is deeper (or a list item of this key)
            int last = e.line;
            for (int j = e.line + 1; j < lines.size(); j++) {
                String l = lines.get(j);
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int ind = leadingSpaces(l);
                if (ind > e.indent || (ind == e.indent && t.startsWith("-"))) last = j;
                else break;
            }
            e.end = last + 1;

            // comment lines directly above the key belong to it
            int s = e.line;
            while (s - 1 >= 0 && lines.get(s - 1).trim().startsWith("#")) s--;
            e.start = s;
        }
        return map;
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static List<String> reindent(List<String> block, int shift) {
        List<String> out = new ArrayList<String>(block.size());
        for (String l : block) {
            if (l.trim().isEmpty() || shift == 0) { out.add(l); continue; }
            if (shift > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < shift; i++) sb.append(' ');
                out.add(sb.append(l).toString());
            } else {
                int remove = Math.min(-shift, leadingSpaces(l));
                out.add(l.substring(remove));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ io helpers

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text.isEmpty()) return out;
        String[] parts = text.split("\r\n|\n|\r", -1);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) n--; // trailing newline is not a line
        for (int i = 0; i < n; i++) out.add(parts[i]);
        return out;
    }

    private static String joinLines(List<String> lines, String nl) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append(nl);
        return sb.toString();
    }

    private static String readResource(JavaPlugin plugin, String name) throws IOException {
        InputStream in = plugin.getResource(name);
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
            return s;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
