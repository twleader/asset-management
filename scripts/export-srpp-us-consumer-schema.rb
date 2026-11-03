#!/usr/bin/env ruby
# frozen_string_literal: true
# Emits only the reviewed US response contract. No SRPP writes or metadata sync.
require 'yaml'
require 'json'
root=File.expand_path('..',__dir__)
doc=YAML.safe_load(File.read(File.join(root,'docs/openapi/docker-external-api.yaml')),aliases:false)
names=doc.fetch('components').fetch('schemas').keys.select{|n|n.start_with?('SrppUs')}
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
