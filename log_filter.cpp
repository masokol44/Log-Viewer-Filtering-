// log_filter.cpp
#include <iostream>
#include <fstream>
#include <string>
#include <vector>
#include <map>
#include <regex>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <thread>
#include <chrono>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <getopt.h>

using namespace std;
using json = nlohmann::json;
namespace fs = std::filesystem;

const string CONFIG_FILE = "log_filter_config.json";

map<string, string> COLORS = {
    {"red", "\033[91m"},
    {"green", "\033[92m"},
    {"yellow", "\033[93m"},
    {"blue", "\033[94m"},
    {"cyan", "\033[96m"},
    {"reset", "\033[0m"}
};

map<string, string> LEVEL_COLORS = {
    {"ERROR", "red"},
    {"WARN", "yellow"},
    {"INFO", "green"},
    {"DEBUG", "cyan"}
};

struct LogEntry {
    time_t timestamp = 0;
    string level;
    string message;
    string raw;
};

json loadConfig() {
    ifstream f(CONFIG_FILE);
    if (!f.is_open()) return json::object();
    json j;
    f >> j;
    return j;
}

void saveConfig(const json& j) {
    ofstream f(CONFIG_FILE);
    f << setw(2) << j << endl;
}

time_t parseTime(const string& s) {
    struct tm tm = {};
    if (strptime(s.c_str(), "%Y-%m-%d %H:%M:%S", &tm) != nullptr) {
        return mktime(&tm);
    }
    return 0;
}

LogEntry parseLogLine(const string& line) {
    LogEntry entry;
    entry.raw = line;
    vector<string> patterns = {
        R"(^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)$)",
        R"(^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)$)",
        R"(^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)$)",
        R"(^(ERROR|WARN|INFO|DEBUG)\s+(.*)$)"
    };
    for (auto& p : patterns) {
        regex re(p);
        smatch match;
        if (regex_search(line, match, re)) {
            if (match.size() == 4) {
                entry.timestamp = parseTime(match[1]);
                entry.level = match[2];
                entry.message = match[3];
            } else if (match.size() == 3) {
                entry.level = match[1];
                entry.message = match[2];
            }
            return entry;
        }
    }
    entry.message = line;
    return entry;
}

long parseSince(const string& s) {
    if (s.empty()) return 3600;
    int num = stoi(s.substr(0, s.size()-1));
    char unit = s.back();
    switch (unit) {
        case 'm': return num * 60;
        case 'h': return num * 3600;
        case 'd': return num * 86400;
        default: return 3600;
    }
}

string highlight(const string& text, const string& filter, const string& regexPattern) {
    if (!filter.empty()) {
        string result = text;
        size_t pos = 0;
        while ((pos = result.find(filter, pos)) != string::npos) {
            result.replace(pos, filter.length(), COLORS["yellow"] + filter + COLORS["reset"]);
            pos += COLORS["yellow"].length() + filter.length() + COLORS["reset"].length();
        }
        return result;
    }
    if (!regexPattern.empty()) {
        regex re(regexPattern);
        return regex_replace(text, re, COLORS["yellow"] + "$0" + COLORS["reset"]);
    }
    for (auto& kv : LEVEL_COLORS) {
        if (text.find(kv.first) != string::npos) {
            string result = text;
            size_t pos = result.find(kv.first);
            if (pos != string::npos) {
                result.replace(pos, kv.first.length(), COLORS[kv.second] + kv.first + COLORS["reset"]);
                return result;
            }
        }
    }
    return text;
}

void showStats(const string& filepath) {
    map<string, int> counts = {{"ERROR",0}, {"WARN",0}, {"INFO",0}, {"DEBUG",0}, {"OTHER",0}};
    ifstream f(filepath);
    string line;
    while (getline(f, line)) {
        LogEntry entry = parseLogLine(line);
        if (counts.count(entry.level)) counts[entry.level]++;
        else if (!line.empty()) counts["OTHER"]++;
    }
    cout << "\n📊 Log Statistics:\n";
    for (auto& level : {"ERROR", "WARN", "INFO", "DEBUG", "OTHER"}) {
        string color = LEVEL_COLORS.count(level) ? COLORS[LEVEL_COLORS[level]] : "";
        cout << "  " << color << setw(5) << level << COLORS["reset"] << ": " << counts[level] << "\n";
    }
}

