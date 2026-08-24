// log_filter.js
#!/usr/bin/env node
const fs = require('fs');
const readline = require('readline');
const { program } = require('commander');

const CONFIG_FILE = 'log_filter_config.json';

const COLORS = {
    red: '\x1b[91m',
    green: '\x1b[92m',
    yellow: '\x1b[93m',
    blue: '\x1b[94m',
    cyan: '\x1b[96m',
    reset: '\x1b[0m'
};

const LEVEL_COLORS = {
    ERROR: 'red',
    WARN: 'yellow',
    INFO: 'green',
    DEBUG: 'cyan'
};

class LogFilter {
    constructor() {
        this.config = this.loadConfig();
    }

    loadConfig() {
        if (fs.existsSync(CONFIG_FILE)) {
            try {
                return JSON.parse(fs.readFileSync(CONFIG_FILE));
            } catch (e) {}
        }
        return { filters: [], levels: [], since: '1h' };
    }

    saveConfig() {
        fs.writeFileSync(CONFIG_FILE, JSON.stringify(this.config, null, 2));
    }

    parseSince(sinceStr) {
        if (!sinceStr) return 3600000;
        const num = parseInt(sinceStr);
        const unit = sinceStr.slice(-1);
        switch (unit) {
            case 'm': return num * 60 * 1000;
            case 'h': return num * 60 * 60 * 1000;
            case 'd': return num * 24 * 60 * 60 * 1000;
            default: return 3600000;
        }
    }

