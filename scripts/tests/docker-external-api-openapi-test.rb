#!/usr/bin/env ruby
# frozen_string_literal: true

require 'set'
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
NGINX = File.join(ROOT, 'api-gateway/nginx.conf')
OPENAPI = File.join(ROOT, 'docs/openapi/docker-external-api.yaml')
COMPOSE = File.join(ROOT, 'docker-compose.yml')
HTTP_METHODS = %w[get put post delete options head patch trace].freeze

MANIFEST = {
  ['GET', '/api/quotes'] => %w[200 400 502 504],
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
compose = YAML.safe_load(File.read(COMPOSE), aliases: false)
assert!(document.fetch('openapi').to_s.match?(/\A3\./), 'OpenAPI 版本必須是 3.x')
assert!(document.dig('info', 'version') == '1.4.0', 'Requirement 108 後 OpenAPI info.version 必須為 1.4.0')
assert!(document['security'] == [], 'OpenAPI global security 必須明確為空陣列')

server_urls = document.fetch('servers').map { |server| server.fetch('url') }
assert!(server_urls.include?('http://127.0.0.1:9090'), '缺 loopback 9090 server')
assert!(server_urls.any? { |url| url.match?(%r{\Ahttps://[^/]+\.ts\.net:9090\z}) },
        '缺 Tailscale 私網 HTTPS :9090 server')
assert!(server_urls.length == 2, 'servers 只能列 loopback 與 Tailscale 私網')

gateway_healthcheck = compose.dig('services', 'api-gateway', 'healthcheck', 'test')
expected_gateway_healthcheck = [
  'CMD-SHELL',
  'wget -qO- http://127.0.0.1:9090/api/public/market-index >/dev/null || exit 1'
]
assert!(gateway_healthcheck == expected_gateway_healthcheck,
        'api-gateway healthcheck 必須只探測既有輕量 public market-index，不能讀完整 /api/quotes')
assert!(MANIFEST.key?(['GET', '/api/public/market-index']),
        'gateway healthcheck target 必須是既有九路公開 GET，不得藉 healthcheck 新增 route')

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

# Requirement 108：兩條既有 quote path 只改 host response decorator，不新增 gateway route。
quote_list = openapi_routes.fetch(['GET', '/api/quotes'])
quote_one = openapi_routes.fetch(['GET', '/api/quotes/one'])
assert!(quote_list.fetch('parameters').map { |p| p.fetch('name') } == %w[market start end],
        '/api/quotes 必須只含 market/start/end 三個 query parameter')
assert!(quote_one.fetch('parameters').map { |p| p.fetch('name') } == %w[code market start end],
        '/api/quotes/one 必須只含 code/market/start/end 四個 query parameter')
assert!(quote_list.dig('responses', '200', 'content', 'application/json', 'schema') ==
          {'type' => 'array', 'items' => {'$ref' => '#/components/schemas/DetailedLatestQuote'}},
        '/api/quotes 200 必須是 DetailedLatestQuote array')
assert!(quote_one.dig('responses', '200', 'content', 'application/json', 'schema') ==
          {'$ref' => '#/components/schemas/DetailedLatestQuote'},
        '/api/quotes/one 200 必須精確引用 DetailedLatestQuote')

schemas = document.dig('components', 'schemas')
detailed = schemas.fetch('DetailedLatestQuote')
raw_quote_keys = %w[stockCode stockName market price previousClose priceChange changePercent buyPrice sellPrice openPrice highPrice lowPrice volume tradingDate updatedAt closed source quoteStatus premiumDiscountPct]
assert!(detailed.fetch('required') == raw_quote_keys + ['marketData'],
        'DetailedLatestQuote required 必須是原始 19 欄後唯一 marketData')
assert!(detailed.fetch('properties').keys == raw_quote_keys + ['marketData'],
        'DetailedLatestQuote property 宣告順序必須保留原始 19 欄再加 marketData')
assert!(detailed.fetch('additionalProperties') == false,
        'DetailedLatestQuote 不可用 loose additionalProperties')

market_data = schemas.fetch('PublicQuoteMarketData')
assert!(market_data.fetch('required') == %w[chart quoteDetail etfConstituents dividends],
        'marketData 必須固定四個 child，不能省略或加入個人資料 child')
assert!(market_data.fetch('properties').keys == %w[chart quoteDetail etfConstituents dividends],
        'marketData 只能宣告四個公開市場 child')

chart = schemas.fetch('ChartMarketData')
assert!(chart.fetch('required') == %w[status message requestedStart requestedEnd series intraday],
        'ChartMarketData required shape 漂移')
assert!(chart.dig('properties', 'status', 'enum') == %w[AVAILABLE NO_DATA UNAVAILABLE],
        'ChartMarketData.status 必須是固定三態')
assert!(chart.dig('properties', 'message', 'type').sort == ['null', 'string'],
        'ChartMarketData.message 必須明確 nullable')
assert!(chart.dig('properties', 'series', '$ref') == '#/components/schemas/ChartSeries',
        'ChartMarketData.series 必須為 typed ChartSeries')

chart_latest = schemas.fetch('ChartLatest')
chart_latest_keys = %w[ma5 ma20 ma60 ma240 k d j9 k3d2 rsv prevK prevD prevJ9 prevK3d2 prevRsv ema12 ema26 dif macd osc rsi5 rsi10 bias10 bias20 b10b20 wr9 prevEma12 prevEma26 prevDif prevMacd prevRsi5 prevRsi10 prevBias10 prevBias20 prevB10b20 prevWr9]
assert!(chart_latest.fetch('required') == chart_latest_keys,
        'ChartLatest required 必須精確對應 Java record 欄位與大小寫')
assert!(chart_latest.fetch('properties').keys == chart_latest_keys,
        'ChartLatest properties 必須精確對應 Java record 欄位與大小寫')
assert!(chart_latest.fetch('additionalProperties') == false,
        'ChartLatest 不可用 loose additionalProperties')
chart_latest.fetch('properties').each do |name, property|
  assert!(property['type'].sort == %w[null number],
          "ChartLatest.#{name} 必須為 nullable number")
end

intraday = schemas.fetch('IntradayMarketData')
assert!(intraday.fetch('required') == %w[status message tradingDate ticks],
        'IntradayMarketData required shape 漂移')
assert!(intraday.dig('properties', 'status', 'enum') == %w[AVAILABLE NO_DATA UNAVAILABLE],
        'IntradayMarketData.status 必須是固定三態')
assert!(intraday.dig('properties', 'message', 'type').sort == ['null', 'string'] &&
        intraday.dig('properties', 'tradingDate', 'type').sort == ['null', 'string'],
        'IntradayMarketData message/tradingDate 必須明確 nullable')
assert!(intraday.dig('properties', 'ticks', 'type') == 'array' &&
        intraday.dig('properties', 'ticks', 'items', '$ref') == '#/components/schemas/IntradayTick',
        'IntradayMarketData.ticks 必須是 typed non-null array')

etf = schemas.fetch('PublicEtfConstituents')
assert!(etf.dig('properties', 'holdings', 'items', '$ref') == '#/components/schemas/EtfConstituent',
        'etfConstituents.holdings 只能是公開發行人成分股 typed array')
assert!(schemas.fetch('EtfConstituent').dig('properties', 'shares', 'description').include?('不是個人'),
        'ETF shares 必須明確標示不是個人持股')

forbidden = %w[configuredAdmin personalHoldings portfolio assetSnapshot user account broker costPrice investmentCost currentValue transaction allocation advice]
forbidden.each do |field|
  assert!(!detailed.fetch('properties').key?(field) && !market_data.fetch('properties').key?(field),
          "公開 quote schema 不可含個人資料欄位 #{field}")
end

nginx = File.read(NGINX)
assert!(nginx.scan(/set \$quotes_upstream bff:8080;/).length == 2,
        '兩條 exact quote gateway location 都必須改 proxy BFF')
assert!(!nginx.match?(/location = \/api\/quotes \{[^}]*external-materials-service:8080/m) &&
        !nginx.match?(/location = \/api\/quotes\/one \{[^}]*external-materials-service:8080/m),
        'quote gateway 不可仍直接送 external-materials')

puts 'PASS: 9090 gateway/OpenAPI 九路 parity、response manifest、parameters、examples 與 refs 完整'
