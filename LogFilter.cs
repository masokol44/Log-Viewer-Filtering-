// LogFilter.cs
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;

class Config
{
    [JsonPropertyName("filters")] public List<string> Filters { get; set; } = new List<string>();
    [JsonPropertyName("levels")] public List<string> Levels { get; set; } = new List<string>();
    [JsonPropertyName("since")] public string Since { get; set; } = "1h";
}

class LogEntry
{
    public DateTime? Timestamp { get; set; }
    public string Level { get; set; }
    public string Message { get; set; }
    public string Raw { get; set; }

    public LogEntry(string raw)
    {
        Raw = raw;
        Parse(raw);
    }

    private void Parse(string line)
    {
        string[] patterns = {
            @"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)",
            @"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)",
            @"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)",
            @"^(ERROR|WARN|INFO|DEBUG)\s+(.*)"
        };
        foreach (var pattern in patterns)
        {
            var match = Regex.Match(line, pattern);
            if (match.Success)
            {
                if (match.Groups.Count == 4)
                {
                    if (DateTime.TryParse(match.Groups[1].Value, out DateTime ts))
                        Timestamp = ts;
                    Level = match.Groups[2].Value;
                    Message = match.Groups[3].Value;
                }
                else if (match.Groups.Count == 3)
                {
                    Level = match.Groups[1].Value;
                    Message = match.Groups[2].Value;
                }
                return;
            }
        }
        Message = line;
    }
}

class LogFilter
{
    private static readonly string ConfigFile = "log_filter_config.json";
    private static readonly Dictionary<string, string> Colors = new Dictionary<string, string>
    {
        {"red", "\x1b[91m"}, {"green", "\x1b[92m"}, {"yellow", "\x1b[93m"},
        {"blue", "\x1b[94m"}, {"cyan", "\x1b[96m"}, {"reset", "\x1b[0m"}
    };
    private static readonly Dictionary<string, string> LevelColors = new Dictionary<string, string>
    {
        {"ERROR", "red"}, {"WARN", "yellow"}, {"INFO", "green"}, {"DEBUG", "cyan"}
    };

    private Config config;

    public LogFilter() { config = LoadConfig(); }

    private Config LoadConfig()
    {
        if (!File.Exists(ConfigFile)) return new Config();
        string json = File.ReadAllText(ConfigFile);
        return JsonSerializer.Deserialize<Config>(json) ?? new Config();
    }

