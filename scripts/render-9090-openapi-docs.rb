#!/usr/bin/env ruby
# frozen_string_literal: true

# Deterministic renderer for the two asset-management Markdown mirrors and, only when an
# explicit managed worktree is supplied, one byte-identical SRPP mirror.  The YAML is the
# only content source: do not hand-edit any generated Markdown target.

require 'fileutils'
require 'yaml'
require 'json'
require 'open3'
require 'pathname'

ROOT = File.expand_path('..', __dir__)
OPENAPI = File.join(ROOT, 'docs/openapi/docker-external-api.yaml')
OBSERVATION_OPENAPI = File.join(ROOT, 'docs/openapi/ai-flow-observation-api.yaml')
REPO_TARGET = File.join(ROOT, 'docs/openapi/9090-api-swagger.md')
# Task 484.9：business-services 隨 jar 發布的已發布 Swagger 身分來源（PublishedSwaggerIdentity 讀它算
# SHA-256）；business 的 Docker build context 只有 ./backend，所以必須在 backend 內保留一份位元組一致的副本。
BACKEND_RESOURCE_TARGET = File.join(ROOT, 'backend/src/main/resources/srpp/9090-api-swagger.md')
ASSET_TARGETS = [REPO_TARGET, BACKEND_RESOURCE_TARGET].freeze
SRPP_SWAGGER_MIRROR = 'docs/9090 Port API Swagger.md'
# sync 後的 metadata 仍可能尚未 commit；--check 只可接續這四個固定產物，
# 不得把任何其他 dirty path 當成安全的 feature worktree。
SRPP_SYNC_GENERATED_PATHS = [
  SRPP_SWAGGER_MIRROR,
  'assumptions/srpp_report_calculations_schema.json',
  'assumptions/daily_runtime_contract.json',
  'assumptions/policy_manifest.json'
].freeze
HTTP_METHODS = %w[get put post delete options head patch trace].freeze
WEAK_DESCRIPTION = /\A(?:`?[A-Za-z0-9_.-]+`?\s*)?(?:資料|欄位|物件|陣列|schema)\.?\z/i
LEGACY_DESCRIPTION_FALLBACK = '型別、可空性與限制以本 OpenAPI schema 為準。'
TASK497_SCHEMA_PREFIX = /\Acomponents\.schemas\.(?:SrppProjection|SrppOrderPlan|SrppSymbolRuleFact)/

def assert!(condition, message)
  raise message unless condition
end

def resolve_ref(document, ref)
  assert!(ref.is_a?(String) && ref.start_with?('#/'), "只允許本文件 $ref：#{ref.inspect}")
  ref.delete_prefix('#/').split('/').reduce(document) do |node, token|
    key = token.gsub('~1', '/').gsub('~0', '~')
    assert!(node.is_a?(Hash) && node.key?(key), "無法解析 #{ref}")
    node[key]
  end
end

def nullable?(schema)
  return true if Array(schema['type']).include?('null')
  return true if Array(schema['anyOf']).any? { |part| part.is_a?(Hash) && part['type'] == 'null' }

  false
end

def type_label(schema)
  return schema['$ref'].delete_prefix('#/components/schemas/') if schema['$ref']

  if schema['anyOf']
    return schema['anyOf'].map { |part| type_label(part) }.join(' | ')
  end

  type = schema['type']
  label = type.is_a?(Array) ? type.join(' | ') : (type || 'schema')
  label += " (#{schema['format']})" if schema['format']
  if type == 'array' || (type.is_a?(Array) && type.include?('array'))
    label += " of #{type_label(schema.fetch('items', {}))}"
  end
  label
end

def enum_label(schema)
  values = schema['enum'] || (schema['const'] ? [schema['const']] : nil)
  values ? values.map { |value| value.nil? ? 'null' : "`#{value}`" }.join(', ') : ''
end

