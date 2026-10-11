#!/usr/bin/env ruby
# frozen_string_literal: true
require 'minitest/autorun'
require 'tmpdir'
require 'open3'
require_relative '../render-9090-openapi-docs'

class UnifiedSwaggerRendererTest < Minitest::Test
  def setup
    @financial = YAML.safe_load(File.read(OPENAPI), aliases: false)
    @observation = YAML.safe_load(File.read(OBSERVATION_OPENAPI), aliases: false)
  end

  def test_union_covers_all_operations_without_altering_sources
    before = Marshal.dump([@financial, @observation])
    union = unified_document(@financial, @observation)
    content = render(union)
    assert_equal before, Marshal.dump([@financial, @observation])
    assert_equal 25, union['paths'].values.sum(&:length)
    assert_includes content, '| 契約版本 | `1.25.0` |'
    assert_includes content, '| AI 流程觀測契約版本 | `1.1.0` |'
    [@financial, @observation].each do |source|
      source['paths'].each do |path, methods|
        methods.each do |method, operation|
          assert_includes content, "`#{method.upcase} #{path}`"
          if operation['requestBody']
            assert_includes content, JSON.pretty_generate(operation['requestBody'])
          end
        end
      end
      source.dig('components', 'schemas').each do |name, schema|
        next unless reachable_schema_names(source).include?(name)
        assert_includes content, JSON.pretty_generate(schema)
      end
    end
    assert_includes content, JSON.pretty_generate(@observation.dig('components', 'securitySchemes', 'AiFlowToken'))
    assert_includes content, JSON.pretty_generate(@observation.dig('paths', '/api/srpp/ai-flow/events', 'post', 'responses'))
  end

  def test_rejects_namespace_collisions_and_unresolved_local_refs
    [
      ->(o) { o['paths'][@financial['paths'].keys.first] = o['paths'].values.first },
      ->(o) { o['paths'].values.first['post']['operationId'] = @financial['paths'].values.first.values.first['operationId'] },
      ->(o) { o['components']['schemas'][@financial.dig('components', 'schemas').keys.first] = {} },
      ->(o) { o.dig('paths', '/api/srpp/ai-flow/events', 'post', 'requestBody', 'content', 'application/json', 'schema')['$ref'] = '#/components/schemas/UNKNOWN' }
    ].each do |mutate|
      observation = Marshal.load(Marshal.dump(@observation))
      mutate.call(observation)
      assert_raises(RuntimeError) { unified_document(@financial, observation) }
    end
  end

  def test_check_is_read_only_and_cloud_does_not_require_srpp
    Dir.mktmpdir do |folder|
      root = File.join(folder, 'isolated')
      FileUtils.mkdir_p(File.join(root, 'scripts'))
      FileUtils.mkdir_p(File.join(root, 'docs/openapi'))
      FileUtils.cp(__dir__ + '/../render-9090-openapi-docs.rb', File.join(root, 'scripts'))
      [OPENAPI, OBSERVATION_OPENAPI].each { |source| FileUtils.cp(source, File.join(root, 'docs/openapi')) }
      script = File.join(root, 'scripts/render-9090-openapi-docs.rb')
      env = {'CLAUDE_CODE_REMOTE' => 'true'}
      output, status = Open3.capture2e(env, 'ruby', script)
      assert status.success?, output
      targets = [File.join(root, 'docs/openapi/9090-api-swagger.md'), File.join(root, 'backend/src/main/resources/srpp/9090-api-swagger.md')]
      before = targets.map { |p| File.binread(p) }
      _, status = Open3.capture2e(env, 'ruby', script, '--check')
      assert status.success?
      stdout, status = Open3.capture2e(env, 'ruby', script, '--stdout')
      assert status.success?
      assert_equal before.first, stdout.b
      assert_equal before, targets.map { |p| File.binread(p) }
      File.binwrite(targets.last, 'stale')
      _, status = Open3.capture2e(env, 'ruby', script, '--check')
      refute status.success?
      assert_equal 'stale', File.binread(targets.last)
    end
  end
end
