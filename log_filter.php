# log_filter.php
#!/usr/bin/env php
<?php

define('CONFIG_FILE', 'log_filter_config.json');

$COLORS = [
    'red' => "\033[91m",
    'green' => "\033[92m",
    'yellow' => "\033[93m",
    'blue' => "\033[94m",
    'cyan' => "\033[96m",
    'reset' => "\033[0m"
];

$LEVEL_COLORS = [
    'ERROR' => 'red',
    'WARN' => 'yellow',
    'INFO' => 'green',
    'DEBUG' => 'cyan'
];

class LogFilter {
    private $config;
    private $colors;
    private $levelColors;

    public function __construct() {
        $this->colors = $GLOBALS['COLORS'];
        $this->levelColors = $GLOBALS['LEVEL_COLORS'];
        $this->config = $this->loadConfig();
    }

    private function loadConfig() {
        if (file_exists(CONFIG_FILE)) {
            $data = json_decode(file_get_contents(CONFIG_FILE), true);
            return $data ?: ['filters' => [], 'levels' => [], 'since' => '1h'];
        }
        return ['filters' => [], 'levels' => [], 'since' => '1h'];
    }

    private function saveConfig() {
        file_put_contents(CONFIG_FILE, json_encode($this->config, JSON_PRETTY_PRINT));
    }

    private function parseSince($sinceStr) {
        if (!$sinceStr) return 3600;
        $num = (int)$sinceStr;
        $unit = substr($sinceStr, -1);
        switch ($unit) {
            case 'm': return $num * 60;
            case 'h': return $num * 3600;
            case 'd': return $num * 86400;
            default: return 3600;
        }
    }

    private function parseLogLine($line) {
        $patterns = [
            '/\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/',
            '/(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/',
            '/(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/',
            '/^(ERROR|WARN|INFO|DEBUG)\s+(.*)/'
        ];
        foreach ($patterns as $pattern) {
            if (preg_match($pattern, $line, $matches)) {
                if (count($matches) == 4) {
                    $ts = DateTime::createFromFormat('Y-m-d H:i:s', $matches[1]);
                    return ['timestamp' => $ts ?: null, 'level' => $matches[2], 'message' => $matches[3], 'raw' => $line];
                } elseif (count($matches) == 3) {
                    return ['timestamp' => null, 'level' => $matches[1], 'message' => $matches[2], 'raw' => $line];
                }
            }
        }
        return ['timestamp' => null, 'level' => null, 'message' => $line, 'raw' => $line];
    }

    private function highlight($text, $filter, $regexPattern) {
        if ($filter) {
            return str_replace($filter, $this->colors['yellow'] . $filter . $this->colors['reset'], $text);
        }
        if ($regexPattern) {
            return preg_replace('/' . $regexPattern . '/', $this->colors['yellow'] . '$0' . $this->colors['reset'], $text);
        }
        foreach ($this->levelColors as $level => $color) {
            if (strpos($text, $level) !== false) {
                return str_replace($level, $this->colors[$color] . $level . $this->colors['reset'], $text);
            }
        }
        return $text;
    }

    public function processFile($filepath, $filter, $regexPattern, $levels, $since, $context, $watch, $stats, $noColor) {
        if (!file_exists($filepath)) {
            fwrite(STDERR, "File '$filepath' not found.\n");
            return;
        }

        if ($stats) {
            $this->showStats($filepath);
            return;
        }

        $sinceSec = $this->parseSince($since ?: $this->config['since'] ?? '1h');
        $cutoff = time() - $sinceSec;

        $lines = file($filepath, FILE_IGNORE_NEW_LINES);
        $entries = array_map([$this, 'parseLogLine'], $lines);

        $filtered = [];
        foreach ($entries as $i => $entry) {
            $line = $lines[$i];
            // Time filter
            if ($entry['timestamp'] && $entry['timestamp']->getTimestamp() < $cutoff) continue;
            // Level filter
            if (!empty($levels) && !in_array($entry['level'], $levels)) continue;
            // Pattern filter
            if ($filter && stripos($line, $filter) === false) continue;
            if ($regexPattern && !preg_match('/' . $regexPattern . '/', $line)) continue;
            $filtered[] = ['idx' => $i, 'line' => $line, 'entry' => $entry];
        }

        echo "\n📜 Log Viewer – $filepath\n";
        if ($filter || $regexPattern) echo "Filter: " . ($filter ?: $regexPattern) . "\n";
        if (!empty($levels)) echo "Levels: " . implode(', ', $levels) . "\n";
        echo "Watch mode: " . ($watch ? 'on' : 'off') . "\n\n";

        if ($context > 0) {
            $shown = [];
            foreach ($filtered as $f) {
                for ($offset = -$context; $offset <= $context; $offset++) {
                    $idx = $f['idx'] + $offset;
                    if ($idx >= 0 && $idx < count($lines)) $shown[$idx] = true;
                }
            }
            ksort($shown);
            foreach ($shown as $idx => $_) {
                $prefix = in_array($idx, array_column($filtered, 'idx')) ? '> ' : '  ';
                $line = $lines[$idx];
                if (!$noColor) $line = $this->highlight($line, $filter, $regexPattern);
                echo $prefix . str_pad($idx+1, 4) . ": $line\n";
            }
        } else {
            foreach ($filtered as $f) {
                $line = $f['line'];
                if (!$noColor) $line = $this->highlight($line, $filter, $regexPattern);
                echo str_pad($f['idx']+1, 4) . ": $line\n";
            }
        }
        echo "\n" . count($filtered) . " lines matched.\n";

        if ($watch) {
            $this->watchMode($filepath, $filter, $regexPattern, $levels, $sinceSec, $context, $noColor);
        }
    }