def markdown(value)
  value.to_s.gsub('|', '\\|').gsub("\n", '<br>')
end

def description_for(name, schema)
  schema.fetch('description') do
    raise "#{name} 缺 YAML description；renderer 不提供 fallback"
  end.to_s.strip
end

def response_schema_label(response)
  content = response['content'] || {}
  content.map do |media_type, media|
    schema = media['schema'] || {}
    "#{media_type}: #{type_label(schema)}"
  end.join('; ')
end

def walk(value, &block)
  yield value
  case value
  when Hash then value.each_value { |child| walk(child, &block) }
  when Array then value.each { |child| walk(child, &block) }
  end
end

def concrete_description!(node, path, kind)
  description = node.is_a?(Hash) ? node['description'].to_s.strip : ''
  assert!(!description.empty?, "#{kind} #{path} 缺 YAML description")
  assert!(description != LEGACY_DESCRIPTION_FALLBACK,
          "#{kind} #{path} 不可使用 renderer legacy fallback")
  assert!(!description.match?(WEAK_DESCRIPTION),
          "#{kind} #{path} description 不可只是名稱重述或泛稱：#{description}")
  description
end

def assert_enum_values_documented!(node, description, path)
  values = node['enum'] || (node.key?('const') ? [node['const']] : [])
  values.each do |value|
    literal = value.nil? ? 'null' : value.to_s
    assert!(description.include?(literal),
            "enum #{path} 的 description 必須解釋值 #{literal.inspect}")
  end
end

def schema_references(value, references = [])
  case value
  when Hash
    ref = value['$ref']
    references << ref if ref.is_a?(String) && ref.start_with?('#/components/schemas/')
    value.each_value { |child| schema_references(child, references) }
  when Array
    value.each { |child| schema_references(child, references) }
  end
  references
end

def reachable_schema_names(document)
  names = {}
  pending = schema_references(document.fetch('paths')).map { |ref| ref.delete_prefix('#/components/schemas/') }
  schemas = document.dig('components', 'schemas')
  until pending.empty?
    name = pending.shift
    next if names[name]

    schema = schemas.fetch(name) { raise "paths 引用了不存在的 schema #{name}" }
    names[name] = true
    pending.concat(schema_references(schema).map { |ref| ref.delete_prefix('#/components/schemas/') })
  end
  names.keys
end

def validate_schema_node!(node, path, kind: 'schema')
  return unless node.is_a?(Hash)
  return if path.match?(TASK497_SCHEMA_PREFIX) && !node.key?('description')

  description = concrete_description!(node, path, kind)
  assert_enum_values_documented!(node, description, path)

  node.fetch('properties', {}).each do |property, definition|
    validate_schema_node!(definition, "#{path}.#{property}", kind: 'property')
  end
  validate_schema_node!(node['items'], "#{path}[]", kind: 'array item') if node['items'].is_a?(Hash)
  if node['additionalProperties'].is_a?(Hash)
    validate_schema_node!(node['additionalProperties'], "#{path}{*}", kind: 'map value')
  end
  %w[allOf anyOf oneOf].each do |composition|
    Array(node[composition]).each_with_index do |variant, index|
      validate_schema_node!(variant, "#{path}.#{composition}[#{index}]", kind: 'variant')
    end
  end
end

