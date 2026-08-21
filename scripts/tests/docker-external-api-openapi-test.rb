#!/usr/bin/env ruby
# frozen_string_literal: true

require 'set'
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
NGINX = File.join(ROOT, 'api-gateway/nginx.conf')
OPENAPI = File.join(ROOT, 'docs/openapi/docker-external-api.yaml')
HTTP_METHODS = %w[get put post delete options head patch trace].freeze

MANIFEST = {
  ['GET', '/api/quotes'] => %w[200 502 504],
  ['GET', '/api/quotes/one'] => %w[200 204 400 502 504],
  ['GET', '/api/public/market-index'] => %w[200 400 500 502 504],
  ['GET', '/api/assets/latest'] => %w[200 404 500 502 503 504],
  ['GET', '/api/public/exchange-rate/usd-twd'] => %w[200 404 502 504],
  ['POST', '/api/public/crawler-data/rescan'] => %w[200 405 502 503 504],
  ['GET', '/api/public/market-analysis/today'] => %w[200 502 503 504],
  ['GET', '/api/public/portfolio-advice/latest'] => %w[200 502 503 504],
  ['GET', '/api/public/trading-radar/today'] => %w[200 502 503 504]
}.transform_values(&:to_set).freeze

def assert!(condition, message)
  raise message unless condition
end