void processFile(const string& filepath, const string& filter, const string& regexPattern,
                 const vector<string>& levels, const string& sinceStr, int context,
                 bool watch, bool stats, bool noColor) {
    if (!fs::exists(filepath)) {
        cerr << "File '" << filepath << "' not found.\n";
        return;
    }

    if (stats) {
        showStats(filepath);
        return;
    }

    long sinceSec = parseSince(sinceStr);
    time_t cutoff = time(nullptr) - sinceSec;

    ifstream f(filepath);
    vector<string> lines;
    vector<LogEntry> entries;
    string line;
    while (getline(f, line)) {
        lines.push_back(line);
        entries.push_back(parseLogLine(line));
    }
    f.close();

    vector<pair<int, string>> filtered;
    for (size_t i = 0; i < entries.size(); i++) {
        auto& entry = entries[i];
        auto& line = lines[i];
        if (entry.timestamp != 0 && entry.timestamp < cutoff) continue;
        if (!levels.empty() && find(levels.begin(), levels.end(), entry.level) == levels.end()) continue;
        if (!filter.empty() && line.find(filter) == string::npos) continue;
        if (!regexPattern.empty() && !regex_search(line, regex(regexPattern))) continue;
        filtered.push_back({i, line});
    }

    cout << "\n📜 Log Viewer – " << filepath << "\n";
    if (!filter.empty() || !regexPattern.empty())
        cout << "Filter: " << (filter.empty() ? regexPattern : filter) << "\n";
    if (!levels.empty()) {
        cout << "Levels: ";
        for (size_t i=0; i<levels.size(); i++) {
            if (i) cout << ", ";
            cout << levels[i];
        }
        cout << "\n";
    }
    cout << "Watch mode: " << (watch ? "on" : "off") << "\n\n";

    if (context > 0) {
        set<int> shown;
        for (auto& f : filtered) {
            for (int offset = -context; offset <= context; offset++) {
                int idx = f.first + offset;
                if (idx >= 0 && idx < (int)lines.size()) shown.insert(idx);
            }
        }
        for (int idx : shown) {
            string prefix = "  ";
            for (auto& f : filtered) {
                if (f.first == idx) { prefix = "> "; break; }
            }
            string line = lines[idx];
            if (!noColor) line = highlight(line, filter, regexPattern);
            cout << prefix << setw(4) << idx+1 << ": " << line << "\n";
        }
    } else {
        for (auto& f : filtered) {
            string line = f.second;
            if (!noColor) line = highlight(line, filter, regexPattern);
            cout << setw(4) << f.first+1 << ": " << line << "\n";
        }
    }
    cout << "\n" << filtered.size() << " lines matched.\n";

    if (watch) {
        cout << "\n👁️  Watching for new lines (press Ctrl+C to stop)...\n";
        long lastPos = fs::file_size(filepath);
        while (true) {
            this_thread::sleep_for(chrono::seconds(1));
            if (!fs::exists(filepath)) continue;
            long newSize = fs::file_size(filepath);
            if (newSize > lastPos) {
                ifstream fw(filepath);
                fw.seekg(lastPos);
                string l;
                while (getline(fw, l)) {
                    LogEntry entry = parseLogLine(l);
                    if (entry.timestamp != 0 && entry.timestamp < time(nullptr) - sinceSec) continue;
                    if (!levels.empty() && find(levels.begin(), levels.end(), entry.level) == levels.end()) continue;
                    if (!filter.empty() && l.find(filter) == string::npos) continue;
                    if (!regexPattern.empty() && !regex_search(l, regex(regexPattern))) continue;
                    if (!noColor) l = highlight(l, filter, regexPattern);
                    cout << "  " << l << "\n";
                }
                lastPos = fw.tellg();
                fw.close();
            }
        }
    }
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        cerr << "Usage: log_filter <file> [options]\n";
        return 1;
    }
    string filepath = argv[1];
    string filter, regexPattern, sinceStr;
    vector<string> levels;
    int context = 0;
    bool watch = false, stats = false, noColor = false, save = false;

    static struct option long_options[] = {
        {"filter", required_argument, 0, 'f'},
        {"regex", required_argument, 0, 'r'},
        {"level", required_argument, 0, 'l'},
        {"since", required_argument, 0, 's'},
        {"context", required_argument, 0, 'c'},
        {"watch", no_argument, 0, 'w'},
        {"stats", no_argument, 0, 't'},
        {"no-color", no_argument, 0, 'n'},
        {"save", no_argument, 0, 'S'},
        {0,0,0,0}
    };
    int opt;
    while ((opt = getopt_long(argc, argv, "f:r:l:s:c:wtnS", long_options, nullptr)) != -1) {
        switch (opt) {
            case 'f': filter = optarg; break;
            case 'r': regexPattern = optarg; break;
            case 'l': {
                stringstream ss(optarg);
                string item;
                while (getline(ss, item, ',')) levels.push_back(item);
                break;
            }
            case 's': sinceStr = optarg; break;
            case 'c': context = stoi(optarg); break;
            case 'w': watch = true; break;
            case 't': stats = true; break;
            case 'n': noColor = true; break;
            case 'S': save = true; break;
            default:
                cerr << "Usage: log_filter <file> [--filter PATTERN] [--regex PATTERN] [--level LEVELS] [--since TIME] [--context N] [--watch] [--stats] [--no-color] [--save]\n";
                return 1;
        }
    }

    json config = loadConfig();
    if (save) {
        config["filters"] = json::array();
        if (!filter.empty()) config["filters"].push_back(filter);
        config["levels"] = levels;
        config["since"] = sinceStr.empty() ? "1h" : sinceStr;
        saveConfig(config);
        cout << "✅ Filter settings saved.\n";
        return 0;
    }

    if (filter.empty() && regexPattern.empty() && levels.empty() && sinceStr.empty()) {
        if (config.contains("filters") && !config["filters"].empty())
            filter = config["filters"][0];
        if (config.contains("levels"))
            levels = config["levels"].get<vector<string>>();
        if (config.contains("since"))
            sinceStr = config["since"];
    }

    processFile(filepath, filter, regexPattern, levels, sinceStr, context, watch, stats, noColor);
    return 0;
}