def validate_document!(document, expected_schemas: 231)
  assert!(document.fetch('openapi').to_s.start_with?('3.'), 'OpenAPI 必須為 3.x')
  assert!(document['security'] == [], 'global security 必須明確為空陣列')
  schemas = document.dig('components', 'schemas')
  assert!(schemas.is_a?(Hash), '缺 components.schemas')

  paths = document.fetch('paths')
  paths.each do |path, path_item|
    path_item.each do |method, operation|
      next unless HTTP_METHODS.include?(method)

      %w[operationId summary description].each do |field|
        assert!(!operation[field].to_s.strip.empty?, "#{method.upcase} #{path} 缺 #{field}")
      end
      concrete_description!(operation, "#{method.upcase} #{path}", 'operation')
      operation.fetch('parameters', []).each do |parameter|
        %w[name in required description schema example].each do |field|
          assert!(parameter.key?(field), "#{method.upcase} #{path} parameter 缺 #{field}")
        end
        parameter_description = concrete_description!(parameter,
                                                       "#{method.upcase} #{path}.#{parameter['name']}",
                                                       'parameter')
        assert_enum_values_documented!(parameter.fetch('schema'), parameter_description,
                                       "#{method.upcase} #{path}.#{parameter['name']}")
      end
      operation.fetch('responses').each do |status, raw_response|
        response = raw_response['$ref'] ? resolve_ref(document, raw_response['$ref']) : raw_response
        concrete_description!(response, "#{method.upcase} #{path}.responses.#{status}", 'response')
      end
    end
  end

  reachable = reachable_schema_names(document)
  assert!(reachable.length == expected_schemas, "預期 #{expected_schemas} 個 reachable component schema，實際為 #{reachable.length}")
  reachable.each do |name|
    validate_schema_node!(schemas.fetch(name), "components.schemas.#{name}")
  end

  walk(document) { |node| resolve_ref(document, node['$ref']) if node.is_a?(Hash) && node['$ref'] }
end

# Resolve every source within its own namespace before combining it; a collision is never overwritten.
def unified_document(financial, observation)
  [financial, observation].each do |source|
    walk(source) { |node| resolve_ref(source, node['$ref']) if node.is_a?(Hash) && node['$ref'] }
  end
  result = Marshal.load(Marshal.dump(financial))
  overlaps = financial.fetch('paths').keys & observation.fetch('paths').keys
  assert!(overlaps.empty?, "path collision: #{overlaps.join(', ')}")
  ids = [financial, observation].flat_map do |source|
    source.fetch('paths').values.flat_map do |item|
      item.map { |method, operation| operation['operationId'] if HTTP_METHODS.include?(method) }.compact
    end
  end
  assert!(ids.uniq.length == ids.length, 'operationId collision')
  observation.fetch('components', {}).each do |kind, members|
    existing = result['components'][kind] ||= {}
    duplicate = existing.keys & members.keys
    assert!(duplicate.empty?, "component collision: #{kind}/#{duplicate.join(', ')}")
    existing.merge!(members)
  end
  result['paths'].merge!(observation.fetch('paths'))
  result['x-observation-version'] = observation.dig('info', 'version')
  result
end

def json_block(value)
  ['```json', JSON.pretty_generate(value), '```', '']
end

