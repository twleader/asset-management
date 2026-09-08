#!/usr/bin/env bash
# Task 422 static Compose contract. Deliberately parses YAML only; it never starts Docker.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repo_root/docker-compose.yml"

ruby -ryaml -e '
  compose = YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)
  condition = compose.dig("services", "business-services", "depends_on",
                          "fubon-broker-service", "condition")
  unless condition == "service_healthy"
    warn "FAIL: business-services must wait for fubon-broker-service service_healthy; got #{condition.inspect}"
    exit 1
  end
  puts "PASS: business-services waits for fubon-broker-service service_healthy"
' "$compose_file"
