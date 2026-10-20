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
    assert_equal 27, union['paths'].values.sum(&:length)
    assert_includes content, '| 契約版本 | `1.26.0` |'
    assert_includes content, '| AI 流程觀測契約版本 | `1.3.0` |'
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

  def test_default_and_asset_only_never_touch_srpp_and_check_is_read_only
    Dir.mktmpdir do |folder|
      root = File.join(folder, 'isolated')
      FileUtils.mkdir_p(File.join(root, 'scripts'))
      FileUtils.mkdir_p(File.join(root, 'docs/openapi'))
      FileUtils.cp(__dir__ + '/../render-9090-openapi-docs.rb', File.join(root, 'scripts'))
      [OPENAPI, OBSERVATION_OPENAPI].each { |source| FileUtils.cp(source, File.join(root, 'docs/openapi')) }
      script = File.join(root, 'scripts/render-9090-openapi-docs.rb')
      srpp = File.join(folder, 'SRPP-primary')
      FileUtils.mkdir_p(File.join(srpp, 'docs'))
      mirror = File.join(srpp, 'docs/9090 Port API Swagger.md')
      File.binwrite(mirror, 'primary sentinel')
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
      assert_equal 'primary sentinel', File.binread(mirror)
      output, status = Open3.capture2e(env, 'ruby', script, '--asset-only')
      assert status.success?, output
      assert_equal 'primary sentinel', File.binread(mirror)
      File.binwrite(targets.last, 'stale')
      _, status = Open3.capture2e(env, 'ruby', script, '--check')
      refute status.success?
      assert_equal 'stale', File.binread(targets.last)
    end
  end

  def test_primary_main_unmanaged_and_symlink_srpp_roots_reject_before_any_asset_write
    Dir.mktmpdir do |folder|
      root = File.join(folder, 'isolated')
      FileUtils.mkdir_p(File.join(root, 'scripts'))
      FileUtils.mkdir_p(File.join(root, 'docs/openapi'))
      FileUtils.cp(__dir__ + '/../render-9090-openapi-docs.rb', File.join(root, 'scripts'))
      [OPENAPI, OBSERVATION_OPENAPI].each { |source| FileUtils.cp(source, File.join(root, 'docs/openapi')) }
      script = File.join(root, 'scripts/render-9090-openapi-docs.rb')
      asset_targets = [File.join(root, 'docs/openapi/9090-api-swagger.md'), File.join(root, 'backend/src/main/resources/srpp/9090-api-swagger.md')]
      FileUtils.mkdir_p(File.dirname(asset_targets.last))
      asset_targets.each { |target| File.binwrite(target, "asset sentinel #{target}") }

      primary = File.join(folder, 'primary')
      FileUtils.mkdir_p(File.join(primary, '.git'))
      unmanaged = File.join(folder, 'unmanaged')
      FileUtils.mkdir_p(unmanaged)
      symlink = File.join(folder, 'symlink')
      File.symlink(unmanaged, symlink)
      main_worktree = File.join(folder, 'main-worktree')
      repo = File.join(folder, 'managed-primary')
      git!('init', '--initial-branch=trunk', repo)
      git!('-C', repo, 'config', 'user.email', 'test@example.invalid')
      git!('-C', repo, 'config', 'user.name', 'renderer test')
      File.write(File.join(repo, 'README'), "fixture\n")
      git!('-C', repo, 'add', 'README')
      git!('-C', repo, 'commit', '-m', 'fixture')
      git!('-C', repo, 'branch', 'main')
      git!('-C', repo, 'worktree', 'add', main_worktree, 'main')

      [primary, unmanaged, symlink, main_worktree].each do |bad_root|
        before = asset_targets.to_h { |target| [target, File.binread(target)] }
        output, status = Open3.capture2e('ruby', script, '--srpp-root', bad_root)
        refute status.success?, "#{bad_root}: #{output}"
        assert_equal before, asset_targets.to_h { |target| [target, File.binread(target)] }
      end
    end
  end

  def test_explicit_clean_feature_worktree_can_write_then_check_its_intended_dirty_mirror
    Dir.mktmpdir('renderer-feature-', ROOT) do |folder|
      root = File.join(folder, 'isolated')
      FileUtils.mkdir_p(File.join(root, 'scripts'))
      FileUtils.mkdir_p(File.join(root, 'docs/openapi'))
      FileUtils.cp(__dir__ + '/../render-9090-openapi-docs.rb', File.join(root, 'scripts'))
      [OPENAPI, OBSERVATION_OPENAPI].each { |source| FileUtils.cp(source, File.join(root, 'docs/openapi')) }
      script = File.join(root, 'scripts/render-9090-openapi-docs.rb')
      primary = File.join(folder, 'primary')
      feature = File.join(folder, 'feature')
      git!('init', '--initial-branch=trunk', primary)
      git!('-C', primary, 'config', 'user.email', 'test@example.invalid')
      git!('-C', primary, 'config', 'user.name', 'renderer test')
      FileUtils.mkdir_p(File.join(primary, 'docs'))
      File.write(File.join(primary, 'docs/9090 Port API Swagger.md'), "old mirror\n")
      git!('-C', primary, 'add', 'docs/9090 Port API Swagger.md')
      git!('-C', primary, 'commit', '-m', 'fixture')
      git!('-C', primary, 'worktree', 'add', '-b', 'task504-choice', feature)

      output, status = Open3.capture2e('ruby', script, '--srpp-root', feature)
      assert status.success?, output
      target = File.join(feature, 'docs/9090 Port API Swagger.md')
      asset = File.join(root, 'docs/openapi/9090-api-swagger.md')
      assert_equal File.binread(asset), File.binread(target)
      refute Open3.capture2e('git', '-C', feature, 'status', '--porcelain').first.empty?
      output, status = Open3.capture2e('ruby', script, '--check', '--srpp-root', feature)
      assert status.success?, output
    end
  end

  private

  def git!(*args)
    output, status = Open3.capture2e('git', *args)
    assert status.success?, output
  end
end