def render(document)
  paths = document.fetch('paths')
  operations = []
  paths.each do |path, path_item|
    path_item.each do |method, operation|
      operations << [path, method.upcase, operation] if HTTP_METHODS.include?(method)
    end
  end
  schemas = document.dig('components', 'schemas')
  reachable_schemas = reachable_schema_names(document)
  get_count = operations.count { |_, method, _| method == 'GET' }
  post_count = operations.count { |_, method, _| method == 'POST' }

  lines = []
  lines << '# 9090 Port API Swagger'
  lines << ''
  lines << '本文件由 `docs/openapi/docker-external-api.yaml` 與 `docs/openapi/ai-flow-observation-api.yaml` 自動產生；請勿手動修改。三個 Markdown 位置必須位元組一致。'
  lines << ''
  lines << '## 概覽'
  lines << ''
  lines << '| 項目 | 值 |'
  lines << '| --- | --- |'
  lines << "| OpenAPI | `#{document['openapi']}` |"
  lines << "| 契約版本 | `#{document.dig('info', 'version')}` |"
  lines << "| AI 流程觀測契約版本 | `#{document['x-observation-version']}` |"
  lines << "| 對外路徑 | #{operations.length} 條：#{get_count} 個 `GET`、#{post_count} 個 `POST` |"
  lines << "| Servers | #{document.fetch('servers').map { |server| "`#{server['url']}`" }.join('、')} |"
  lines << '| 應用層 security | `[]`；實際邊界為 loopback 或獲准 Tailscale identity，非公網服務。 |'
  lines << ''
  lines << '此統一文件涵蓋全部 9090 API；PublishedSwaggerIdentity 為完整文件 bytes 的 SHA-256。兩份 YAML 分別保留金融及觀測協定版本，歷史 receipts 維持原身分。'
  lines << ''
  lines << '### 金融來源說明'
  lines << ''
  lines << '以下引用金融 YAML 的 26 個 API 說明；引文中的「本文件」指金融 YAML。此統一 Swagger 的完整集合為 27 個 API，操作層憑證以各路由的授權章節為準。'
  lines << ''
  lines.concat(document.dig('info', 'description').to_s.strip.split("\n").map { |line| "> #{line}".rstrip })
  lines << ''
  lines << '## 服務憑證'
  lines << ''
  document.dig('components', 'securitySchemes').to_h.each do |name, scheme|
    lines << "### `#{name}`"
    lines << ''
    lines << scheme.fetch('description', '').to_s
    lines << ''
    lines.concat(json_block(scheme))
  end
  lines << '## 路由總覽'
  lines << ''
  lines << '| # | Method | Path | operationId | 摘要 | 成功回應 |'
  lines << '| ---: | --- | --- | --- | --- | --- |'
  operations.each_with_index do |(path, method, operation), index|
    successes = operation.fetch('responses').select { |status, _| status.to_s.start_with?('2') }
                         .map { |status, raw| response = raw['$ref'] ? resolve_ref(document, raw['$ref']) : raw; "#{status} #{response_schema_label(response)}" }
    lines << "| #{index + 1} | `#{method}` | `#{path}` | `#{operation['operationId']}` | #{markdown(operation['summary'])} | #{markdown(successes.join('<br>'))} |"
  end
  lines << ''
  lines << '## 路由詳情'
  lines << ''
  operations.each_with_index do |(path, method, operation), index|
    lines << "### #{index + 1}. `#{method} #{path}`"
    lines << ''
    lines << operation['description'].to_s.strip
    lines << ''
    lines << '#### 授權'
    lines << ''
    lines.concat(json_block(operation.fetch('security', document.fetch('security'))))
    if operation['requestBody']
      body = operation['requestBody']['$ref'] ? resolve_ref(document, operation['requestBody']['$ref']) : operation['requestBody']
      lines << '#### Request body'
      lines << ''
      lines << "必填：#{body['required'] ? '是' : '否'}。#{body['description']}"
      lines << ''
      lines.concat(json_block(body))
    end
    parameters = operation.fetch('parameters', [])
    unless parameters.empty?
      lines << '#### 參數'
      lines << ''
      lines << '| 名稱 | 位置 | 必填 | 型別 | 限制／範例 | 說明 |'
      lines << '| --- | --- | --- | --- | --- | --- |'
      parameters.each do |parameter|
        schema = parameter.fetch('schema')
        limits = []
        limits << "enum: #{enum_label(schema)}" unless enum_label(schema).empty?
        limits << "pattern: `#{schema['pattern']}`" if schema['pattern']
        limits << "example: `#{parameter['example']}`"
        lines << "| `#{parameter['name']}` | `#{parameter['in']}` | #{parameter['required'] ? '是' : '否'} | `#{type_label(schema)}` | #{markdown(limits.join('<br>'))} | #{markdown(parameter['description'])} |"
      end
      lines << ''
    end
    lines << '#### Responses'
    lines << ''
    lines << '| Status | Content／schema | 說明 |'
    lines << '| --- | --- | --- |'
    operation.fetch('responses').each do |status, raw_response|
      response = raw_response['$ref'] ? resolve_ref(document, raw_response['$ref']) : raw_response
      lines << "| `#{status}` | #{markdown(response_schema_label(response))} | #{markdown(response['description'])} |"
    end
    lines << ''
    lines << '完整回應契約（包含 headers、media types 與 examples）：'
    lines << ''
    responses = operation.fetch('responses').transform_values { |raw| raw['$ref'] ? resolve_ref(document, raw['$ref']) : raw }
    lines.concat(json_block(responses))
  end
  lines << '## Schema 欄位'
  lines << ''
  schemas.each do |name, schema|
    next unless reachable_schemas.include?(name)

    lines << "### `#{name}`"
    lines << ''
    lines << description_for(name, schema)
    lines << ''
    lines << '完整 schema（含閉合、required、oneOf／條件與巢狀限制）：'
    lines << ''
    lines.concat(json_block(schema))
    if schema['properties']
      required = Array(schema['required'])
      lines << '| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |'
      lines << '| --- | --- | --- | --- | --- | --- |'
      schema['properties'].each do |property, definition|
        restrictions = []
        restrictions << "enum: #{enum_label(definition)}" unless enum_label(definition).empty?
        %w[format pattern minimum maximum minItems maxItems minLength maxLength multipleOf uniqueItems exclusiveMinimum exclusiveMaximum].each do |key|
          restrictions << "#{key}: #{definition[key]}" if definition.key?(key)
        end
        if definition['type'] == 'array'
          item = definition.fetch('items')
          restrictions << "items: #{type_label(item)}"
          restrictions << "items 說明: #{description_for('item', item)}"
        end
        lines << "| `#{property}` | #{required.include?(property) ? '是' : '否'} | `#{type_label(definition)}` | #{nullable?(definition) ? '是' : '否'} | #{markdown(restrictions.join('<br>'))} | #{markdown(description_for(property, definition))} |"
      end
      lines << ''
    elsif schema['type'] == 'array'
      item = schema.fetch('items')
      lines << "陣列項目：`#{type_label(item)}`。#{description_for('item', item)}"
      lines << ''
    else
      enum = enum_label(schema)
      lines << "型別：`#{type_label(schema)}`#{enum.empty? ? '' : "；enum: #{enum}"}。"
      lines << ''
    end
  end
  lines.join("\n").rstrip + "\n"
