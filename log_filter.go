// log_filter.go
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"regexp"
	"strings"
	"time"
)

type Config struct {
	Filters []string `json:"filters"`
	Levels  []string `json:"levels"`
	Since   string   `json:"since"`
}

var configFile = "log_filter_config.json"

var levelColors = map[string]string{
	"ERROR": "\033[91m",
	"WARN":  "\033[93m",
	"INFO":  "\033[92m",
	"DEBUG": "\033[96m",
}
var reset = "\033[0m"
var yellow = "\033[93m"

type LogEntry struct {
	Timestamp time.Time
	Level     string
	Message   string
	Raw       string
}

func parseLogLine(line string) LogEntry {
	patterns := []struct {
		re      *regexp.Regexp
		timeIdx int
		levelIdx int
		msgIdx  int
	}{
		{regexp.MustCompile(`\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)`), 1, 2, 3},
		{regexp.MustCompile(`(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)`), 1, 2, 3},
		{regexp.MustCompile(`(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)`), 1, 2, 3},
		{regexp.MustCompile(`^(ERROR|WARN|INFO|DEBUG)\s+(.*)`), -1, 1, 2},
	}
	for _, p := range patterns {
		match := p.re.FindStringSubmatch(line)
		if match != nil {
			var ts time.Time
			if p.timeIdx >= 0 && p.timeIdx < len(match) {
				ts, _ = time.Parse("2006-01-02 15:04:05", match[p.timeIdx])
				if ts.IsZero() {
					ts, _ = time.Parse(time.RFC3339, match[p.timeIdx])
				}
			}
			level := ""
			if p.levelIdx >= 0 && p.levelIdx < len(match) {
				level = match[p.levelIdx]
			}
			msg := ""
			if p.msgIdx >= 0 && p.msgIdx < len(match) {
				msg = match[p.msgIdx]
			}
			return LogEntry{Timestamp: ts, Level: level, Message: msg, Raw: line}
		}
	}
	return LogEntry{Message: line, Raw: line}
}

func parseSince(sinceStr string) time.Duration {
	if sinceStr == "" {
		return time.Hour
	}
	num := 0
	unit := ""
	fmt.Sscanf(sinceStr, "%d%s", &num, &unit)
	switch unit {
	case "m":
		return time.Duration(num) * time.Minute
	case "h":
		return time.Duration(num) * time.Hour
	case "d":
		return time.Duration(num) * 24 * time.Hour
	default:
		return time.Hour
	}
}

func highlight(text string, filter string, regexPattern string) string {
	if filter != "" {
		return strings.ReplaceAll(text, filter, yellow+filter+reset)
	}
	if regexPattern != "" {
		re := regexp.MustCompile(regexPattern)
		return re.ReplaceAllString(text, yellow+"$0"+reset)
	}
	// Highlight level
	for level, color := range levelColors {
		if strings.Contains(text, level) {
			return strings.Replace(text, level, color+level+reset, 1)
		}
	}
	return text
}

func loadConfig() Config {
	data, err := os.ReadFile(configFile)
	if err != nil {
		return Config{Since: "1h"}
	}
	var cfg Config
	json.Unmarshal(data, &cfg)
	return cfg
}

func saveConfig(cfg Config) {
	data, _ := json.MarshalIndent(cfg, "", "  ")
	os.WriteFile(configFile, data, 0644)
}

func showStats(filepath string) {
	counts := map[string]int{"ERROR": 0, "WARN": 0, "INFO": 0, "DEBUG": 0, "OTHER": 0}
	f, _ := os.Open(filepath)
	defer f.Close()
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		entry := parseLogLine(scanner.Text())
		if entry.Level != "" {
			counts[entry.Level]++
		} else {
			counts["OTHER"]++
		}
	}
	fmt.Println("\n📊 Log Statistics:")
	for _, level := range []string{"ERROR", "WARN", "INFO", "DEBUG", "OTHER"} {
		color := levelColors[level]
		fmt.Printf("  %s%s%5s%s: %d\n", color, reset, level, reset, counts[level])
	}
}

