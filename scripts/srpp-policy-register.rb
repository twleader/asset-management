#!/usr/bin/env ruby
# frozen_string_literal: true

# srpp-policy-register — 產生 SRPP 規則包登錄 SQL（Requirement 163／Task 452.4）
#
# 用法：
#   ruby scripts/srpp-policy-register.rb --bundle <64hex> --policy <policy.json> --manifest <manifest.json>
#
# 行為：
#   * 只用 Ruby stdlib；不連 DB。stdout 輸出一行
#       INSERT INTO srpp_policy_registry (...) VALUES (...) ON CONFLICT (policy_bundle_sha256) DO NOTHING;
#     由維運人員自行檢視後離線執行。stderr 印出伺服器將計算出的 calculationPolicySha256 與
#     formulaSetSha256（皆為 SHA-256(UTF-8(RFC 8785 JCS(document)))）。
#   * 拒絕浮點數字（權重與金額一律以 canonical Decimal 字串表示）、重複 key、全零 bundle hash
#     （保留給 Tailscale preflight 探測，禁止登錄）。
#   * policy 必須恰為 {"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{...}}；target key 需符合
#     ASSET_KEY_V1（STOCK:<market>:<code>｜FUND:<code>｜CASH:<currency>:<type>），值為 0–1 的
#     canonical Decimal 字串且總和 ≤ 1。
#   * formula_version 取自 manifest 的 formulaVersion。伺服器端仍會再以程式內建 manifest 比對；
#     manifest 不符的列會被視為未支援（409 POLICY_UNSUPPORTED），本腳本不代替該驗證。

require 'json'
require 'digest'
require 'bigdecimal'
require 'optparse'

Encoding.default_external = Encoding::UTF_8
Encoding.default_internal = Encoding::UTF_8

