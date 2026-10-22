# frozen_string_literal: true

# 16.10.26af AC11: the built gem's executables are exactly `lingara`, and
# its files hold every asset under lib/lingara/cli/assets/ (D8), byte for
# byte, so an installed gem never reads a path outside itself.
#
#   ruby check_gem.rb <lingara-cli.gem> <assets dir>
require "rubygems/package"
require "digest"

gem_path, assets = ARGV
abort "usage: check_gem.rb <gem> <assets dir>" unless gem_path && assets

package = Gem::Package.new(gem_path)
spec = package.spec
problems = []
problems << "executables are #{spec.executables.inspect}, not [\"lingara\"]" unless spec.executables == ["lingara"]

packed = {}
package.contents.each { |path| packed[path] = true }
data = {}
File.open(gem_path, "rb") do |io|
  Gem::Package::TarReader.new(io).each do |entry|
    next unless entry.full_name == "data.tar.gz"

    Zlib::GzipReader.wrap(entry) do |gz|
      Gem::Package::TarReader.new(gz).each { |file| data[file.full_name] = Digest::SHA256.hexdigest(file.read.to_s) if file.file? }
    end
  end
end

prefix = "lib/lingara/cli/assets/"
Dir.glob("**/*", File::FNM_DOTMATCH, base: assets).each do |rel|
  local = File.join(assets, rel)
  next unless File.file?(local)

  path = "#{prefix}#{rel}"
  if !spec.files.include?(path) || !packed[path]
    problems << "#{path} is not in the gem"
  elsif data[path] != Digest::SHA256.file(local).hexdigest
    problems << "#{path} differs from #{local}"
  end
end

abort(problems.map { |p| "✗ #{p}" }.join("\n")) unless problems.empty?
count = spec.files.count { |f| f.start_with?(prefix) }
puts "✓ #{File.basename(gem_path)}: executables [\"lingara\"], #{count} assets, each byte for byte"
