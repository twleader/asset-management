#!/usr/bin/env ruby
# frozen_string_literal: true
# Emits only the reviewed US response contract. No SRPP writes or metadata sync.
require 'yaml'
require 'json'
root=File.expand_path('..',__dir__)
doc=YAML.safe_load(File.read(File.join(root,'docs/openapi/docker-external-api.yaml')),aliases:false)
schemas=doc.fetch('components').fetch('schemas')
names=schemas.keys.select{|n|n.start_with?('SrppUs')}
# D236 US legs reuse the published generic leg/fact/receipt definitions. Export their
# complete reference closure, rather than relying on an undeclared TW schema.
references=lambda do |value|
  case value
  when Hash
    own=value['$ref'].is_a?(String) && value['$ref'].start_with?('#/components/schemas/') ? [value['$ref'].delete_prefix('#/components/schemas/')] : []
    own+value.values.flat_map{|v|references.call(v)}
  when Array then value.flat_map{|v|references.call(v)}
  else []
  end
end
pending=names.dup
until pending.empty?
  name=pending.shift
  references.call(schemas.fetch(name)).each do |dependency|
    next if names.include?(dependency)
    raise "Unknown US schema dependency: #{dependency}" unless schemas.key?(dependency)
    names<<dependency;pending<<dependency
  end
end
contract={'$ref'=>'#/components/schemas/SrppUsReportCalculations','components'=>{'schemas'=>doc['components']['schemas'].select{|k,_|names.include?(k)}},'x-api-scope'=>'US_REPORT_CALCULATIONS'}
bytes=JSON.pretty_generate(contract)+"\n"
if ARGV.length==2 && ARGV[0]=='--check'
 abort 'US consumer schema bytes differ from OpenAPI' unless File.binread(ARGV[1])==bytes.b
 puts 'PASS: US consumer schema matches OpenAPI'
elsif ARGV.empty?
 print bytes
else
 abort 'Usage: export-srpp-us-consumer-schema.rb [--check PATH]'
end