module SrppPolicyRegister
  class Error < StandardError; end

  MAX_SAFE_INTEGER = 9_007_199_254_740_991
  CANONICAL_DECIMAL = /\A-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?\z/.freeze
  ASSET_KEY = [/\ASTOCK:[^:]+:[^:]+\z/, /\AFUND:[^:]+\z/, /\ACASH:[^:]+:.+\z/].freeze
  HASH = /\A[0-9a-f]{64}\z/.freeze

  # JSON.parse 以 object_class 建立物件並逐一 []= 寫入；重複 key 在此攔下。
  class StrictHash < Hash
    def []=(key, value)
      raise Error, "duplicate key: #{key}" if key?(key)

      super
    end
  end

  module_function

  def parse_strict(text)
    JSON.parse(text, object_class: StrictHash, create_additions: false)
  rescue JSON::ParserError => e
    raise Error, "invalid JSON: #{e.message.lines.first&.strip}"
  end

  def jcs(value)
    case value
    when Hash
      keys = value.keys
      keys.each { |k| raise Error, 'object key must be string' unless k.is_a?(String) }
      sorted = keys.sort_by { |k| k.encode('UTF-16BE').unpack('n*') }
      "{#{sorted.map { |k| "#{jcs_string(k)}:#{jcs(value[k])}" }.join(',')}}"
    when Array
      "[#{value.map { |v| jcs(v) }.join(',')}]"
    when String
      jcs_string(value)
    when true then 'true'
    when false then 'false'
    when nil then 'null'
    when Integer
      raise Error, 'integer out of IEEE-754 safe range' if value.abs > MAX_SAFE_INTEGER

      value.to_s
    when Float, BigDecimal
      raise Error, 'floating-point numbers are not allowed (use canonical decimal strings)'
    else
      raise Error, "unsupported JSON value: #{value.class}"
    end
  end

  def jcs_string(text)
    out = +'"'
    text.each_char do |c|
      out << case c
             when '"' then '\\"'
             when '\\' then '\\\\'
             when "\b" then '\\b'
             when "\f" then '\\f'
             when "\n" then '\\n'
             when "\r" then '\\r'
             when "\t" then '\\t'
             else
               c.ord < 0x20 ? format('\\u%04x', c.ord) : c
             end
    end
    out << '"'
  end

  def sha256(text)
    Digest::SHA256.hexdigest(text.encode('UTF-8'))
  end

  def validate_bundle!(bundle)
    raise Error, 'bundle hash must be 64 lowercase hex' unless bundle.is_a?(String) && HASH.match?(bundle)
    raise Error, 'all-zero bundle hash is reserved for preflight and cannot be registered' if bundle == '0' * 64
  end

  def validate_policy!(policy)
    raise Error, 'policy must be a JSON object' unless policy.is_a?(Hash)
    raise Error, 'policy must have exactly schema and targets' unless policy.keys.sort == %w[schema targets]
    raise Error, 'policy schema must be SRPP_POLICY_DOCUMENT_V1' unless policy['schema'] == 'SRPP_POLICY_DOCUMENT_V1'

    targets = policy['targets']
    raise Error, 'targets must be an object' unless targets.is_a?(Hash)

    sum = BigDecimal('0')
    targets.each do |key, value|
      raise Error, "invalid asset key: #{key}" unless ASSET_KEY.any? { |re| re.match?(key) }
      unless value.is_a?(String) && CANONICAL_DECIMAL.match?(value) && value != '-0' && value.length <= 80
        raise Error, "target #{key} must be a canonical decimal string"
      end

      weight = BigDecimal(value)
      raise Error, "target #{key} must be within 0..1" if weight.negative? || weight > 1

      sum += weight
    end
    raise Error, 'target weights must sum to at most 1' if sum > 1
  end

  def validate_manifest!(manifest)
    raise Error, 'manifest must be a JSON object' unless manifest.is_a?(Hash)

    version = manifest['formulaVersion']
    raise Error, 'manifest formulaVersion must be a non-empty string' unless version.is_a?(String) && !version.empty?
    raise Error, 'manifest formulaVersion too long' if version.length > 64

    version
  end

  def sql_literal(text)
    "'#{text.gsub("'", "''")}'"
  end

  # 回傳 [sql, calculation_policy_sha256, formula_set_sha256]
  def build(bundle, policy_text, manifest_text)
    validate_bundle!(bundle)
    policy = parse_strict(policy_text)
    manifest = parse_strict(manifest_text)
    policy_jcs = jcs(policy)
    manifest_jcs = jcs(manifest)
    validate_policy!(policy)
    version = validate_manifest!(manifest)
    sql = 'INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, policy_document, ' \
          "formula_manifest) VALUES (#{sql_literal(bundle)}, #{sql_literal(version)}, #{sql_literal(policy_jcs)}, " \
          "#{sql_literal(manifest_jcs)}) ON CONFLICT (policy_bundle_sha256) DO NOTHING;"
    [sql, sha256(policy_jcs), sha256(manifest_jcs)]
  end

  def main(argv, stdout: $stdout, stderr: $stderr)
    options = {}
    OptionParser.new do |o|
      o.banner = 'usage: ruby scripts/srpp-policy-register.rb --bundle <64hex> --policy <policy.json> --manifest <manifest.json>'
      o.on('--bundle HASH') { |v| options[:bundle] = v }
      o.on('--policy FILE') { |v| options[:policy] = v }
      o.on('--manifest FILE') { |v| options[:manifest] = v }
    end.parse!(argv)
    %i[bundle policy manifest].each { |k| raise Error, "missing --#{k}" unless options[k] }

    sql, policy_hash, formula_hash = build(options[:bundle], File.read(options[:policy], encoding: 'UTF-8'),
                                           File.read(options[:manifest], encoding: 'UTF-8'))
    stdout.puts sql
    stderr.puts "calculationPolicySha256=#{policy_hash}"
    stderr.puts "formulaSetSha256=#{formula_hash}"
    0
  rescue Error, OptionParser::ParseError, Errno::ENOENT => e
    stderr.puts "srpp-policy-register: #{e.message}"
    1
  end
end

exit(SrppPolicyRegister.main(ARGV)) if $PROGRAM_NAME == __FILE__
