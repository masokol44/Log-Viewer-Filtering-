// LogFilter.java
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import com.google.gson.*;

class Config {
    List<String> filters = new ArrayList<>();
    List<String> levels = new ArrayList<>();
    String since = "1h";
}

class LogEntry {
    LocalDateTime timestamp;
    String level;
    String message;
    String raw;

    LogEntry(String raw) {
        this.raw = raw;
        parse(raw);
    }

    private void parse(String line) {
        String[] patterns = {
            "\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\]\\s+(ERROR|WARN|INFO|DEBUG)\\s+(.*)",
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\s+(ERROR|WARN|INFO|DEBUG)\\s+(.*)",
            "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(ERROR|WARN|INFO|DEBUG)\\s+(.*)",
            "^(ERROR|WARN|INFO|DEBUG)\\s+(.*)"
        };
        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(line);
            if (m.find()) {
                if (m.groupCount() == 3) {
                    try {
                        this.timestamp = LocalDateTime.parse(m.group(1), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    } catch (DateTimeParseException e) {
                        try {
                            this.timestamp = LocalDateTime.parse(m.group(1), DateTimeFormatter.ISO_DATE_TIME);
                        } catch (DateTimeParseException e2) {}
                    }
                    this.level = m.group(2);
                    this.message = m.group(3);
                } else if (m.groupCount() == 2) {
                    this.level = m.group(1);
                    this.message = m.group(2);
                }
                return;
            }
        }
        this.message = line;
    }
}

public class LogFilter {
    private static final String CONFIG_FILE = "log_filter_config.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> COLORS = new HashMap<>();
    private static final Map<String, String> LEVEL_COLORS = new HashMap<>();
    static {
        COLORS.put("red", "\033[91m");
        COLORS.put("green", "\033[92m");
        COLORS.put("yellow", "\033[93m");
        COLORS.put("blue", "\033[94m");
        COLORS.put("cyan", "\033[96m");
        COLORS.put("reset", "\033[0m");
        LEVEL_COLORS.put("ERROR", "red");
        LEVEL_COLORS.put("WARN", "yellow");
        LEVEL_COLORS.put("INFO", "green");
        LEVEL_COLORS.put("DEBUG", "cyan");
    }

    private Config config;

    public LogFilter() { this.config = loadConfig(); }

    private Config loadConfig() {
        try {
            Path path = Paths.get(CONFIG_FILE);
            if (Files.exists(path)) {
                String json = new String(Files.readAllBytes(path));
                return gson.fromJson(json, Config.class);
            }
        } catch (Exception e) {}
        return new Config();
    }

    private void saveConfig() {
        try {
            Files.write(Paths.get(CONFIG_FILE), gson.toJson(config).getBytes());
        } catch (Exception e) {}
    }

    private long parseSince(String sinceStr) {
        if (sinceStr == null || sinceStr.isEmpty()) return 3600;
        int num = Integer.parseInt(sinceStr.substring(0, sinceStr.length()-1));
        char unit = sinceStr.charAt(sinceStr.length()-1);
        switch (unit) {
            case 'm': return num * 60;
            case 'h': return num * 3600;
            case 'd': return num * 86400;
            default: return 3600;
        }
    }

