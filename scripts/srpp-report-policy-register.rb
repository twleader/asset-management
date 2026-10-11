#!/usr/bin/env ruby
# frozen_string_literal: true
# Offline D-201 registration SQL only. Never connects to a database.
require_relative 'srpp-policy-register'
require 'pathname'
root=Pathname.new(__dir__).parent
model_bytes=(root/'backend/src/main/resources/srpp/decision/model_inputs.json').read
manifest_bytes=(root/'backend/src/main/resources/srpp/decision/policy_manifest.json').read
model=JSON.parse(model_bytes,decimal_class:BigDecimal)
manifest=SrppPolicyRegister.parse_strict(manifest_bytes)
bundle=manifest['POLICY_BUNDLE_SHA256'];body=manifest.reject{|k,_| k=='POLICY_BUNDLE_SHA256'}
raise 'POLICY_BYTES_MISMATCH' unless SrppPolicyRegister.sha256(SrppPolicyRegister.jcs(body))==bundle && manifest.dig('files','assumptions/model_inputs.json')==SrppPolicyRegister.sha256(model_bytes)
raise 'POLICY_VERSION_MISMATCH' unless %w[decision policy_version model_version].all?{|k| model[k]==manifest[k]} && model['decision']=='D-201'
targets={}
model.fetch('detailed_target_allocation_2027').each do |code,weight|
 key=case code
 when 'TWD_term_deposits' then 'CASH:TWD:TERM'
 when 'demand_deposits_and_cash' then 'CASH:TWD:DEMAND'
 else "STOCK:#{code.match?(/\A[A-Z.]+\z/) ? '美股' : '台股'}:#{code}"
 end
 value=weight.is_a?(BigDecimal) ? weight.to_s('F').sub(/\.0+\z/,'').sub(/(\.[0-9]*?)0+\z/,'\\1') : weight.to_s
 targets[key]=value
end
policy={'schema'=>'SRPP_POLICY_DOCUMENT_V1','targets'=>targets}
policy_text=SrppPolicyRegister.jcs(policy)
expected=(root/'backend/src/main/resources/srpp/report/d201-policy-document.json').read
raise 'FORMAL_TARGET_ARTIFACT_MISMATCH' unless SrppPolicyRegister.jcs(SrppPolicyRegister.parse_strict(expected))==policy_text
formula=(root/'spec/fixtures/srpp_formula_manifest_v2.json').read
sql,policy_hash,formula_hash=SrppPolicyRegister.build(bundle,policy_text,formula)
warn "D201 policy=#{policy_hash} formula=#{formula_hash} model=#{SrppPolicyRegister.sha256(model_bytes)}"
puts sql