end

# Task 504：跨 repo mirror 只能明確指定一個乾淨的 managed feature worktree。
# 這段在產生或寫任何 target 前執行；預設／--asset-only 路徑不呼叫它，也不讀 SRPP。
def shell!(root, *args)
  output, status = Open3.capture2e('git', '-C', root, *args)
  assert!(status.success?, "SRPP worktree git 檢查失敗")
  output
end

def managed_srpp_worktree!(raw_root, require_clean: true, allowed_dirty_paths: [])
  assert!(raw_root.is_a?(String) && Pathname.new(raw_root).absolute?, '--srpp-root 必須是明示絕對路徑')
  expanded = File.expand_path(raw_root)
  assert!(!File.symlink?(expanded), '--srpp-root 不可為 symlink')
  canonical = File.realpath(expanded)
  assert!(canonical == expanded, '--srpp-root 路徑不可穿越 symlink')
  # 主 checkout 的 .git 是 directory；git worktree 建立的受管理 checkout 則為 .git file。
  assert!(File.file?(File.join(canonical, '.git')), '--srpp-root 必須是 managed Git worktree，不可為 primary checkout')
  top = shell!(canonical, 'rev-parse', '--show-toplevel').strip
  assert!(File.realpath(top) == canonical, '--srpp-root 必須是 worktree root')
  listed = shell!(canonical, 'worktree', 'list', '--porcelain').lines.grep(/^worktree /)
                  .map { |line| File.realpath(line.delete_prefix('worktree ').strip) }
  assert!(listed.include?(canonical), '--srpp-root 不在 Git managed worktree 清單')
  branch, branch_status = Open3.capture2e('git', '-C', canonical, 'symbolic-ref', '--quiet', '--short', 'HEAD')
  assert!(branch_status.success?, '--srpp-root 不可為 main 或 detached HEAD')
  branch = branch.strip
  assert!(!branch.empty? && branch != 'main', '--srpp-root 不可為 main 或 detached HEAD')
  status = shell!(canonical, 'status', '--porcelain=v1', '-z')
  if !status.empty? && require_clean
    entries = status.split("\0").reject(&:empty?)
    changed = entries.map do |entry|
      assert!(entry.start_with?(' M '), '--srpp-root 只能保留 renderer/sync 的未 staged 產物')
      entry.delete_prefix(' M ')
    end
    assert!(!allowed_dirty_paths.empty? && changed.all? { |path| allowed_dirty_paths.include?(path) },
            '--srpp-root 必須乾淨或僅含 renderer/sync 固定產物')
  end
  canonical