    private String highlight(String text, String filter, String regexPattern) {
        if (filter != null && !filter.isEmpty()) {
            return text.replace(filter, COLORS.get("yellow") + filter + COLORS.get("reset"));
        }
        if (regexPattern != null && !regexPattern.isEmpty()) {
            return text.replaceAll(regexPattern, COLORS.get("yellow") + "$0" + COLORS.get("reset"));
        }
        for (Map.Entry<String, String> entry : LEVEL_COLORS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return text.replace(entry.getKey(), COLORS.get(entry.getValue()) + entry.getKey() + COLORS.get("reset"));
            }
        }
        return text;
    }

    private void showStats(String filepath) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("ERROR", 0); counts.put("WARN", 0);
        counts.put("INFO", 0); counts.put("DEBUG", 0); counts.put("OTHER", 0);
        List<String> lines = Files.readAllLines(Paths.get(filepath));
        for (String line : lines) {
            LogEntry entry = new LogEntry(line);
            if (entry.level != null && counts.containsKey(entry.level)) {
                counts.put(entry.level, counts.get(entry.level) + 1);
            } else if (!line.trim().isEmpty()) {
                counts.put("OTHER", counts.get("OTHER") + 1);
            }
        }
        System.out.println("\n📊 Log Statistics:");
        for (String level : Arrays.asList("ERROR", "WARN", "INFO", "DEBUG", "OTHER")) {
            String color = LEVEL_COLORS.getOrDefault(level, "reset");
            System.out.printf("  %s%5s%s: %d%n", COLORS.get(color), level, COLORS.get("reset"), counts.get(level));
        }
    }

    public void processFile(String filepath, String filter, String regexPattern,
                            List<String> levels, String sinceStr, int context,
                            boolean watch, boolean stats, boolean noColor) throws IOException, InterruptedException {
        Path path = Paths.get(filepath);
        if (!Files.exists(path)) {
            System.err.println("File '" + filepath + "' not found.");
            return;
        }

        if (stats) {
            showStats(filepath);
            return;
        }

        long sinceSec = parseSince(sinceStr != null ? sinceStr : config.since);
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(sinceSec);

        List<String> lines = Files.readAllLines(path);
        List<LogEntry> entries = lines.stream().map(LogEntry::new).collect(Collectors.toList());

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            String line = lines.get(i);
            // Time filter
            if (entry.timestamp != null && entry.timestamp.isBefore(cutoff)) continue;
            // Level filter
            if (!levels.isEmpty() && !levels.contains(entry.level)) continue;
            // Pattern filter
            if (filter != null && !line.toLowerCase().contains(filter.toLowerCase())) continue;
            if (regexPattern != null && !line.matches(".*" + regexPattern + ".*")) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("idx", i);
            map.put("line", line);
            map.put("entry", entry);
            filtered.add(map);
        }

        System.out.printf("\n📜 Log Viewer – %s%n", filepath);
        if (filter != null || regexPattern != null) System.out.printf("Filter: %s%n", filter != null ? filter : regexPattern);
        if (!levels.isEmpty()) System.out.printf("Levels: %s%n", String.join(", ", levels));
        System.out.printf("Watch mode: %s%n%n", watch ? "on" : "off");

        if (context > 0) {
            Set<Integer> shown = new HashSet<>();
            for (Map<String, Object> f : filtered) {
                int idx = (int)f.get("idx");
                for (int offset = -context; offset <= context; offset++) {
                    int n = idx + offset;
                    if (n >= 0 && n < lines.size()) shown.add(n);
                }
            }
            List<Integer> sorted = new ArrayList<>(shown);
            Collections.sort(sorted);
            for (int idx : sorted) {
                String prefix = filtered.stream().anyMatch(f -> (int)f.get("idx") == idx) ? "> " : "  ";
                String line = lines.get(idx);
                if (!noColor) line = highlight(line, filter, regexPattern);
                System.out.printf("%s%4d: %s%n", prefix, idx+1, line);
            }
        } else {
            for (Map<String, Object> f : filtered) {
                int idx = (int)f.get("idx");
                String line = (String)f.get("line");
                if (!noColor) line = highlight(line, filter, regexPattern);
                System.out.printf("%4d: %s%n", idx+1, line);
            }
        }
        System.out.printf("%n%d lines matched.%n", filtered.size());

        if (watch) {
            watchMode(filepath, filter, regexPattern, levels, sinceSec, context, noColor);
        }
    }

    private void watchMode(String filepath, String filter, String regexPattern,
                           List<String> levels, long sinceSec, int context, boolean noColor) throws IOException, InterruptedException {
        System.out.println("\n👁️  Watching for new lines (press Ctrl+C to stop)...");
        long lastPos = Files.size(Paths.get(filepath));
        while (true) {
            Thread.sleep(1000);
            long newSize = Files.size(Paths.get(filepath));
            if (newSize > lastPos) {
                try (RandomAccessFile raf = new RandomAccessFile(filepath, "r")) {
                    raf.seek(lastPos);
                    String line;
                    while ((line = raf.readLine()) != null) {
                        LogEntry entry = new LogEntry(line);
                        if (entry.timestamp != null && entry.timestamp.isBefore(LocalDateTime.now().minusSeconds(sinceSec))) continue;
                        if (!levels.isEmpty() && !levels.contains(entry.level)) continue;
                        if (filter != null && !line.toLowerCase().contains(filter.toLowerCase())) continue;
                        if (regexPattern != null && !line.matches(".*" + regexPattern + ".*")) continue;
                        if (!noColor) line = highlight(line, filter, regexPattern);
                        System.out.printf("  %s%n", line);
                    }
                    lastPos = raf.getFilePointer();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: LogFilter <file> [options]");
            return;
        }
        LogFilter app = new LogFilter();
        String filepath = args[0];
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i+1 < args.length) {
                params.put(args[i].substring(2), args[++i]);
            } else if (args[i].startsWith("--")) {
                params.put(args[i].substring(2), "");
            }
        }

        if (params.containsKey("save")) {
            Config cfg = app.config;
            if (params.containsKey("filter")) cfg.filters = Arrays.asList(params.get("filter"));
            if (params.containsKey("level")) cfg.levels = Arrays.asList(params.get("level").split(","));
            if (params.containsKey("since")) cfg.since = params.get("since");
            app.saveConfig();
            System.out.println("✅ Filter settings saved.");
            return;
        }

        String filter = params.get("filter");
        String regex = params.get("regex");
        List<String> levels = params.containsKey("level") ?
            Arrays.asList(params.get("level").split(",")) : new ArrayList<>();
        String since = params.get("since");
        int context = params.containsKey("context") ? Integer.parseInt(params.get("context")) : 0;
        boolean watch = params.containsKey("watch");
        boolean stats = params.containsKey("stats");
        boolean noColor = params.containsKey("no-color");

        // Use config defaults
        if (filter == null && regex == null && levels.isEmpty() && since == null) {
            if (!app.config.filters.isEmpty()) filter = app.config.filters.get(0);
            levels = app.config.levels;
            since = app.config.since;
        }

        app.processFile(filepath, filter, regex, levels, since, context, watch, stats, noColor);
    }
}