    private function watchMode($filepath, $filter, $regexPattern, $levels, $sinceSec, $context, $noColor) {
        echo "\n👁️  Watching for new lines (press Ctrl+C to stop)...\n";
        $lastPos = filesize($filepath);
        while (true) {
            sleep(1);
            clearstatcache();
            if (!file_exists($filepath)) continue;
            $newSize = filesize($filepath);
            if ($newSize <= $lastPos) continue;
            $f = fopen($filepath, 'r');
            fseek($f, $lastPos);
            while (!feof($f)) {
                $line = fgets($f);
                if ($line === false) break;
                $line = rtrim($line, "\n\r");
                $entry = $this->parseLogLine($line);
                if ($entry['timestamp'] && $entry['timestamp']->getTimestamp() < time() - $sinceSec) continue;
                if (!empty($levels) && !in_array($entry['level'], $levels)) continue;
                if ($filter && stripos($line, $filter) === false) continue;
                if ($regexPattern && !preg_match('/' . $regexPattern . '/', $line)) continue;
                if (!$noColor) $line = $this->highlight($line, $filter, $regexPattern);
                echo "  $line\n";
            }
            $lastPos = ftell($f);
            fclose($f);
        }
    }

    private function showStats($filepath) {
        $counts = ['ERROR' => 0, 'WARN' => 0, 'INFO' => 0, 'DEBUG' => 0, 'OTHER' => 0];
        foreach (file($filepath, FILE_IGNORE_NEW_LINES) as $line) {
            $entry = $this->parseLogLine($line);
            if ($entry['level'] && isset($counts[$entry['level']])) {
                $counts[$entry['level']]++;
            } elseif (trim($line)) {
                $counts['OTHER']++;
            }
        }
        echo "\n📊 Log Statistics:\n";
        foreach (['ERROR', 'WARN', 'INFO', 'DEBUG', 'OTHER'] as $level) {
            $color = $this->levelColors[$level] ?? 'white';
            echo "  " . $this->colors[$color] . str_pad($level, 5) . $this->colors['reset'] . ": " . $counts[$level] . "\n";
        }
    }

    public function getConfig() { return $this->config; }
    public function saveConfigToFile() { $this->saveConfig(); }
}

if ($argc < 2) {
    die("Usage: php log_filter.php <file> [options]\n");
}

$app = new LogFilter();
$filepath = $argv[1];
$options = getopt("", ["filter:", "regex:", "level:", "since:", "context:", "watch", "stats", "no-color", "save"]);

if (isset($options['save'])) {
    $levels = isset($options['level']) ? explode(',', $options['level']) : [];
    $config = $app->getConfig();
    $config['filters'] = isset($options['filter']) ? [$options['filter']] : [];
    $config['levels'] = $levels;
    $config['since'] = $options['since'] ?? '1h';
    file_put_contents(CONFIG_FILE, json_encode($config, JSON_PRETTY_PRINT));
    echo "✅ Filter settings saved.\n";
    exit(0);
}

$filter = $options['filter'] ?? null;
$regex = $options['regex'] ?? null;
$levels = isset($options['level']) ? explode(',', $options['level']) : [];
$since = $options['since'] ?? null;
$context = isset($options['context']) ? (int)$options['context'] : 0;
$watch = isset($options['watch']);
$stats = isset($options['stats']);
$noColor = isset($options['no-color']);

// Use config defaults
if (!$filter && !$regex && empty($levels) && !$since) {
    $config = $app->getConfig();
    $filter = $config['filters'][0] ?? null;
    $levels = $config['levels'] ?? [];
    $since = $config['since'] ?? '1h';
}

$app->processFile($filepath, $filter, $regex, $levels, $since, $context, $watch, $stats, $noColor);
?>
