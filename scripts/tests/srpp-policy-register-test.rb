#!/usr/bin/env ruby
# frozen_string_literal: true

# Requirement 163／Task 452.4：scripts/srpp-policy-register.rb 的離線契約測試（合成 policy，不連 DB）。
#   ruby scripts/tests/srpp-policy-register-test.rb

Encoding.default_external = Encoding::UTF_8
Encoding.default_internal = Encoding::UTF_8

require 'minitest/autorun'
require 'stringio'
require 'tmpdir'
require_relative '../srpp-policy-register'

class SrppPolicyRegisterTest < Minitest::Test
  BUNDLE = 'b' * 64
  # 與 backend SrppFormulaCatalog.MANIFEST_JSON 相同內容（刻意打亂 key 順序以驗證 JCS）。
  MANIFEST = <<~JSON
    {"hash":"RFC8785-JCS+SHA-256","schema":"SRPP_FORMULA_MANIFEST_V1","formulaVersion":"ASSET_MGMT_SRPP_V1",
     "calculations":{
      "assets":{"calculationId":"ASSET_MGMT_ASSETS_RECON_V1","inputs":"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)","revisionProjection":"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1","dataAsOf":"MIN_LIVE_STOCK_UPDATED_AT_V1","tolerance":"max(0.01,max(abs(detail),abs(reported))*0.0001)","depositInterest":"SnapshotAggregateCalculator.depositEstimatedInterest"},
      "allocation":{"calculationId":"ASSET_MGMT_TOTAL_EXPOSURE_V1","assetKey":"ASSET_KEY_V1","division":"MathContext(34,HALF_EVEN)"},
      "cashIncome":{"calculationId":"ASSET_MGMT_GROSS_INCOME_V1","netCalculation":"NOT_VERIFIED"},
      "funding":{"calculationId":"ASSET_MGMT_FUNDING_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"},
      "completedTechnicals":{"calculationId":"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"}},
     "timezone":"Asia/Taipei","offsetLessTimezone":"Asia/Taipei","decimal":"canonical-string"}
  JSON
  # backend SrppFormulaCatalogTest 的 golden。
  FORMULA_SET_SHA256 = '35cabe65dccf3479356958477661c1ac79686db229f703d8a2989ec77c8fb901'
  POLICY = '{"targets": {"STOCK:台股:0050": "0.2", "CASH:TWD:活存": "0.4", "FUND:F001": "0.35"}, ' \
           '"schema": "SRPP_POLICY_DOCUMENT_V1"}'
  POLICY_JCS = '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"CASH:TWD:活存":"0.4","FUND:F001":"0.35",' \
               '"STOCK:台股:0050":"0.2"}}'

  def build(policy: POLICY, bundle: BUNDLE, manifest: MANIFEST)
    SrppPolicyRegister.build(bundle, policy, manifest)
  end

  def test_outputs_jcs_insert_and_hashes
    sql, policy_hash, formula_hash = build
    assert_equal FORMULA_SET_SHA256, formula_hash
    assert_equal Digest::SHA256.hexdigest(POLICY_JCS), policy_hash
    assert_includes sql, "'#{POLICY_JCS}'"
    assert sql.start_with?("INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, " \
                           "policy_document, formula_manifest) VALUES ('#{BUNDLE}', 'ASSET_MGMT_SRPP_V1', ")
    assert sql.end_with?('ON CONFLICT (policy_bundle_sha256) DO NOTHING;')
    assert_includes sql, %('{"calculations":{"allocation":{"assetKey":"ASSET_KEY_V1",)
    refute_match(/\n/, sql)
  end

  def test_jcs_sorts_by_utf16_code_units_and_escapes
    value = { "\u{FB01}" => 'a', "\u{1F600}" => "q\"\\\n\u0001", 'B' => [1, true, nil] }
    assert_equal "{\"B\":[1,true,null],\"\u{1F600}\":\"q\\\"\\\\\\n\\u0001\",\"\u{FB01}\":\"a\"}",
                 SrppPolicyRegister.jcs(value)
  end

  def test_sql_literal_doubles_single_quotes
    sql, = build(policy: '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"CASH:TWD:o\'k":"0.1"}}')
    assert_includes sql, "CASH:TWD:o''k"
  end

  def test_rejects_floats
    assert_raises(SrppPolicyRegister::Error) { build(policy: '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":0.5}}') }
    assert_raises(SrppPolicyRegister::Error) { build(policy: '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":"0.5"},"x":1.0}') }
    assert_raises(SrppPolicyRegister::Error) { build(manifest: '{"formulaVersion":"V","n":1.5}') }
    assert_raises(SrppPolicyRegister::Error) { SrppPolicyRegister.jcs(9_007_199_254_740_992) }
  end

  def test_rejects_all_zero_and_malformed_bundle
    assert_raises(SrppPolicyRegister::Error) { build(bundle: '0' * 64) }
    assert_raises(SrppPolicyRegister::Error) { build(bundle: 'B' * 64) }
    assert_raises(SrppPolicyRegister::Error) { build(bundle: 'b' * 63) }
  end

  def test_rejects_invalid_policy_structures
    [
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":"0.50"}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":"1.1"}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":"0.6","FUND:B":"0.5"}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"FUND:A":"0.1","FUND:A":"0.2"}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{"TW:0050":"0.1"}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{}, "extra":"x"}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V2","targets":{}}',
      '{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":[]}',
      '[]'
    ].each do |policy|
      assert_raises(SrppPolicyRegister::Error, policy) { build(policy: policy) }
    end
  end

  def test_main_writes_sql_to_stdout_and_hashes_to_stderr
    Dir.mktmpdir do |dir|
      File.write(File.join(dir, 'p.json'), POLICY)
      File.write(File.join(dir, 'm.json'), MANIFEST)
      out = StringIO.new
      err = StringIO.new
      code = SrppPolicyRegister.main(['--bundle', BUNDLE, '--policy', File.join(dir, 'p.json'),
                                      '--manifest', File.join(dir, 'm.json')], stdout: out, stderr: err)
      assert_equal 0, code
      assert out.string.start_with?('INSERT INTO srpp_policy_registry')
      assert_includes err.string, "formulaSetSha256=#{FORMULA_SET_SHA256}"
      assert_includes err.string, "calculationPolicySha256=#{Digest::SHA256.hexdigest(POLICY_JCS)}"

      err = StringIO.new
      out = StringIO.new
      code = SrppPolicyRegister.main(['--bundle', '0' * 64, '--policy', File.join(dir, 'p.json'),
                                      '--manifest', File.join(dir, 'm.json')], stdout: out, stderr: err)
      assert_equal 1, code
      assert_empty out.string
    end
  end
end