func processFile(filepath, filter, regexPattern string, levels []string, sinceStr string, context int, watch, stats, noColor bool) {
	info, err := os.Stat(filepath)
	if os.IsNotExist(err) {
		fmt.Printf("File '%s' not found.\n", filepath)
		return
	}

	if stats {
		showStats(filepath)
		return
	}

	sinceDur := parseSince(sinceStr)
	cutoff := time.Now().Add(-sinceDur)

	f, err := os.Open(filepath)
	if err != nil {
		fmt.Printf("Error opening file: %v\n", err)
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	var lines []string
	var entries []LogEntry
	for scanner.Scan() {
		line := scanner.Text()
		entry := parseLogLine(line)
		entries = append(entries, entry)
		lines = append(lines, line)
	}

	var filtered []struct{ idx int; entry LogEntry }
	for i, entry := range entries {
		// Time filter
		if !entry.Timestamp.IsZero() && entry.Timestamp.Before(cutoff) {
			continue
		}
		// Level filter
		if len(levels) > 0 {
			found := false
			for _, l := range levels {
				if entry.Level == l {
					found = true
					break
				}
			}
			if !found {
				continue
			}
		}
		// Pattern filter
		if filter != "" && !strings.Contains(strings.ToLower(entry.Raw), strings.ToLower(filter)) {
			continue
		}
		if regexPattern != "" {
			re, err := regexp.Compile(regexPattern)
			if err == nil && !re.MatchString(entry.Raw) {
				continue
			}
		}
		filtered = append(filtered, struct{ idx int; entry LogEntry }{i, entry})
	}

	fmt.Printf("\n📜 Log Viewer – %s\n", filepath)
	if filter != "" || regexPattern != "" {
		fmt.Printf("Filter: %s\n", filter)
	}
	if len(levels) > 0 {
		fmt.Printf("Levels: %s\n", strings.Join(levels, ", "))
	}
	fmt.Printf("Watch mode: %v\n\n", watch)

	if context > 0 {
		shown := make(map[int]bool)
		for _, f := range filtered {
			for offset := -context; offset <= context; offset++ {
				idx := f.idx + offset
				if idx >= 0 && idx < len(lines) {
					shown[idx] = true
				}
			}
		}
		for i := 0; i < len(lines); i++ {
			if shown[i] {
				prefix := "  "
				for _, f := range filtered {
					if f.idx == i {
						prefix = "> "
						break
					}
				}
				line := lines[i]
				if !noColor {
					line = highlight(line, filter, regexPattern)
				}
				fmt.Printf("%s%4d: %s\n", prefix, i+1, line)
			}
		}
	} else {
		for _, f := range filtered {
			line := lines[f.idx]
			if !noColor {
				line = highlight(line, filter, regexPattern)
			}
			fmt.Printf("%4d: %s\n", f.idx+1, line)
		}
	}
	fmt.Printf("\n%d lines matched.\n", len(filtered))

	if watch {
		watchMode(filepath, filter, regexPattern, levels, sinceDur, context, noColor)
	}
}

func watchMode(filepath, filter, regexPattern string, levels []string, sinceDur time.Duration, context int, noColor bool) {
	fmt.Println("\n👁️  Watching for new lines (press Ctrl+C to stop)...")
	lastPos, _ := os.Stat(filepath)
	pos := lastPos.Size()
	for {
		time.Sleep(1 * time.Second)
		info, _ := os.Stat(filepath)
		if info.Size() > pos {
			f, _ := os.Open(filepath)
			f.Seek(pos, 0)
			scanner := bufio.NewScanner(f)
			for scanner.Scan() {
				line := scanner.Text()
				entry := parseLogLine(line)
				if !entry.Timestamp.IsZero() && entry.Timestamp.Before(time.Now().Add(-sinceDur)) {
					continue
				}
				if len(levels) > 0 {
					found := false
					for _, l := range levels {
						if entry.Level == l {
							found = true
							break
						}
					}
					if !found {
						continue
					}
				}
				if filter != "" && !strings.Contains(strings.ToLower(line), strings.ToLower(filter)) {
					continue
				}
				if regexPattern != "" {
					re, _ := regexp.Compile(regexPattern)
					if re != nil && !re.MatchString(line) {
						continue
					}
				}
				if !noColor {
					line = highlight(line, filter, regexPattern)
				}
				fmt.Printf("  %s\n", line)
			}
			pos, _ = f.Seek(0, 1)
			f.Close()
		}
	}
}

func main() {
	var (
		file        = flag.String("file", "", "Path to log file")
		filter      = flag.String("filter", "", "Filter by keyword")
		regex       = flag.String("regex", "", "Filter by regex pattern")
		levels      = flag.String("level", "", "Filter by log level (comma-separated)")
		since       = flag.String("since", "", "Time range (e.g., 5m, 1h, 2d)")
		context     = flag.Int("context", 0, "Lines of context around matches")
		watch       = flag.Bool("watch", false, "Watch for new lines")
		stats       = flag.Bool("stats", false, "Show statistics")
		noColor     = flag.Bool("no-color", false, "Disable color output")
		save        = flag.Bool("save", false, "Save current filter settings")
	)
	flag.Parse()

	if *file == "" && len(flag.Args()) > 0 {
		*file = flag.Args()[0]
	}
	if *file == "" {
		fmt.Println("Usage: log_filter <file> [options]")
		return
	}

	cfg := loadConfig()
	if *save {
		var levelList []string
		if *levels != "" {
			levelList = strings.Split(*levels, ",")
		}
		cfg.Filters = []string{*filter}
		cfg.Levels = levelList
		cfg.Since = *since
		saveConfig(cfg)
		fmt.Println("✅ Filter settings saved.")
		return
	}

	if *filter == "" && *regex == "" && *levels == "" && *since == "" {
		*filter = cfg.Filters[0]
		*levels = strings.Join(cfg.Levels, ",")
		*since = cfg.Since
	}

	var levelList []string
	if *levels != "" {
		levelList = strings.Split(*levels, ",")
		for i, l := range levelList {
			levelList[i] = strings.TrimSpace(l)
		}
	}

	processFile(*file, *filter, *regex, levelList, *since, *context, *watch, *stats, *noColor)
}