end

def parse_renderer_args(args)
  options = { check: false, stdout: false, asset_only: false, srpp_root: nil }
  pending = args.dup
  until pending.empty?
    token = pending.shift
    case token
    when '--check' then options[:check] = true
    when '--stdout' then options[:stdout] = true
    when '--asset-only' then options[:asset_only] = true
    when '--srpp-root'
      value = pending.shift
      raise '--srpp-root 缺少路徑' if value.nil?
      raise '--srpp-root 不可重複' if options[:srpp_root]
      options[:srpp_root] = value
    else
      raise "未知參數：#{token}"
    end
  end
  raise '--stdout 不可與其他模式併用' if options[:stdout] && (options[:check] || options[:asset_only] || options[:srpp_root])
  raise '--asset-only 與 --srpp-root 互斥' if options[:asset_only] && options[:srpp_root]
  options
end

if __FILE__ == $PROGRAM_NAME
begin
options = parse_renderer_args(ARGV)
rescue RuntimeError => failure
  warn "usage: ruby scripts/render-9090-openapi-docs.rb [--asset-only] [--check] [--srpp-root ABSOLUTE_WORKTREE] | [--stdout]"
  warn failure.message
  exit 2
end

# rejection must occur before asset targets are changed, so every failure path is zero-write.
srpp_root = options[:srpp_root] ? managed_srpp_worktree!(
  options[:srpp_root], require_clean: true,
  allowed_dirty_paths: options[:check] ? SRPP_SYNC_GENERATED_PATHS : []
) : nil

document = YAML.safe_load(File.read(OPENAPI), aliases: false)
validate_document!(document)
observation = YAML.safe_load(File.read(OBSERVATION_OPENAPI), aliases: false)
document = unified_document(document, observation)
validate_document!(document, expected_schemas: 243)
rendered = render(document)
targets = ASSET_TARGETS + (srpp_root ? [File.join(srpp_root, 'docs/9090 Port API Swagger.md')] : [])

if options[:stdout]
  print rendered
elsif options[:check]
  stale = targets.reject { |target| File.file?(target) && File.binread(target) == rendered.b }
  if stale.empty?
    puts 'PASS: 9090 OpenAPI Markdown mirrors are byte-identical and current'
  else
    warn "generated OpenAPI Markdown is stale: #{stale.join(', ')}"
    exit 1
  end
else
  # 只為 backend classpath resource 建立目錄；指定 SRPP worktree 的父目錄不存在時必須明確失敗。
  FileUtils.mkdir_p(File.dirname(BACKEND_RESOURCE_TARGET))
  targets.each { |target| File.binwrite(target, rendered) }
  puts "rendered #{targets.length} byte-identical 9090 OpenAPI Markdown mirrors"
  puts 'SKIP: 未指定 --srpp-root，預設只處理 asset-management，不讀寫 SRPP' unless srpp_root
end
end
