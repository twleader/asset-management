#!/usr/bin/env ruby
# frozen_string_literal: true

# Task 484.12(b)：BFF `OpenApiRouteCatalog` 送出的每一組 (operation_key, 標籤) 都必須逐字存在於 backend
# `ApiErrorLogOperationCatalog`（`require(...)` 以 source+key+apiName 比對）與 `api_error_log_operation`
# 種子列（`api_error_log` 對 (source, operation_key, operation_label) 有外鍵）。只比 key 會放過標籤錯誤，
# 讓該路由的 5xx 被 ingest 靜默丟棄，所以這裡一律比對 (key, 標籤) 整組。

Encoding.default_external = Encoding::UTF_8
Encoding.default_internal = Encoding::UTF_8

ROOT = File.expand_path('../..', __dir__)
BFF_CATALOG = File.join(ROOT, 'bff/src/main/java/com/steven/assets/bff/apierrorlogs/OpenApiRouteCatalog.java')
BACKEND_CATALOG = File.join(ROOT, 'backend/src/main/java/com/steven/assets/apierrorlog/ApiErrorLogOperationCatalog.java')
CHANGELOG_MASTER = File.join(ROOT, 'backend/src/main/resources/db/changelog/db.changelog-master.yaml')
RESOURCES = File.join(ROOT, 'backend/src/main/resources')

def assert!(condition, message)
  return if condition

  warn "FAIL: #{message}"
  exit 1
end

bff = File.read(BFF_CATALOG).scan(/Map\.entry\("([A-Z]+ [^"]+)",\s*new Operation\("([A-Z0-9_]+)",\s*"([^"]+)"\)\)/)
                            .map { |route, key, label| {route: route, key: key, label: label} }
assert!(!bff.empty?, 'BFF OpenApiRouteCatalog 解析不到任何 route')
assert!(bff.map { |op| op[:key] }.uniq.length == bff.length, 'BFF OpenApiRouteCatalog operation key 重複')

backend = File.read(BACKEND_CATALOG)
              .scan(/new Operation\((OPEN_API|FUBON_API),\s*"([A-Z0-9_]+)",\s*"([^"]+)",\s*"([^"]*)",\s*(\d+)\)/)
              .map { |source, key, label, url, order| {source: source, key: key, label: label, url: url, order: order.to_i} }
assert!(!backend.empty?, 'backend ApiErrorLogOperationCatalog 解析不到任何 operation')
backend_open = backend.select { |op| op[:source] == 'OPEN_API' }.to_h { |op| [op[:key], op] }

# master changelog 依序 include；`ON CONFLICT DO NOTHING` 讓先插入的列勝出，所以同 key 取第一次出現的值。
seeds = {}
File.read(CHANGELOG_MASTER).scan(%r{file:\s*(db/changelog/changes/\S+\.sql)}).flatten.each do |relative|
  sql = File.read(File.join(RESOURCES, relative))
  next unless sql.include?('api_error_log_operation')

  sql.scan(/\(\s*'(OPEN_API|FUBON_API)'\s*,\s*'([A-Z0-9_]+)'\s*,\s*'([^']+)'\s*,\s*(\d+)\s*\)/) do |source, key, label, order|
    seeds[[source, key]] ||= {label: label, order: order.to_i}
  end
end

bff.each do |op|
  catalog = backend_open[op[:key]]
  assert!(catalog, "backend ApiErrorLogOperationCatalog 缺 OPEN_API #{op[:key]}（BFF route #{op[:route]}）")
  assert!(catalog[:label] == op[:label],
          "#{op[:key]} 標籤不一致：BFF「#{op[:label]}」對 backend「#{catalog[:label]}」")
  assert!(catalog[:url] == op[:route], "#{op[:key]} 路由不一致：BFF #{op[:route]} 對 backend #{catalog[:url]}")
  seed = seeds[['OPEN_API', op[:key]]]
  assert!(seed, "api_error_log_operation 種子缺 OPEN_API #{op[:key]}")
  assert!(seed[:label] == op[:label] && seed[:order] == catalog[:order],
          "#{op[:key]} 種子列（#{seed[:label]}, #{seed[:order]}）與 catalog（#{catalog[:label]}, #{catalog[:order]}）不一致")
end

puts "PASS: BFF OpenApiRouteCatalog #{bff.length} 組 (operation_key, 標籤) 皆逐字存在於 backend 目錄與 api_error_log_operation 種子"