    private void SaveConfig()
    {
        string json = JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigFile, json);
    }

    private long ParseSince(string sinceStr)
    {
        if (string.IsNullOrEmpty(sinceStr)) return 3600;
        int num = int.Parse(sinceStr.Substring(0, sinceStr.Length - 1));
        char unit = sinceStr[^1];
        switch (unit)
        {
            case 'm': return num * 60;
            case 'h': return num * 3600;
            case 'd': return num * 86400;
            default: return 3600;
        }
    }

    private string Highlight(string text, string filter, string regexPattern)
    {
        if (!string.IsNullOrEmpty(filter))
            return text.Replace(filter, Colors["yellow"] + filter + Colors["reset"]);
        if (!string.IsNullOrEmpty(regexPattern))
            return Regex.Replace(text, regexPattern, Colors["yellow"] + "$0" + Colors["reset"]);
        foreach (var kv in LevelColors)
        {
            if (text.Contains(kv.Key))
                return text.Replace(kv.Key, Colors[kv.Value] + kv.Key + Colors["reset"]);
        }
        return text;
    }

    public void ProcessFile(string filepath, string filter, string regexPattern, List<string> levels,
                            string sinceStr, int context, bool watch, bool stats, bool noColor)
    {
        if (!File.Exists(filepath)) { Console.WriteLine($"File '{filepath}' not found."); return; }

        if (stats) { ShowStats(filepath); return; }

        long sinceSec = ParseSince(string.IsNullOrEmpty(sinceStr) ? config.Since : sinceStr);
        DateTime cutoff = DateTime.Now.AddSeconds(-sinceSec);

        var lines = File.ReadAllLines(filepath);
        var entries = lines.Select(l => new LogEntry(l)).ToList();

        var filtered = new List<(int idx, string line, LogEntry entry)>();
        for (int i = 0; i < entries.Count; i++)
        {
            var entry = entries[i];
            var line = lines[i];
            if (entry.Timestamp.HasValue && entry.Timestamp.Value < cutoff) continue;
            if (levels.Any() && !levels.Contains(entry.Level)) continue;
            if (!string.IsNullOrEmpty(filter) && !line.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
            if (!string.IsNullOrEmpty(regexPattern) && !Regex.IsMatch(line, regexPattern)) continue;
            filtered.Add((i, line, entry));
        }

        Console.WriteLine($"\n📜 Log Viewer – {filepath}");
        if (!string.IsNullOrEmpty(filter) || !string.IsNullOrEmpty(regexPattern))
            Console.WriteLine($"Filter: {filter ?? regexPattern}");
        if (levels.Any()) Console.WriteLine($"Levels: {string.Join(", ", levels)}");
        Console.WriteLine($"Watch mode: {watch}\n");

        if (context > 0)
        {
            var shown = new HashSet<int>();
            foreach (var f in filtered)
                for (int offset = -context; offset <= context; offset++)
                {
                    int idx = f.idx + offset;
                    if (idx >= 0 && idx < lines.Length) shown.Add(idx);
                }
            foreach (int idx in shown.OrderBy(i => i))
            {
                string prefix = filtered.Any(f => f.idx == idx) ? "> " : "  ";
                string line = lines[idx];
                if (!noColor) line = Highlight(line, filter, regexPattern);
                Console.WriteLine($"{prefix}{idx+1,4}: {line}");
            }
        }
        else
        {
            foreach (var f in filtered)
            {
                string line = f.line;
                if (!noColor) line = Highlight(line, filter, regexPattern);
                Console.WriteLine($"{f.idx+1,4}: {line}");
            }
        }
        Console.WriteLine($"\n{filtered.Count} lines matched.");

        if (watch) WatchMode(filepath, filter, regexPattern, levels, sinceSec, context, noColor);
    }

    private void WatchMode(string filepath, string filter, string regexPattern, List<string> levels,
                           long sinceSec, int context, bool noColor)
    {
        Console.WriteLine("\n👁️  Watching for new lines (press Ctrl+C to stop)...");
        long lastPos = new FileInfo(filepath).Length;
        while (true)
        {
            Thread.Sleep(1000);
            var info = new FileInfo(filepath);
            if (info.Length > lastPos)
            {
                using var fs = new FileStream(filepath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                fs.Seek(lastPos, SeekOrigin.Begin);
                using var reader = new StreamReader(fs);
                string line;
                while ((line = reader.ReadLine()) != null)
                {
                    var entry = new LogEntry(line);
                    if (entry.Timestamp.HasValue && entry.Timestamp.Value < DateTime.Now.AddSeconds(-sinceSec)) continue;
                    if (levels.Any() && !levels.Contains(entry.Level)) continue;
                    if (!string.IsNullOrEmpty(filter) && !line.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
                    if (!string.IsNullOrEmpty(regexPattern) && !Regex.IsMatch(line, regexPattern)) continue;
                    if (!noColor) line = Highlight(line, filter, regexPattern);
                    Console.WriteLine($"  {line}");
                }
                lastPos = fs.Position;
            }
        }
    }

    private void ShowStats(string filepath)
    {
        var counts = new Dictionary<string, int> { {"ERROR", 0}, {"WARN", 0}, {"INFO", 0}, {"DEBUG", 0}, {"OTHER", 0} };
        foreach (var line in File.ReadLines(filepath))
        {
            var entry = new LogEntry(line);
            if (entry.Level != null && counts.ContainsKey(entry.Level))
                counts[entry.Level]++;
            else if (!string.IsNullOrWhiteSpace(line))
                counts["OTHER"]++;
        }
        Console.WriteLine("\n📊 Log Statistics:");
        foreach (var level in new[] { "ERROR", "WARN", "INFO", "DEBUG", "OTHER" })
        {
            string color = LevelColors.GetValueOrDefault(level, "reset");
            Console.WriteLine($"  {Colors[color]}{level,5}{Colors["reset"]}: {counts[level]}");
        }
    }

    static void Main(string[] args)
    {
        if (args.Length < 1) { Console.WriteLine("Usage: LogFilter <file> [options]"); return; }
        var app = new LogFilter();
        var parsed = ParseArgs(args);
        string filepath = args[0];

        if (parsed.ContainsKey("save"))
        {
            var cfg = app.config;
            if (parsed.ContainsKey("filter")) cfg.Filters = new List<string> { parsed["filter"] };
            if (parsed.ContainsKey("level")) cfg.Levels = parsed["level"].Split(',').ToList();
            if (parsed.ContainsKey("since")) cfg.Since = parsed["since"];
            app.SaveConfig();
            Console.WriteLine("✅ Filter settings saved.");
            return;
        }

        string filter = parsed.GetValueOrDefault("filter");
        string regex = parsed.GetValueOrDefault("regex");
        List<string> levels = parsed.ContainsKey("level") ?
            parsed["level"].Split(',').ToList() : new List<string>();
        string since = parsed.GetValueOrDefault("since");
        int context = parsed.ContainsKey("context") ? int.Parse(parsed["context"]) : 0;
        bool watch = parsed.ContainsKey("watch");
        bool stats = parsed.ContainsKey("stats");
        bool noColor = parsed.ContainsKey("no-color");

        // Use config defaults
        if (string.IsNullOrEmpty(filter) && string.IsNullOrEmpty(regex) && !levels.Any() && string.IsNullOrEmpty(since))
        {
            if (app.config.Filters.Any()) filter = app.config.Filters[0];
            levels = app.config.Levels;
            since = app.config.Since;
        }

        app.ProcessFile(filepath, filter, regex, levels, since, context, watch, stats, noColor);
    }

    static Dictionary<string, string> ParseArgs(string[] args)
    {
        var dict = new Dictionary<string, string>();
        for (int i = 1; i < args.Length; i++)
        {
            if (args[i].StartsWith("--"))
            {
                string key = args[i].Substring(2);
                if (i + 1 < args.Length && !args[i + 1].StartsWith("--"))
                    dict[key] = args[++i];
                else
                    dict[key] = "";
            }
        }
        return dict;
    }
}
