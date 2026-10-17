#!/usr/bin/env ruby
# frozen_string_literal: true

require 'minitest/autorun'
require 'pathname'
require_relative '../srpp-report-policy-register'

class SrppReportPolicyRegisterTest < Minitest::Test
  ROOT = Pathname.new(__dir__).parent.parent
  FIELDS = %w[
    publication_status daily_adoption_status enabled_for_daily report_slots
    uses_legacy_api_slot_mapping calculator_version consumer_verifier prerequisites
    observations_pending fallback_reason
  ].freeze
  ARRAY_FIELDS = %w[report_slots prerequisites observations_pending].freeze

  def test_current_d204_accepts_and_extra_root_route_is_not_locked
    model = current_model
    routes(model) << '/api/public/srpp/future-projection'
    sql, = generate(model)
    assert_match(/\AINSERT INTO srpp_policy_registry \(policy_bundle_sha256/, sql)
  end

  def test_every_candidate_field_rejects_missing_wrong_type_and_wrong_value
    FIELDS.each do |field|
      model = current_model
      candidate(model).delete(field)
      assert_semantic_rejected(model)

      model = current_model
      candidate(model)[field] = candidate(model)[field].is_a?(String) ? true : 'WRONG_TYPE'
      assert_semantic_rejected(model)

      model = current_model
      value = candidate(model)[field]
      candidate(model)[field] = case value
                                when String then "#{value}_MUTANT"
                                when TrueClass, FalseClass then !value
                                when Array then value.each_with_index.map { |entry, index| index.zero? ? 'MUTANT' : entry }
                                end
      assert_semantic_rejected(model)
    end
  end

  def test_extra_field_and_every_ordered_array_extra_value_are_rejected
    model = current_model
    candidate(model)['unexpected'] = 'forbidden'
    assert_semantic_rejected(model)

    ARRAY_FIELDS.each do |field|
      model = current_model
      candidate(model)[field] << 'EXTRA'
      assert_semantic_rejected(model)
    end
  end

  def test_historical_d201_candidate_and_missing_report_route_are_rejected
    model = current_model
    model['decision'] = 'D-201'
    model['policy_version'] = '2.49.0'
    orchestration(model)['decision'] = 'D-201'
    candidate(model)['publication_status'] = 'PUBLISHED_SWAGGER_1_25_0'
    assert_semantic_rejected(model)

    model = current_model
    routes(model).clear
    assert_semantic_rejected(model, 'REPORT_ROUTE_UNSUPPORTED')
  end

  def test_duplicate_model_key_is_rejected_before_generation
    duplicate = (ROOT / 'backend/src/main/resources/srpp/decision/model_inputs.json').read.sub(
      /\n  "model_version": "3\.22\.0",/,
      "\n  \"model_version\": \"3.22.0\",\n  \"model_version\": \"3.22.0\","
    )
    error = assert_raises(SrppPolicyRegister::Error) do
      SrppReportPolicyRegister.generate(
        model_bytes: duplicate,
        manifest_bytes: (ROOT / 'backend/src/main/resources/srpp/decision/policy_manifest.json').read,
        formal_policy_bytes: (ROOT / 'backend/src/main/resources/srpp/report/d201-policy-document.json').read,
        formula_bytes: (ROOT / 'spec/fixtures/srpp_formula_manifest_v2.json').read
      )
    end
    assert_equal 'duplicate key: model_version', error.message
  end

  private

  def current_model
    strict = SrppReportPolicyRegister.parse_model_strict((ROOT / 'backend/src/main/resources/srpp/decision/model_inputs.json').read)
    mutable(strict)
  end

  def orchestration(model)
    model.fetch('execution_policy').fetch('llm_orchestrated_api_d197')
  end

  def routes(model)
    orchestration(model).fetch('read_only_routes')
  end

  def candidate(model)
    orchestration(model).fetch('published_read_candidates').fetch(SrppReportPolicyRegister::REPORT_ROUTE)
  end

  def fixture(model)
    model_bytes = json_document(model)
    manifest = mutable(SrppPolicyRegister.parse_strict(
      (ROOT / 'backend/src/main/resources/srpp/decision/policy_manifest.json').read))
    %w[decision policy_version model_version].each { |field| manifest[field] = model.fetch(field) }
    manifest.fetch('files')['assumptions/model_inputs.json'] = SrppPolicyRegister.sha256(model_bytes)
    manifest.delete('POLICY_BUNDLE_SHA256')
    manifest['POLICY_BUNDLE_SHA256'] = SrppPolicyRegister.sha256(SrppPolicyRegister.jcs(manifest))
    [model_bytes, JSON.generate(manifest)]
  end

  def assert_raw_authority_valid(model_bytes, manifest_bytes)
    model = SrppReportPolicyRegister.parse_model_strict(model_bytes)
    manifest = SrppPolicyRegister.parse_strict(manifest_bytes)
    body = manifest.reject { |key, _| key == 'POLICY_BUNDLE_SHA256' }
    assert_equal SrppPolicyRegister.sha256(SrppPolicyRegister.jcs(body)), manifest.fetch('POLICY_BUNDLE_SHA256')
    assert_equal SrppPolicyRegister.sha256(model_bytes), manifest.fetch('files').fetch('assumptions/model_inputs.json')
    %w[decision policy_version model_version].each { |field| assert_equal model.fetch(field), manifest.fetch(field) }
  end

  def generate(model)
    model_bytes, manifest_bytes = fixture(model)
    assert_raw_authority_valid(model_bytes, manifest_bytes)
    SrppReportPolicyRegister.generate(
      model_bytes: model_bytes,
      manifest_bytes: manifest_bytes,
      formal_policy_bytes: (ROOT / 'backend/src/main/resources/srpp/report/d201-policy-document.json').read,
      formula_bytes: (ROOT / 'spec/fixtures/srpp_formula_manifest_v2.json').read
    )
  end

  def assert_semantic_rejected(model, reason = 'REPORT_CANDIDATE_CONTRACT_INVALID')
    error = assert_raises(SrppPolicyRegister::Error) { generate(model) }
    assert_equal reason, error.message
  end

  def mutable(value)
    case value
    when Hash then value.each_with_object({}) { |(key, entry), copy| copy[key] = mutable(entry) }
    when Array then value.map { |entry| mutable(entry) }
    else value
    end
  end

  def json_document(value)
    case value
    when Hash then "{#{value.map { |key, entry| "#{JSON.generate(key)}:#{json_document(entry)}" }.join(',')}}"
    when Array then "[#{value.map { |entry| json_document(entry) }.join(',')}]"
    when BigDecimal then value.to_s('F')
    when Integer then value.to_s
    else JSON.generate(value)
    end
  end
end