    parseLogLine(line) {
        const patterns = [
            /\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
            /(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
            /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
            /^(ERROR|WARN|INFO|DEBUG)\s+(.*)/
        ];
        for (const pattern of patterns) {
            const match = line.match(pattern);
            if (match) {
                let dt = null;
                if (match.length === 4) {
                    dt = new Date(match[1]);
                    return { timestamp: isNaN(dt) ? null : dt, level: match[2], message: match[3], raw: line };
                } else if (match.length === 3) {
                    return { timestamp: null, level: match[1], message: match[2], raw: line };
                }
            }
        }
        return { timestamp: null, level: null, message: line, raw: line };
    }

    highlight(text, filter, regexPattern) {
        if (filter) {
            return text.split(filter).join(`${COLORS.yellow}${filter}${COLORS.reset}`);
        }
        if (regexPattern) {
            const re = new RegExp(regexPattern, 'g');
            return text.replace(re, `${COLORS.yellow}$&${COLORS.reset}`);
        }
        // Highlight level
        for (const [level, color] of Object.entries(LEVEL_COLORS)) {
            if (text.includes(level)) {
                return text.replace(level, `${COLORS[color]}${level}${COLORS.reset}`);
            }
        }
        return text;
    }

    processFile(filepath, filter, regexPattern, levels, sinceStr, context, watch, stats, noColor) {
        if (!fs.existsSync(filepath)) {
            console.error(`File '${filepath}' not found.`);
            return;
        }

        if (stats) {
            this.showStats(filepath);
            return;
        }

        const sinceMs = this.parseSince(sinceStr);
        const cutoff = Date.now() - sinceMs;

        const content = fs.readFileSync(filepath, 'utf8');
        const lines = content.split('\n').filter(l => l !== '');
        const entries = lines.map(line => this.parseLogLine(line));

        const filtered = [];
        for (let i = 0; i < entries.length; i++) {
            const entry = entries[i];
            const line = lines[i];
            // Time filter
            if (entry.timestamp && entry.timestamp.getTime() < cutoff) continue;
            // Level filter
            if (levels.length > 0 && !levels.includes(entry.level)) continue;
            // Pattern filter
            if (filter && !line.toLowerCase().includes(filter.toLowerCase())) continue;
            if (regexPattern) {
                const re = new RegExp(regexPattern);
                if (!re.test(line)) continue;
            }
            filtered.push({ idx: i, line, entry });
        }

        console.log(`\n📜 Log Viewer – ${filepath}`);
        if (filter || regexPattern) console.log(`Filter: ${filter || regexPattern}`);
        if (levels.length) console.log(`Levels: ${levels.join(', ')}`);
        console.log(`Watch mode: ${watch ? 'on' : 'off'}\n`);

        if (context > 0) {
            const shown = new Set();
            for (const f of filtered) {
                for (let offset = -context; offset <= context; offset++) {
                    const idx = f.idx + offset;
                    if (idx >= 0 && idx < lines.length) shown.add(idx);
                }
            }
            const sorted = [...shown].sort((a, b) => a - b);
            for (const idx of sorted) {
                const prefix = filtered.some(f => f.idx === idx) ? '> ' : '  ';
                let line = lines[idx];
                if (!noColor) line = this.highlight(line, filter, regexPattern);
                console.log(`${prefix}${String(idx+1).padStart(4)}: ${line}`);
            }
        } else {
            for (const f of filtered) {
                let line = f.line;
                if (!noColor) line = this.highlight(line, filter, regexPattern);
                console.log(`${String(f.idx+1).padStart(4)}: ${line}`);
            }
        }
        console.log(`\n${filtered.length} lines matched.`);

        if (watch) {
            this.watchMode(filepath, filter, regexPattern, levels, sinceMs, context, noColor);
        }
    }

    watchMode(filepath, filter, regexPattern, levels, sinceMs, context, noColor) {
        console.log('\n👁️  Watching for new lines (press Ctrl+C to stop)...');
        let lastPos = fs.statSync(filepath).size;
        const interval = setInterval(() => {
            const stats = fs.statSync(filepath);
            if (stats.size > lastPos) {
                const stream = fs.createReadStream(filepath, { start: lastPos, encoding: 'utf8' });
                const rl = readline.createInterface({ input: stream });
                rl.on('line', (line) => {
                    const entry = this.parseLogLine(line);
                    if (entry.timestamp && entry.timestamp.getTime() < Date.now() - sinceMs) return;
                    if (levels.length > 0 && !levels.includes(entry.level)) return;
                    if (filter && !line.toLowerCase().includes(filter.toLowerCase())) return;
                    if (regexPattern) {
                        const re = new RegExp(regexPattern);
                        if (!re.test(line)) return;
                    }
                    if (!noColor) line = this.highlight(line, filter, regexPattern);
                    console.log(`  ${line}`);
                });
                rl.on('close', () => {
                    lastPos = stats.size;
                });
            }
        }, 1000);
        process.on('SIGINT', () => {
            clearInterval(interval);
            console.log('\n👋 Watching stopped.');
            process.exit(0);
        });
    }

    showStats(filepath) {
        const counts = { ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0, OTHER: 0 };
        const content = fs.readFileSync(filepath, 'utf8');
        for (const line of content.split('\n')) {
            const entry = this.parseLogLine(line);
            if (entry.level && counts[entry.level] !== undefined) {
                counts[entry.level]++;
            } else if (line.trim()) {
                counts['OTHER']++;
            }
        }
        console.log('\n📊 Log Statistics:');
        for (const level of ['ERROR', 'WARN', 'INFO', 'DEBUG', 'OTHER']) {
            const color = LEVEL_COLORS[level] || 'white';
            console.log(`  ${COLORS[color] || ''}${level.padStart(5)}${COLORS.reset}: ${counts[level]}`);
        }
    }
}

program
    .argument('<file>', 'Path to log file')
    .option('--filter <pattern>', 'Filter by keyword')
    .option('--regex <pattern>', 'Filter by regex pattern')
    .option('--level <levels>', 'Filter by log level (comma-separated)')
    .option('--since <time>', 'Time range (e.g., 5m, 1h, 2d)')
    .option('--context <n>', 'Lines of context around matches', parseInt, 0)
    .option('--watch', 'Watch for new lines')
    .option('--stats', 'Show statistics')
    .option('--no-color', 'Disable color output')
    .option('--save', 'Save current filter settings')
    .action((file, options) => {
        const app = new LogFilter();
        let levels = options.level ? options.level.split(',').map(s => s.trim()) : [];

        if (options.save) {
            app.config.filters = [options.filter];
            app.config.levels = levels;
            app.config.since = options.since || '1h';
            app.saveConfig();
            console.log('✅ Filter settings saved.');
            return;
        }

        // Use config defaults if not provided
        if (!options.filter && !options.regex && !options.level && !options.since) {
            options.filter = app.config.filters[0];
            levels = app.config.levels;
            options.since = app.config.since || '1h';
        }

        app.processFile(file, options.filter, options.regex, levels, options.since,
                        options.context, options.watch, options.stats, options.noColor);
    });

program.parse(process.argv);
