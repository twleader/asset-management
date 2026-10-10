#!/usr/bin/env ruby
# frozen_string_literal: true

require 'fileutils'
require 'open3'
require 'tmpdir'

Encoding.default_external = Encoding::UTF_8
ROOT = File.expand_path('../..', __dir__)
MIGRATION = 'backend/src/main/resources/db/changelog/changes/v1.149.0-srpp-decision-engine.sql'
checker = File.read(File.join(ROOT, 'scripts/spec-check.sh'))
function = checker[/# BEGIN migration-create-reentrancy-check\n(.*?)# END migration-create-reentrancy-check/m, 1]
raise 'Missing production checker function' unless function
raise 'Production B3 does not invoke the checked function' unless checker.include?('check_migration_create_reentrancy "$f"')
original = File.binread(File.join(ROOT, MIGRATION))

# Execute the actual production B3 function with isolated SQL files. Do not
# invoke unrelated schema/runtime/OpenAPI checks or touch deployed migrations.
shell = <<~BASH
  set -uo pipefail
  BLOCKS=0
  CHECKS=0
  block() { echo "BLOCK: $*"; BLOCKS=$((BLOCKS + 1)); }
  check() { echo "CHECK: $*"; CHECKS=$((CHECKS + 1)); }
  #{function}
  check_migration_create_reentrancy "$1"
  [ "$BLOCKS" -eq 0 ]
BASH

cases = [
  ['original guarded replacement', MIGRATION, original, true],
  ['removed nonempty guard', MIGRATION, original.sub(/DO \$\$ BEGIN.*?END \$\$;\n/m, ''), false],
  ['changed bytes despite IF NOT EXISTS token', MIGRATION, original + "\n-- IF NOT EXISTS\n", false],
  ['changed changeset ID', MIGRATION, original.sub('steven:v1.149.0-', 'steven:v1.150.0-'), false],
  ['renamed original replacement', MIGRATION.sub('v1.149.0-', 'v1.150.0-'), original, false],
  ['ordinary unguarded CREATE', 'backend/src/main/resources/db/changelog/changes/v1.150.0-test.sql', "CREATE TABLE test_table(id int);\n", false],
  ['ordinary idempotent CREATE', 'backend/src/main/resources/db/changelog/changes/v1.150.0-test.sql', "CREATE TABLE IF NOT EXISTS test_table(id int);\n", true]
]

Dir.mktmpdir('spec-check-guarded-replacement-') do |dir|
  cases.each do |name, path, bytes, accepted|
    target = File.join(dir, path)
    FileUtils.mkdir_p(File.dirname(target))
    File.binwrite(target, bytes)
    stdout, stderr, status = Open3.capture3('bash', '-c', shell, 'checker-regression', path, chdir: dir)
    raise "#{name}: unexpected result\n#{stdout}\n#{stderr}" unless status.success? == accepted
    if name == 'original guarded replacement'
      raise 'Exception must disclose non-reentrant status' unless stdout.include?('CHECK:') && stdout.include?('non-reentrant') && stdout.include?('禁止改號')
    elsif !accepted
      raise "#{name}: rejection must remain BLOCK" unless stdout.include?('BLOCK:')
    end
  end
end
puts "PASS: production B3 guarded-replacement checker, #{cases.size} isolated cases"
