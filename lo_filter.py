# log_filter.py
import sys
import os
import re
import time
import argparse
import json
from datetime import datetime, timedelta
from typing import List, Dict, Optional, Tuple

CONFIG_FILE = "log_filter_config.json"

# ANSI color codes
COLORS = {
    'red': '\033[91m',
    'green': '\033[92m',
    'yellow': '\033[93m',
    'blue': '\033[94m',
    'cyan': '\033[96m',
    'reset': '\033[0m'
}

LEVEL_COLORS = {
    'ERROR': 'red',
    'WARN': 'yellow',
    'INFO': 'green',
    'DEBUG': 'cyan'
}

class LogFilter:
    def __init__(self):
        self.config = self.load_config()

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE, 'r') as f:
                return json.load(f)
        return {'filters': [], 'levels': [], 'since': '1h'}

    def save_config(self):
        with open(CONFIG_FILE, 'w') as f:
            json.dump(self.config, f, indent=2)

    def parse_since(self, since_str: str) -> timedelta:
        """Parse time string like '5m', '2h', '1d' into timedelta."""
        if not since_str:
            return timedelta(hours=1)
        num = int(since_str[:-1])
        unit = since_str[-1]
        if unit == 'm':
            return timedelta(minutes=num)
        elif unit == 'h':
            return timedelta(hours=num)
        elif unit == 'd':
            return timedelta(days=num)
        return timedelta(hours=1)

    def parse_log_line(self, line: str) -> Tuple[Optional[datetime], Optional[str], str]:
        """Try to parse log line: timestamp, level, message."""
        # Common log formats: ISO timestamp, level, message
        # Example: [2026-08-24 10:15:32] ERROR Connection failed
        patterns = [
            r'\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)',
            r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)',
            r'(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)',
            r'^(ERROR|WARN|INFO|DEBUG)\s+(.*)',
        ]
        for pattern in patterns:
            match = re.search(pattern, line)
            if match:
                groups = match.groups()
                if len(groups) == 3:
                    try:
                        dt = datetime.strptime(groups[0], '%Y-%m-%d %H:%M:%S')
                    except:
                        try:
                            dt = datetime.fromisoformat(groups[0])
                        except:
                            dt = None
                    return dt, groups[1], groups[2]
                elif len(groups) == 2:
                    return None, groups[0], groups[1]
        return None, None, line

    def filter_by_level(self, line: str, levels: List[str]) -> bool:
        if not levels:
            return True
        _, level, _ = self.parse_log_line(line)
        return level in levels if level else False

    def filter_by_time(self, dt: Optional[datetime], since_td: timedelta) -> bool:
        if dt is None:
            return True
        return dt >= datetime.now() - since_td

    def filter_by_pattern(self, line: str, pattern: str, regex: bool = False) -> bool:
        if not pattern:
            return True
        if regex:
            return bool(re.search(pattern, line))
        return pattern.lower() in line.lower()

    def highlight(self, text: str, pattern: str = None, regex: bool = False) -> str:
        """Apply color highlighting to the line."""
        if pattern:
            if regex:
                return re.sub(pattern, f"{COLORS['yellow']}\\g<0>{COLORS['reset']}", text)
            else:
                return text.replace(pattern, f"{COLORS['yellow']}{pattern}{COLORS['reset']}")
        
        # Highlight log level
        _, level, message = self.parse_log_line(text)
        if level and level in LEVEL_COLORS:
            color = LEVEL_COLORS[level]
            # Replace level with colored version
            return text.replace(level, f"{COLORS[color]}{level}{COLORS['reset']}")
        return text

    def process_file(self, filepath: str, filter_str: str = None, regex: bool = False,
                     levels: List[str] = None, since: str = None, context: int = 0,
                     watch: bool = False, stats: bool = False, no_color: bool = False):
        
        if not os.path.exists(filepath):
            print(f"File '{filepath}' not found.", file=sys.stderr)
            return

        if not levels:
            levels = self.config.get('levels', [])
        if not filter_str and not regex:
            filter_str = self.config.get('filters', [''])[0] if self.config.get('filters') else None
        if not since:
            since = self.config.get('since', '1h')

        since_td = self.parse_since(since)

        if stats:
            self.show_stats(filepath, levels)
            return

        print(f"\n📜 Log Viewer – {filepath}")
        if filter_str or regex:
            print(f"Filter: {filter_str if filter_str else regex}")
        if levels:
            print(f"Levels: {', '.join(levels)}")
        print(f"Watch mode: {'on' if watch else 'off'}\n")

        # Read file
        with open(filepath, 'r') as f:
            lines = f.readlines()

        filtered_lines = []
        for i, line in enumerate(lines):
            dt, level, _ = self.parse_log_line(line)
            # Filter by time
            if not self.filter_by_time(dt, since_td):
                continue
            # Filter by level
            if levels and not self.filter_by_level(line, levels):
                continue
            # Filter by pattern
            if filter_str and not self.filter_by_pattern(line, filter_str, regex):
                continue
            filtered_lines.append((i, line))

        # Show results with context
        if context > 0:
            shown_indices = set()
            for idx, _ in filtered_lines:
                for offset in range(-context, context + 1):
                    n = idx + offset
                    if 0 <= n < len(lines):
                        shown_indices.add(n)
            shown_indices = sorted(shown_indices)
            for i in shown_indices:
                if i in [idx for idx, _ in filtered_lines]:
                    prefix = "> "
                else:
                    prefix = "  "
                line = lines[i]
                if not no_color:
                    line = self.highlight(line, filter_str, regex)
                print(f"{prefix}{i+1:4d}: {line.rstrip()}")
        else:
            for i, line in filtered_lines:
                line_num = i + 1
                if not no_color:
                    line = self.highlight(line, filter_str, regex)
                print(f"{line_num:4d}: {line.rstrip()}")

        print(f"\n{len(filtered_lines)} lines matched.")

        if watch:
            self.watch_mode(filepath, filter_str, regex, levels, since_td, context, no_color)

    def watch_mode(self, filepath, filter_str, regex, levels, since_td, context, no_color):
        print("\n👁️  Watching for new lines (press Ctrl+C to stop)...")
        try:
            last_pos = os.path.getsize(filepath)
            while True:
                time.sleep(1)
                if os.path.getsize(filepath) > last_pos:
                    with open(filepath, 'r') as f:
                        f.seek(last_pos)
                        new_lines = f.readlines()
                        last_pos = f.tell()
                    for line in new_lines:
                        dt, level, _ = self.parse_log_line(line)
                        if not self.filter_by_time(dt, since_td):
                            continue
                        if levels and not self.filter_by_level(line, levels):
                            continue
                        if filter_str and not self.filter_by_pattern(line, filter_str, regex):
                            continue
                        if not no_color:
                            line = self.highlight(line, filter_str, regex)
                        print(f"  {line.rstrip()}")
        except KeyboardInterrupt:
            print("\n👋 Watching stopped.")

    def show_stats(self, filepath, levels):
        counts = {'ERROR': 0, 'WARN': 0, 'INFO': 0, 'DEBUG': 0, 'OTHER': 0}
        with open(filepath, 'r') as f:
            for line in f:
                _, level, _ = self.parse_log_line(line)
                if level in counts:
                    counts[level] += 1
                else:
                    counts['OTHER'] += 1
        print("\n📊 Log Statistics:")
        for level in ['ERROR', 'WARN', 'INFO', 'DEBUG', 'OTHER']:
            if levels and level not in levels and level != 'OTHER':
                continue
            color = LEVEL_COLORS.get(level, 'white')
            print(f"  {COLORS.get(color, '')}{level:5}{COLORS['reset']}: {counts[level]}")

