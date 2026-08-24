# log_filter.rb
#!/usr/bin/env ruby
require 'json'
require 'optparse'
require 'date'
require 'time'

CONFIG_FILE = 'log_filter_config.json'

COLORS = {
  red: "\e[91m",
  green: "\e[92m",
  yellow: "\e[93m",
  blue: "\e[94m",
  cyan: "\e[96m",
  reset: "\e[0m"
}

LEVEL_COLORS = {
  'ERROR' => :red,
  'WARN' => :yellow,
  'INFO' => :green,
  'DEBUG' => :cyan
}

class LogFilter
  attr_reader :config

  def initialize
    @config = load_config
  end

  def load_config
    if File.exist?(CONFIG_FILE)
      JSON.parse(File.read(CONFIG_FILE))
    else
      { 'filters' => [], 'levels' => [], 'since' => '1h' }
    end
  end

  def save_config
    File.write(CONFIG_FILE, JSON.pretty_generate(@config))
  end

  def parse_since(since_str)
    return 3600 unless since_str
    num = since_str.to_i
    unit = since_str[-1]
    case unit
    when 'm' then num * 60
    when 'h' then num * 3600
    when 'd' then num * 86400
    else 3600
    end
  end

  def parse_log_line(line)
    patterns = [
      /\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
      /(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
      /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(ERROR|WARN|INFO|DEBUG)\s+(.*)/,
      /^(ERROR|WARN|INFO|DEBUG)\s+(.*)/
    ]
    patterns.each do |pattern|
      match = line.match(pattern)
      if match
        if match.length == 4
          ts = DateTime.parse(match[1]) rescue nil
          return { timestamp: ts, level: match[2], message: match[3], raw: line }
        elsif match.length == 3
          return { timestamp: nil, level: match[1], message: match[2], raw: line }
        end
      end
    end
    { timestamp: nil, level: nil, message: line, raw: line }
  end

  def highlight(text, filter, regex_pattern)
    if filter
      text.gsub(filter, "#{COLORS[:yellow]}#{filter}#{COLORS[:reset]}")
    elsif regex_pattern
      text.gsub(Regexp.new(regex_pattern), "#{COLORS[:yellow]}\\0#{COLORS[:reset]}")
    else
      # Highlight level
      LEVEL_COLORS.each do |level, color|
        if text.include?(level)
          return text.sub(level, "#{COLORS[color]}#{level}#{COLORS[:reset]}")
        end
      end
      text
    end
  end

  def process_file(filepath, filter: nil, regex_pattern: nil, levels: [], since: nil,
                   context: 0, watch: false, stats: false, no_color: false)
    unless File.exist?(filepath)
      warn "File '#{filepath}' not found."
      return
    end

    if stats
      show_stats(filepath)
      return
    end

    since_sec = parse_since(since || @config['since'] || '1h')
    cutoff = Time.now - since_sec

    lines = File.readlines(filepath, chomp: true)
    entries = lines.map { |line| parse_log_line(line) }

    filtered = []
    entries.each_with_index do |entry, i|
      line = lines[i]
      # Time filter
      if entry[:timestamp] && entry[:timestamp].to_time < cutoff
        next
      end
      # Level filter
      if !levels.empty? && !levels.include?(entry[:level])
        next
      end
      # Pattern filter
      if filter && !line.downcase.include?(filter.downcase)
        next
      end
      if regex_pattern && !line.match?(Regexp.new(regex_pattern))
        next
      end
      filtered << { idx: i, line: line, entry: entry }
    end

    puts "\n📜 Log Viewer – #{filepath}"
    puts "Filter: #{filter || regex_pattern}" if filter || regex_pattern
    puts "Levels: #{levels.join(', ')}" unless levels.empty?
    puts "Watch mode: #{watch ? 'on' : 'off'}\n"

    if context > 0
      shown = Set.new
      filtered.each do |f|
        (-context..context).each do |offset|
          idx = f[:idx] + offset
          shown << idx if idx >= 0 && idx < lines.length
        end
      end
      shown.sort.each do |idx|
        prefix = filtered.any? { |f| f[:idx] == idx } ? '> ' : '  '
        line = lines[idx]
        line = highlight(line, filter, regex_pattern) unless no_color
        puts "#{prefix}#{idx+1.to_s.rjust(4)}: #{line}"
      end
    else
      filtered.each do |f|
        line = f[:line]
        line = highlight(line, filter, regex_pattern) unless no_color
        puts "#{(f[:idx]+1).to_s.rjust(4)}: #{line}"
      end
    end
    puts "\n#{filtered.length} lines matched."

    if watch
      watch_mode(filepath, filter, regex_pattern, levels, since_sec, context, no_color)
    end
  end

  def watch_mode(filepath, filter, regex_pattern, levels, since_sec, context, no_color)
    puts "\n👁️  Watching for new lines (press Ctrl+C to stop)..."
    last_pos = File.size(filepath)
    loop do
      sleep 1
      next unless File.exist?(filepath)
      new_size = File.size(filepath)
      next if new_size <= last_pos
      File.open(filepath, 'r') do |f|
        f.seek(last_pos)
        f.each_line do |line|
          line.chomp!
          entry = parse_log_line(line)
          if entry[:timestamp] && entry[:timestamp].to_time < Time.now - since_sec
            next
          end
          if !levels.empty? && !levels.include?(entry[:level])
            next
          end
          if filter && !line.downcase.include?(filter.downcase)
            next
          end
          if regex_pattern && !line.match?(Regexp.new(regex_pattern))
            next
          end
          line = highlight(line, filter, regex_pattern) unless no_color
          puts "  #{line}"
        end
        last_pos = f.pos
      end
    end
  rescue Interrupt
    puts "\n👋 Watching stopped."
  end

  def show_stats(filepath)
    counts = { 'ERROR' => 0, 'WARN' => 0, 'INFO' => 0, 'DEBUG' => 0, 'OTHER' => 0 }
    File.readlines(filepath).each do |line|
      entry = parse_log_line(line)
      if entry[:level] && counts.key?(entry[:level])
        counts[entry[:level]] += 1
      elsif !line.strip.empty?
        counts['OTHER'] += 1
      end
    end
    puts "\n📊 Log Statistics:"
    ['ERROR', 'WARN', 'INFO', 'DEBUG', 'OTHER'].each do |level|
      color = LEVEL_COLORS[level] || :white
      puts "  #{COLORS[color]}#{level.rjust(5)}#{COLORS[:reset]}: #{counts[level]}"
    end
  end
end

options = {}
OptionParser.new do |opts|
  opts.banner = "Usage: log_filter.rb <file> [options]"
  opts.on("--filter PATTERN", "Filter by keyword") { |v| options[:filter] = v }
  opts.on("--regex PATTERN", "Filter by regex") { |v| options[:regex] = v }
  opts.on("--level LEVELS", "Filter by levels (comma-separated)") { |v| options[:levels] = v }
  opts.on("--since TIME", "Time range (e.g., 5m, 1h, 2d)") { |v| options[:since] = v }
  opts.on("--context N", Integer, "Context lines") { |v| options[:context] = v }
  opts.on("--watch", "Watch for new lines") { options[:watch] = true }
  opts.on("--stats", "Show statistics") { options[:stats] = true }
  opts.on("--no-color", "Disable color") { options[:no_color] = true }
  opts.on("--save", "Save settings") { options[:save] = true }
end.parse!

filepath = ARGV.shift
unless filepath
  puts "Usage: log_filter.rb <file> [options]"
  exit 1
end

app = LogFilter.new

if options[:save]
  levels = options[:levels] ? options[:levels].split(',').map(&:strip) : []
  app.config['filters'] = [options[:filter]] if options[:filter]
  app.config['levels'] = levels
  app.config['since'] = options[:since] || '1h'
  app.save_config
  puts "✅ Filter settings saved."
  exit 0
end

unless options[:filter] || options[:regex] || options[:levels] || options[:since]
  options[:filter] = app.config['filters'][0]
  options[:levels] = app.config['levels'].join(',')
  options[:since] = app.config['since']
end

levels = options[:levels] ? options[:levels].split(',').map(&:strip) : []

app.process_file(filepath,
                 filter: options[:filter],
                 regex_pattern: options[:regex],
                 levels: levels,
                 since: options[:since],
                 context: options[:context] || 0,
                 watch: options[:watch],
                 stats: options[:stats],
                 no_color: options[:no_color])
