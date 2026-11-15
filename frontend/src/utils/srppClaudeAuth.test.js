import test from 'node:test'
import assert from 'node:assert/strict'

import {
  canManageClaudeSubscription,
  claudeAuthFlowStatusLabel,
  claudeAuthLoginStateLabel,
  isClaudeAuthFlowActive,
  isClaudeAuthFlowTerminal,
  isOfficialClaudeAuthorizationUrl,
  isValidClaudeAuthorizationCode,
  normalizeClaudeAuthBootstrap,
  normalizeClaudeAuthFlow,
  safeClaudeAuthMessage,
  shouldPromptClaudeSubscriptionLogin
} from './srppClaudeAuth.js'

const scopes = [
  'org:create_api_key',
  'user:profile',
  'user:inference',
  'user:sessions:claude_code',
  'user:mcp_servers',
  'user:file_upload',
  'user:plugins',
  'user:privacy_consents:write'
]

const opaque = character => character.repeat(43)

function authorizationUrl(overrides = {}) {
  const query = new URLSearchParams({
    code: 'true',
    response_type: 'code',
    redirect_uri: 'https://platform.claude.com/oauth/code/callback',
    client_id: '9d1c250a-e61b-44d9-88ed-5944d1962f5e',
    scope: scopes.join(' '),
    code_challenge: opaque('A'),
    code_challenge_method: 'S256',
    state: opaque('B'),
    ...overrides
  })
  return `https://claude.com/cai/oauth/authorize?${query.toString()}`
}

function localState(overrides = {}) {
  return {
    schemaVersion: 1,
    toolId: 'claude',
    loginState: 'EXPIRED',
    expiresAt: '2000-01-01T00:00:00Z',
    code: 'AI_AUTH_EXPIRED',
    ...overrides
  }
}

function authFlow(overrides = {}) {
  return {
    schemaVersion: 1,
    flowId: opaque('C'),
    toolId: 'claude',
    status: 'WAITING_FOR_CODE',
    authorizationUrl: authorizationUrl(),
    expiresAt: '2099-11-16T00:05:00Z',
    code: null,
    ...overrides
  }
}

test('只有目前 ACTIVE 的 configured-admin 本人可管理 Claude 登入', () => {
  const allowed = {
    configuredAdmin: true,
    active: true,
    impersonating: false,
    effectiveUserId: 1,
    email: 'admin@example.invalid'
  }
  assert.equal(canManageClaudeSubscription(allowed), true)
  assert.equal(canManageClaudeSubscription({ ...allowed, configuredAdmin: false }), false)
  assert.equal(canManageClaudeSubscription({ ...allowed, active: false }), false)
  assert.equal(canManageClaudeSubscription({ ...allowed, impersonating: true }), false)
  assert.equal(canManageClaudeSubscription({ ...allowed, effectiveUserId: null }), false)
  assert.equal(canManageClaudeSubscription({ ...allowed, email: '' }), false)
})

test('只有本機登入狀態 REQUIRED 或 EXPIRED 才自動提示，不讀取費用狀態決定提示', () => {
  assert.equal(shouldPromptClaudeSubscriptionLogin('REQUIRED'), true)
  assert.equal(shouldPromptClaudeSubscriptionLogin('EXPIRED'), true)
  assert.equal(shouldPromptClaudeSubscriptionLogin('VALID'), false)
  assert.equal(shouldPromptClaudeSubscriptionLogin('UNKNOWN'), false)
  assert.equal(shouldPromptClaudeSubscriptionLogin('AUTHENTICATING'), false)
  assert.equal(claudeAuthLoginStateLabel('VALID'), '本機登入有效；費用狀態仍須獨立確認。')
  assert.equal(claudeAuthLoginStateLabel('EXPIRED'), 'Claude 登入已失效。')
})

test('官方授權 URL 僅接受核定 host、path、client、redirect、PKCE 與精確 scope', () => {
  const url = authorizationUrl()
  assert.equal(isOfficialClaudeAuthorizationUrl(url), true)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ orgUUID: '6d8d5e10-8d1e-4b1a-a3d3-30d64612a521' })), true)
  assert.equal(isOfficialClaudeAuthorizationUrl(url.replace('claude.com/', 'claude.ai/')), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(url.replace('/cai/oauth/authorize', '/oauth/authorize')), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(url.replace('https://claude.com/', 'https://user@claude.com/')), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(url.replace('https://claude.com/', 'https://claude.com:443/')), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(`${url}#fragment`), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(`${url}&extra=unexpected`), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(`${url}&state=${opaque('D')}`), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ client_id: 'other-client' })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ redirect_uri: 'https://claude.com/callback' })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ code_challenge_method: 'plain' })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ scope: scopes.slice(1).join(' ') })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ scope: [...scopes, scopes[0]].join(' ') })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(authorizationUrl({ login_hint: 'admin@example.invalid' })), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(`${url}&malformed=%E0%A4%A`), false)
  assert.equal(isOfficialClaudeAuthorizationUrl(`https://claude.com/cai/oauth/authorize?${'x'.repeat(8193)}`), false)
})