def main():
    parser = argparse.ArgumentParser(description="Log Viewer with Filtering")
    parser.add_argument('file', help='Path to log file')
    parser.add_argument('--filter', help='Filter by keyword')
    parser.add_argument('--regex', help='Filter by regex pattern')
    parser.add_argument('--level', action='append', choices=['ERROR', 'WARN', 'INFO', 'DEBUG'], help='Filter by log level')
    parser.add_argument('--since', help='Time range (e.g., 5m, 1h, 2d)')
    parser.add_argument('--context', type=int, default=0, help='Lines of context around matches')
    parser.add_argument('--watch', action='store_true', help='Watch for new lines')
    parser.add_argument('--stats', action='store_true', help='Show statistics')
    parser.add_argument('--no-color', action='store_true', help='Disable color output')
    parser.add_argument('--save', action='store_true', help='Save current filter settings')
    args = parser.parse_args()

    if args.save:
        config = {'filters': [args.filter] if args.filter else [],
                  'levels': args.level or [],
                  'since': args.since or '1h'}
        with open(CONFIG_FILE, 'w') as f:
            json.dump(config, f, indent=2)
        print("✅ Filter settings saved.")
        return

    app = LogFilter()
    app.process_file(args.file, args.filter, args.regex, args.level, args.since,
                     args.context, args.watch, args.stats, args.no_color)

if __name__ == "__main__":
    main()
