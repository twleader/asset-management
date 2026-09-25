#!/usr/bin/env ruby
# frozen_string_literal: true

# Deterministic renderer for the two human-readable mirrors of the versioned 9090
# OpenAPI contract.  The YAML is the only content source: do not hand-edit either
# generated Markdown target.

require 'yaml'

ROOT = File.expand_path('..', __dir__)
OPENAPI = File.join(ROOT, 'docs/openapi/docker-external-api.yaml')
REPO_TARGET = File.join(ROOT, 'docs/openapi/9090-api-swagger.md')
SRPP_MIRROR_TARGET = '/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md'
# SRPP 鏡像只在地端同步（寫入與 --check 皆然）；雲端（Claude Code on the web 等遠端環境，
# 以 CLAUDE_CODE_REMOTE=true 辨識）沒有 SRPP 專案，一律略過，只維護本專案鏡像。
CLOUD_SESSION = ENV['CLAUDE_CODE_REMOTE'] == 'true'
TARGETS = (CLOUD_SESSION ? [REPO_TARGET] : [REPO_TARGET, SRPP_MIRROR_TARGET]).freeze
HTTP_METHODS = %w[get put post delete options head patch trace].freeze
WEAK_DESCRIPTION = /\A(?:`?[A-Za-z0-9_.-]+`?\s*)?(?:資料|欄位|物件|陣列|schema)\.?\z/i
LEGACY_DESCRIPTION_FALLBACK = '型別、可空性與限制以本 OpenAPI schema 為準。'

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

def validate_document!(document)
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
  assert!(reachable.length == 92, "預期 92 個 reachable component schema，實際為 #{reachable.length}")
  reachable.each do |name|
    validate_schema_node!(schemas.fetch(name), "components.schemas.#{name}")
  end

  walk(document) { |node| resolve_ref(document, node['$ref']) if node.is_a?(Hash) && node['$ref'] }
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
  lines << '本文件由 `docs/openapi/docker-external-api.yaml` 自動產生；請勿手動修改。兩個 Markdown 位置必須位元組一致。'
  lines << ''
  lines << '## 概覽'
  lines << ''
  lines << '| 項目 | 值 |'
  lines << '| --- | --- |'
  lines << "| OpenAPI | `#{document['openapi']}` |"
  lines << "| 契約版本 | `#{document.dig('info', 'version')}` |"
  lines << "| 對外路徑 | #{operations.length} 條：#{get_count} 個 `GET`、#{post_count} 個 `POST` |"
  lines << "| Servers | #{document.fetch('servers').map { |server| "`#{server['url']}`" }.join('、')} |"
  lines << '| 應用層 security | `[]`；實際邊界為 loopback 或獲准 Tailscale identity，非公網服務。 |'
  lines << ''
  lines << document.dig('info', 'description').to_s.strip
  lines << ''
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
    parameters = operation.fetch('parameters', [])
    unless parameters.empty?
      lines << '#### Query 參數'
      lines << ''
      lines << '| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |'
      lines << '| --- | --- | --- | --- | --- |'
      parameters.each do |parameter|
        schema = parameter.fetch('schema')
        limits = []
        limits << "enum: #{enum_label(schema)}" unless enum_label(schema).empty?
        limits << "pattern: `#{schema['pattern']}`" if schema['pattern']
        limits << "example: `#{parameter['example']}`"
        lines << "| `#{parameter['name']}` | #{parameter['required'] ? '是' : '否'} | `#{type_label(schema)}` | #{markdown(limits.join('<br>'))} | #{markdown(parameter['description'])} |"
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
  end
  lines << '## Schema 欄位'
  lines << ''
  schemas.each do |name, schema|
    next unless reachable_schemas.include?(name)

    lines << "### `#{name}`"
    lines << ''
    lines << description_for(name, schema)
    lines << ''
    if schema['properties']
      required = Array(schema['required'])
      lines << '| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |'
      lines << '| --- | --- | --- | --- | --- | --- |'
      schema['properties'].each do |property, definition|
        restrictions = []
        restrictions << "enum: #{enum_label(definition)}" unless enum_label(definition).empty?
        %w[pattern minimum maximum minItems maxItems exclusiveMinimum].each do |key|
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

unless ARGV.empty? || ARGV == ['--check'] || ARGV == ['--stdout']
  warn 'usage: ruby scripts/render-9090-openapi-docs.rb [--check|--stdout]'
  exit 2
end

document = YAML.safe_load(File.read(OPENAPI), aliases: false)
validate_document!(document)
rendered = render(document)

if ARGV == ['--stdout']
  print rendered
elsif ARGV == ['--check']
  stale = TARGETS.reject { |target| File.file?(target) && File.binread(target) == rendered.b }
  if stale.empty?
    puts 'PASS: 9090 OpenAPI Markdown mirrors are byte-identical and current'
    puts 'SKIP: 雲端環境（CLAUDE_CODE_REMOTE=true）不檢查 SRPP 鏡像，地端才同步' if CLOUD_SESSION
  else
    warn "generated OpenAPI Markdown is stale: #{stale.join(', ')}"
    exit 1
  end
else
  TARGETS.each { |target| File.binwrite(target, rendered) }
  puts "rendered #{TARGETS.length} byte-identical 9090 OpenAPI Markdown mirrors"
  puts 'SKIP: 雲端環境（CLAUDE_CODE_REMOTE=true）不覆寫 SRPP 鏡像，地端才同步' if CLOUD_SESSION
end