test('登入 bootstrap 與 flow 僅接受閉合 DTO、正確工具及短期 opaque 值', () => {
  const bootstrap = {
    schemaVersion: 1,
    csrfToken: opaque('D'),
    login: localState()
  }
  assert.deepEqual(normalizeClaudeAuthBootstrap(bootstrap), bootstrap)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, unexpected: 'field' }), null)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, csrfToken: 'short' }), null)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, login: localState({ toolId: 'codex' }) }), null)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, login: localState({ loginState: 'BROKEN' }) }), null)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, login: localState({ expiresAt: 'not-a-date' }) }), null)
  assert.equal(normalizeClaudeAuthBootstrap({ ...bootstrap, login: localState({ code: 'raw provider output' }) }), null)

  assert.deepEqual(normalizeClaudeAuthFlow(authFlow()), authFlow())
  assert.equal(normalizeClaudeAuthFlow(authFlow({ flowId: 'short' })), null)
  assert.equal(normalizeClaudeAuthFlow(authFlow({ status: 'UNKNOWN' })), null)
  assert.equal(normalizeClaudeAuthFlow(authFlow({ authorizationUrl: 'https://example.invalid/login' })), null)
  assert.equal(normalizeClaudeAuthFlow(authFlow({ status: 'SUCCEEDED', authorizationUrl: authorizationUrl() })), null)
  assert.equal(normalizeClaudeAuthFlow(authFlow({ expiresAt: null })), null)
})

test('授權碼只接受單行 ASCII code#state，提交錯誤碼不會被當成授權密碼', () => {
  assert.equal(isValidClaudeAuthorizationCode('authorization-code#native-state'), true)
  assert.equal(isValidClaudeAuthorizationCode('code#state with-space'), false)
  assert.equal(isValidClaudeAuthorizationCode('code#state\n'), false)
  assert.equal(isValidClaudeAuthorizationCode('#state'), false)
  assert.equal(isValidClaudeAuthorizationCode('code#'), false)
  assert.equal(isValidClaudeAuthorizationCode('code#state#extra'), false)
  assert.equal(isValidClaudeAuthorizationCode(`code#${'x'.repeat(4092)}`), false)
  assert.equal(isValidClaudeAuthorizationCode('password'), false)
  assert.equal(isValidClaudeAuthorizationCode('sk-ant-api-token'), false)
})

test('flow 進行與終態有安全固定文案，未知錯誤不原樣顯示', () => {
  assert.equal(isClaudeAuthFlowActive('WAITING_FOR_URL'), true)
  assert.equal(isClaudeAuthFlowActive('WAITING_FOR_CODE'), true)
  assert.equal(isClaudeAuthFlowActive('VERIFYING'), true)
  assert.equal(isClaudeAuthFlowActive('SUCCEEDED'), false)
  assert.equal(isClaudeAuthFlowTerminal('SUCCEEDED'), true)
  assert.equal(isClaudeAuthFlowTerminal('CANCELLED'), true)
  assert.equal(isClaudeAuthFlowTerminal('WAITING_FOR_CODE'), false)
  assert.equal(claudeAuthFlowStatusLabel('WAITING_FOR_CODE'), '請在官方 Claude 頁面完成授權，再貼上完整授權碼。')
  assert.equal(safeClaudeAuthMessage('AUTH_INVALID_CODE'), '授權碼或狀態不符，請重新開始登入並貼上最新授權碼。')
  assert.equal(safeClaudeAuthMessage('provider said token expired: secret'), 'Claude 登入未完成，請重新開始。')
})


test('矛盾的登入狀態不可被顯示為有效或錯誤啟動登入提示', () => {
  const bootstrap = login => ({ schemaVersion: 1, csrfToken: opaque('D'), login })
  const future = '2099-01-01T00:00:00Z'
  const past = '2000-01-01T00:00:00Z'
  assert.ok(normalizeClaudeAuthBootstrap(bootstrap(localState({ loginState: 'VALID', code: null, expiresAt: future }))))
  for (const invalid of [
    localState({ loginState: 'VALID', code: 'AI_AUTH_EXPIRED', expiresAt: future }),
    localState({ loginState: 'VALID', code: null, expiresAt: null }),
    localState({ loginState: 'VALID', code: null, expiresAt: past }),
    localState({ loginState: 'EXPIRED', expiresAt: future }),
    localState({ loginState: 'AUTHENTICATING', code: null, expiresAt: null }),
    localState({ loginState: 'REQUIRED', code: 'AI_AUTH_REQUIRED', expiresAt: future })
  ]) assert.equal(normalizeClaudeAuthBootstrap(bootstrap(invalid)), null)
})

test('登入成功及等待授權碼的狀態必須與安全碼和官方連結一致', () => {
  assert.ok(normalizeClaudeAuthFlow(authFlow({ status: 'SUCCEEDED', authorizationUrl: null, code: null })))
  assert.ok(normalizeClaudeAuthFlow(authFlow({ status: 'EXPIRED', authorizationUrl: null, code: 'AUTH_TIMEOUT' })))
  for (const invalid of [
    authFlow({ status: 'SUCCEEDED', authorizationUrl: null, code: 'AUTH_FAILED' }),
    authFlow({ authorizationUrl: null }),
    authFlow({ status: 'VERIFYING', authorizationUrl: null, code: 'AUTH_FAILED' }),
    authFlow({ status: 'FAILED', authorizationUrl: null, code: null }),
    authFlow({ status: 'EXPIRED', authorizationUrl: null, code: null })
  ]) assert.equal(normalizeClaudeAuthFlow(invalid), null)
})
