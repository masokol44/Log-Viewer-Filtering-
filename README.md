📜 Log Viewer (Filtering) — Multi‑Language Smart Log Filter
8 languages, one powerful log analyzer – filter logs by keyword, regex, time range, and log level with real‑time updates and color highlighting – right from your terminal.

✨ Features
🔍 Multiple filter modes – keyword, regex, log level (ERROR, WARN, INFO, DEBUG)

⏱️ Time range filtering – show logs from the last N minutes/hours

📋 Line context – show surrounding lines around matches

🎨 Color highlighting – matches highlighted in yellow, log levels colored

🔄 Auto‑refresh – watch log file for new entries (tail mode)

📊 Statistics – show counts by log level

💾 Persistent config – save filter preferences in a JSON file

🚀 Quick Start
All implementations share the same CLI pattern:

bash
# Basic filtering by keyword
<command> app.log --filter ERROR

# Regex filter
<command> app.log --regex "ERROR.*database"

# Filter by log level
<command> app.log --level ERROR --level WARN

# Show last 30 minutes of logs
<command> app.log --since 30m

# Watch mode with filtering
<command> app.log --filter "Connection" --watch

# Show context lines around matches
<command> app.log --filter "Failed" --context 2

# Show statistics
<command> app.log --stats
Arguments:

<file> – path to log file (required)

--filter <pattern> – keyword to filter by

--regex <pattern> – regular expression pattern

--level <level> – filter by log level (ERROR, WARN, INFO, DEBUG)

--since <time> – time range (e.g., 5m, 1h, 2d)

--context <n> – show N lines before/after match

--watch – auto‑refresh (tail mode)

--stats – show log level statistics

--no-color – disable color output

📸 Example Output
text
📜 Log Viewer – app.log (Filter: ERROR)
Showing 12 lines (watch mode: off)

 1: [2026-08-24 10:15:32] ERROR Database connection failed: timeout
 2: [2026-08-24 10:15:33] ERROR Failed to load configuration
 3: [2026-08-24 10:15:34] ERROR Cannot connect to API endpoint
 4: [2026-08-24 10:15:35] ERROR Disk space low: 2% remaining
 5: [2026-08-24 10:15:36] ERROR Unhandled exception in thread-4

📊 Statistics:
  ERROR: 5
  WARN:  2
  INFO:  8
  DEBUG: 3
Colors: ERROR in red, WARN in yellow, INFO in green, DEBUG in cyan.

📁 Repository Structure
text
.
├── README.md
├── python/
│   └── log_filter.py
├── go/
│   └── log_filter.go
├── javascript/
│   └── log_filter.js
├── ruby/
│   └── log_filter.rb
├── php/
│   └── log_filter.php
├── java/
│   └── LogFilter.java
├── csharp/
│   └── LogFilter.cs
└── cpp/
    └── log_filter.cpp
