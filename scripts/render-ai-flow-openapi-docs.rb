#!/usr/bin/env ruby
# frozen_string_literal: true
require 'yaml'
require 'json'
require 'digest'
Encoding.default_external = Encoding::UTF_8
ROOT = File.expand_path('..', __dir__)
SOURCE = File.join(ROOT, 'docs/openapi/ai-flow-observation-api.yaml')
TARGET = File.join(ROOT, 'docs/openapi/9090-ai-flow-observation.md')
abort 'Usage: render-ai-flow-openapi-docs.rb [--check]' unless ARGV.empty? || ARGV == ['--check']
document = YAML.safe_load(File.read(SOURCE), permitted_classes: [], aliases: false)
def cell(value)
  value.to_s.gsub('|', '\\|').gsub("\n", '<br>')
end
lines = ["# #{document.fetch('info').fetch('title')}", '', "版本：#{document.dig('info', 'version')}；來源 SHA256：`#{Digest::SHA256.file(SOURCE).hexdigest}`", '', document.dig('info', 'description'), '']
document.fetch('paths').each do |path, methods|
  methods.each do |method, operation|
    lines.concat(["## #{method.upcase} #{path}", '', operation.fetch('summary'), '', operation.fetch('description'), '', '成功／失敗回應：', '', '| HTTP | 用途 |', '| --- | --- |'])
    operation.fetch('responses').each { |status, response| lines << "| #{status} | #{cell(response.fetch('description'))} |" }
    lines.concat(['', '輸入範例（只供隔離測試，不可回填為真執行）：', '', '```json', JSON.pretty_generate(operation.dig('requestBody', 'content', 'application/json', 'example')), '```', ''])
  end
end
lines.concat(['## 憑證', '', document.dig('components', 'securitySchemes', 'AiFlowToken', 'description'), ''])
document.dig('components', 'schemas').each do |name, schema|
  lines.concat(["## #{name}", '', schema.fetch('description'), '', '| 欄位 | 型別／限制 | 必填 | 用途 |', '| --- | --- | --- | --- |'])
  schema.fetch('properties').each do |name, field|
    type = field['$ref'] || field['type'].inspect
    constraints = field.reject { |key, _| %w[type description $ref].include?(key) }
    lines << "| #{name} | #{cell(type)} #{cell(constraints.empty? ? '' : JSON.generate(constraints))} | #{schema.fetch('required').include?(name) ? '是' : '否'} | #{cell(field.fetch('description'))} |"
  end
  lines << ''
end
rendered = lines.join("\n") + "\n"
if ARGV == ['--check']
  abort "STALE: #{TARGET}" unless File.file?(TARGET) && File.binread(TARGET) == rendered.b
  puts 'PASS: AI 觀測 OpenAPI Markdown 與來源一致；未寫入任何金融或 SRPP 檔案'
else
  File.write(TARGET, rendered)
  puts "Generated: #{TARGET}"
end