def nginx_routes
  lines = File.readlines(NGINX, chomp: true)
  routes = {}
  lines.each_with_index do |line, index|
    match = line.match(/^(\s*)location = (\/\S+) \{\s*$/)
    next unless match

    indent = match[1].length
    path = match[2]
    body = []
    cursor = index + 1
    while cursor < lines.length && lines[cursor] !~ /^\s{#{indent}}\}\s*$/
      body << lines[cursor]
      cursor += 1
    end
    guards = body.map { |entry| entry[/if \(\$request_method != ([A-Z]+)\)/, 1] }.compact.uniq
    assert!(guards.length == 1, "#{path}: exact location 必須只有一個 method guard")
    assert!(!routes.key?(path), "#{path}: api-gateway exact location 重複")
    routes[path] = guards.first
  end
  routes
end

def resolve_ref(document, ref)
  assert!(ref.is_a?(String) && ref.start_with?('#/'), "只允許 local $ref：#{ref.inspect}")
  ref.delete_prefix('#/').split('/').reduce(document) do |node, token|
    key = token.gsub('~1', '/').gsub('~0', '~')
    assert!(node.is_a?(Hash) && node.key?(key), "無法解析 $ref #{ref}（缺 #{key}）")
    node[key]
  end
end

def walk(value, &block)
  yield value
  case value
  when Hash
    value.each_value { |child| walk(child, &block) }
  when Array
    value.each { |child| walk(child, &block) }
  end
end

document = YAML.safe_load(File.read(OPENAPI), aliases: false)
assert!(document.fetch('openapi').to_s.match?(/\A3\./), 'OpenAPI 版本必須是 3.x')
assert!(document['security'] == [], 'OpenAPI global security 必須明確為空陣列')

server_urls = document.fetch('servers').map { |server| server.fetch('url') }
assert!(server_urls.include?('http://127.0.0.1:9090'), '缺 loopback 9090 server')
assert!(server_urls.any? { |url| url.match?(%r{\Ahttps://[^/]+\.ts\.net:9090\z}) },
        '缺 Tailscale 私網 HTTPS :9090 server')
assert!(server_urls.length == 2, 'servers 只能列 loopback 與 Tailscale 私網')

paths = document.fetch('paths')
openapi_routes = {}
operation_ids = []
paths.each do |path, path_item|
  path_item.each do |method, operation|
    next unless HTTP_METHODS.include?(method)

    key = [method.upcase, path]
    assert!(!openapi_routes.key?(key), "OpenAPI operation 重複：#{key.join(' ')}")
    openapi_routes[key] = operation
    operation_id = operation['operationId']
    assert!(!operation_id.to_s.empty?, "#{key.join(' ')} 缺 operationId")
    tags = operation['tags']
    assert!(tags.is_a?(Array) && !tags.empty? && tags.all? { |tag| !tag.to_s.strip.empty? },
            "#{key.join(' ')} tags 必須是非空陣列且不得含空值")
    assert!(!operation['summary'].to_s.strip.empty?, "#{key.join(' ')} 缺 nonempty summary")
    assert!(!operation['description'].to_s.strip.empty?, "#{key.join(' ')} 缺 nonempty description")
    operation_ids << operation_id
  end
end
assert!(operation_ids.uniq.length == operation_ids.length, 'operationId 必須全部唯一')

gateway_set = nginx_routes.map { |path, method| [method, path] }.to_set
openapi_set = openapi_routes.keys.to_set
assert!(gateway_set == MANIFEST.keys.to_set,
        "api-gateway allowlist 與九路 manifest 不同\ngateway=#{gateway_set.to_a.sort}\nmanifest=#{MANIFEST.keys.sort}")
assert!(openapi_set == MANIFEST.keys.to_set,
        "OpenAPI paths 與九路 manifest 不同\nopenapi=#{openapi_set.to_a.sort}\nmanifest=#{MANIFEST.keys.sort}")

walk(document) do |node|
  resolve_ref(document, node['$ref']) if node.is_a?(Hash) && node.key?('$ref')
end

# RFC ProblemDetail / Spring default errors 允許擴充欄位；其餘固定 object 必須關閉
# additionalProperties，真正的 map 則必須以 typed schema 描述 value。
dynamic_objects = Set['ProblemDetail', 'SpringBasicError', 'SpringWebFluxBasicError']
document.dig('components', 'schemas').each do |name, schema|
  next unless schema.is_a?(Hash) && schema['type'] == 'object'

  additional = schema['additionalProperties']
  if dynamic_objects.include?(name)
    assert!(additional == true, "#{name}: 擴充型 error schema 應明確允許 extension members")
  elsif schema.key?('properties')
    assert!(additional == false, "#{name}: 固定 object 必須 additionalProperties: false")
  else
    assert!(additional.is_a?(Hash) && !additional.empty?, "#{name}: map value schema 不可未型別化")
  end
end

openapi_routes.each do |key, operation|
  method, path = key
  parameters = operation.fetch('parameters', [])
  parameters.each do |parameter|
    %w[name in required description schema].each do |field|
      assert!(parameter.key?(field), "#{method} #{path}: parameter 缺 #{field}")
    end
    assert!([true, false].include?(parameter['required']),
            "#{method} #{path}: parameter required 必須是 boolean")
    assert!(!parameter['description'].to_s.empty? && parameter['schema'].is_a?(Hash),
            "#{method} #{path}: parameter description/schema 不完整")
    assert!(parameter.key?('example'), "#{method} #{path}: parameter 缺合成 example")
  end

  if operation.key?('requestBody')
    body = operation.fetch('requestBody')
    assert!(body.key?('required') && body['content'].is_a?(Hash) && !body['content'].empty?,
            "#{method} #{path}: requestBody 不完整")
    body['content'].each do |media_type, media|
      assert!(media['schema'].is_a?(Hash), "#{method} #{path}: #{media_type} requestBody 缺 schema")
      assert!(media.key?('example') || media.key?('examples'),
              "#{method} #{path}: #{media_type} requestBody 缺 example")
    end
  end

  responses = operation.fetch('responses')
  assert!(responses.keys.to_set == MANIFEST.fetch(key),
          "#{method} #{path}: response status 漂移：#{responses.keys.sort} != #{MANIFEST.fetch(key).to_a.sort}")
  responses.each do |status, raw_response|
    response = raw_response.key?('$ref') ? resolve_ref(document, raw_response.fetch('$ref')) : raw_response
    assert!(!response['description'].to_s.empty?, "#{method} #{path} #{status}: 缺 response description")
    if key == ['GET', '/api/quotes/one'] && status == '204'
      assert!(!response.key?('content'), 'GET /api/quotes/one 204 不可宣告 body')
      next
    end
    content = response['content']
    assert!(content.is_a?(Hash) && !content.empty?, "#{method} #{path} #{status}: 缺 response body content")
    if status.start_with?('2')
      assert!(content.key?('application/json'),
              "#{method} #{path} #{status}: 有 body 的成功 response 必須含 application/json")
    end
    content.each do |media_type, media|
      assert!(media['schema'].is_a?(Hash), "#{method} #{path} #{status} #{media_type}: 缺 schema")
      next unless status.start_with?('2')

      assert!(media.key?('example') || media.key?('examples'),
              "#{method} #{path} #{status} #{media_type}: 成功 body 缺直接合成 example")
    end
  end
end

radar_success_schema = openapi_routes
                       .fetch(['GET', '/api/public/trading-radar/today'])
                       .dig('responses', '200', 'content', 'application/json', 'schema')
assert!(radar_success_schema == {'$ref' => '#/components/schemas/TradingRadarResponse'},
        '交易雷達 200 application/json 必須精確引用 TradingRadarResponse')

catalog = document.dig('components', 'schemas', 'MarketOption', 'properties')
expected_codes = %w[TWSE TPEX DJI SPX IXIC SOX FTSE DAX KOSPI N225]
expected_labels = ['台股集中市場', '台股櫃買市場', '道瓊工業', '標普 500', '那斯達克綜合',
                   '費城半導體', '英國富時 100', '德國 DAX', '韓國 KOSPI', '日經 225']
assert!(catalog.dig('value', 'enum') == expected_codes, 'MarketOption code catalog 順序漂移')
assert!(catalog.dig('label', 'enum') == expected_labels, 'MarketOption label catalog 順序漂移')
market_response = document.dig('components', 'schemas', 'MarketIndexResponse', 'properties')
assert!(market_response.dig('supportedMarkets', 'minItems') == 10 &&
        market_response.dig('supportedMarkets', 'maxItems') == 10,
        'MarketIndexResponse.supportedMarkets 必須固定 10 項')

puts 'PASS: 9090 gateway/OpenAPI 九路 parity、response manifest、parameters、examples 與 refs 完整'
