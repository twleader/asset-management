#!/usr/bin/env ruby
# frozen_string_literal: true

# Offline current-policy report registration SQL only. Never connects to a database.
require_relative 'srpp-policy-register'
require 'pathname'

module SrppReportPolicyRegister
  REPORT_ROUTE = '/api/public/srpp/report-calculations'
  CANDIDATE = {
    'publication_status' => 'PUBLISHED_SWAGGER_1_26_0',
    'daily_adoption_status' => 'ENABLED_TRIAL_RUNTIME_PENDING',
    'enabled_for_daily' => true,
    'report_slots' => %w[09:05 12:00],
    'uses_legacy_api_slot_mapping' => false,
    'calculator_version' => 'SRPP_REPORT_CALCULATIONS_V1',
    'consumer_verifier' => 'scripts/verify_srpp_report_calculations.py',
    'prerequisites' => %w[CURRENT_POLICY_REGISTRATION INDEPENDENT_CONSUMER_VERIFIER],
    'observations_pending' => %w[SCOPED_SAME_INPUT_GOLDEN_PARITY TRADING_DAY_RUNTIME_ACCEPTANCE],
    'fallback_reason' => 'REPORT_CALCULATION_SCOPE_UNAVAILABLE'
  }.freeze

  module_function

  def parse_model_strict(text)
    JSON.parse(text, object_class: SrppPolicyRegister::StrictHash, decimal_class: BigDecimal,
                     create_additions: false)
  rescue JSON::ParserError => e
    raise SrppPolicyRegister::Error, "invalid JSON: #{e.message.lines.first&.strip}"
  end

  def verify_report_candidate!(model)
    raise SrppPolicyRegister::Error, 'REPORT_MODEL_INVALID' unless model.is_a?(Hash)

    orchestrated = model.dig('execution_policy', 'llm_orchestrated_api_d197')
    routes = orchestrated.is_a?(Hash) ? orchestrated['read_only_routes'] : nil
    unless routes.is_a?(Array) && routes.any? { |route| route == REPORT_ROUTE }
      raise SrppPolicyRegister::Error, 'REPORT_ROUTE_UNSUPPORTED'
    end

    candidate = orchestrated.dig('published_read_candidates', REPORT_ROUTE)
    unless candidate.is_a?(Hash) && candidate.keys.sort == CANDIDATE.keys.sort &&
           CANDIDATE.all? { |key, value| candidate[key] == value }
      raise SrppPolicyRegister::Error, 'REPORT_CANDIDATE_CONTRACT_INVALID'
    end
  end

  def policy_document(model)
    targets = {}
    allocations = model['detailed_target_allocation_2027']
    raise SrppPolicyRegister::Error, 'FORMAL_TARGET_MISSING' unless allocations.is_a?(Hash)

    allocations.each do |code, weight|
      key = case code
            when 'TWD_term_deposits' then 'CASH:TWD:TERM'
            when 'demand_deposits_and_cash' then 'CASH:TWD:DEMAND'
            else "STOCK:#{code.match?(/\A[A-Z.]+\z/) ? '美股' : '台股'}:#{code}"
            end
      value = weight.is_a?(BigDecimal) ? weight.to_s('F').sub(/\.0+\z/, '').sub(/(\.[0-9]*?)0+\z/, '\1') : weight.to_s
      targets[key] = value
    end
    SrppPolicyRegister.jcs('schema' => 'SRPP_POLICY_DOCUMENT_V1', 'targets' => targets)
  end

  def generate(model_bytes:, manifest_bytes:, formal_policy_bytes:, formula_bytes:)
    model = parse_model_strict(model_bytes)
    manifest = SrppPolicyRegister.parse_strict(manifest_bytes)
    raise SrppPolicyRegister::Error, 'POLICY_MANIFEST_INVALID' unless manifest.is_a?(Hash)

    bundle = manifest['POLICY_BUNDLE_SHA256']
    body = manifest.reject { |key, _| key == 'POLICY_BUNDLE_SHA256' }
    unless SrppPolicyRegister.sha256(SrppPolicyRegister.jcs(body)) == bundle &&
           manifest.dig('files', 'assumptions/model_inputs.json') == SrppPolicyRegister.sha256(model_bytes)
      raise SrppPolicyRegister::Error, 'POLICY_BYTES_MISMATCH'
    end
    unless %w[decision policy_version model_version].all? { |key| model[key] == manifest[key] }
      raise SrppPolicyRegister::Error, 'POLICY_VERSION_MISMATCH'
    end
    verify_report_candidate!(model)

    policy_text = policy_document(model)
    expected = SrppPolicyRegister.parse_strict(formal_policy_bytes)
    unless SrppPolicyRegister.jcs(expected) == policy_text
      raise SrppPolicyRegister::Error, 'FORMAL_TARGET_ARTIFACT_MISMATCH'
    end
    sql, policy_hash, formula_hash = SrppPolicyRegister.build(bundle, policy_text, formula_bytes)
    [sql, "current-policy policy=#{policy_hash} formula=#{formula_hash} model=#{SrppPolicyRegister.sha256(model_bytes)}"]
  end

  def generate_from_root(root)
    root = Pathname.new(root)
    generate(
      model_bytes: (root / 'backend/src/main/resources/srpp/decision/model_inputs.json').read,
      manifest_bytes: (root / 'backend/src/main/resources/srpp/decision/policy_manifest.json').read,
      formal_policy_bytes: (root / 'backend/src/main/resources/srpp/report/d201-policy-document.json').read,
      formula_bytes: (root / 'spec/fixtures/srpp_formula_manifest_v2.json').read
    )
  end
end

if $PROGRAM_NAME == __FILE__
  sql, diagnostic = SrppReportPolicyRegister.generate_from_root(Pathname.new(__dir__).parent)
  warn diagnostic
  puts sql
end
