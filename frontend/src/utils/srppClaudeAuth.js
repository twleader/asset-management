const CLAUDE_TOOL_ID = 'claude'
const CLAUDE_AUTHORIZATION_SCOPES = Object.freeze([
  'org:create_api_key',
  'user:profile',
  'user:inference',
  'user:sessions:claude_code',
  'user:mcp_servers',
  'user:file_upload',
  'user:plugins',
  'user:privacy_consents:write'
])

const SAFE_AUTH_CODE_MESSAGES = Object.freeze({
  AUTH_BUSY: 'Claude 登入作業目前無法開始，請稍後再試。',
  AUTH_FAILED: 'Claude 登入未完成，請重新開始。',
  AUTH_TIMEOUT: 'Claude 登入已逾時，請重新開始。',
  AUTH_INVALID_CODE: '授權碼或狀態不符，請重新開始登入並貼上最新授權碼。',
  AUTH_SESSION_NOT_FOUND: '登入流程已失效，請重新開始。',
  AI_INVALID_REQUEST: '登入請求無效，請重新開始。',
  AI_INVALID_SELECTION: 'Claude 工具資料已更新，請重新載入後再試。',
  AI_AUTH_REQUIRED: 'Claude 尚未完成登入，請使用下方登入流程。',
  AI_AUTH_EXPIRED: 'Claude 登入已失效，請使用下方登入流程。',
  AI_AUTH_IN_PROGRESS: 'Claude 登入正在進行，請先完成目前登入流程。',
  AI_UNAUTHORIZED: '只有主要管理者本人可執行 Claude 登入。',
  AI_TOOL_UNAVAILABLE: 'Claude 登入功能目前無法使用，請稍後再試。'
})

const LOGIN_STATES = new Set(['VALID', 'REQUIRED', 'EXPIRED', 'UNKNOWN', 'AUTHENTICATING'])
const FLOW_STATUSES = new Set([
  'WAITING_FOR_URL', 'WAITING_FOR_CODE', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'
])
const SAFE_AUTH_CODES = new Set(Object.keys(SAFE_AUTH_CODE_MESSAGES))

function hasExactKeys(value, expected) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === expected.length && expected.every(key => Object.hasOwn(value, key))
}

function isText(value) {
  return typeof value === 'string'
}

function isToolId(value) {
  return isText(value) && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(value)
}

function isOpaque256(value) {
  return isText(value) && /^[A-Za-z0-9_-]{43}$/.test(value)
}

function isUtcInstant(value) {
  return isText(value) && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
}

function decodeQueryPart(value) {
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '))
  } catch {
    return null
  }
}

function parseStrictQuery(rawQuery) {
  if (!rawQuery) return null
  const result = new Map()
  for (const part of rawQuery.split('&')) {
    if (!part) return null
    const delimiter = part.indexOf('=')
    if (delimiter < 1) return null
    const key = decodeQueryPart(part.slice(0, delimiter))
    const value = decodeQueryPart(part.slice(delimiter + 1))
    if (!key || value == null || result.has(key)) return null
    result.set(key, value)
  }
  return result
}

export function isOfficialClaudeAuthorizationUrl(value) {
  if (!isText(value) || value.length > 8192 || !/^https:\/\/claude\.com\//.test(value)) return false
  let url
  try {
    url = new URL(value)
  } catch {
    return false
  }
  if (url.protocol !== 'https:' || url.hostname !== 'claude.com' || url.port || url.username || url.password ||
    url.pathname !== '/cai/oauth/authorize' || url.hash) return false

  const query = parseStrictQuery(url.search.slice(1))
  if (!query) return false
  const required = new Set([
    'code', 'response_type', 'redirect_uri', 'client_id', 'scope',
    'code_challenge', 'code_challenge_method', 'state'
  ])
  if ([...required].some(key => !query.has(key)) ||
    [...query.keys()].some(key => !required.has(key) && key !== 'orgUUID')) return false

  if (query.get('code') !== 'true' || query.get('response_type') !== 'code' ||
    query.get('redirect_uri') !== 'https://platform.claude.com/oauth/code/callback' ||
    query.get('client_id') !== '9d1c250a-e61b-44d9-88ed-5944d1962f5e' ||
    query.get('code_challenge_method') !== 'S256' ||
    !/^[A-Za-z0-9_-]{43}$/.test(query.get('code_challenge')) ||
    !/^[A-Za-z0-9_-]{16,256}$/.test(query.get('state'))) return false

  const scopes = query.get('scope').split(' ')
  if (scopes.length !== CLAUDE_AUTHORIZATION_SCOPES.length ||
    new Set(scopes).size !== scopes.length ||
    CLAUDE_AUTHORIZATION_SCOPES.some(scope => !scopes.includes(scope))) return false

  return !query.has('orgUUID') || /^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$/.test(query.get('orgUUID'))
}

export function isValidClaudeAuthorizationCode(value) {
  return isText(value) && value.length <= 4096 && /^[!-~]+#[!-~]+$/.test(value) &&
    value.indexOf('#') === value.lastIndexOf('#')
}

export function canManageClaudeSubscription({ configuredAdmin, active, impersonating, effectiveUserId, email } = {}) {
  return configuredAdmin === true && active === true && impersonating !== true &&
    (typeof effectiveUserId === 'number' ? Number.isSafeInteger(effectiveUserId) && effectiveUserId > 0
      : isText(effectiveUserId) && /^[1-9]\d*$/.test(effectiveUserId)) &&
    isText(email) && !!email.trim()
}

export function shouldPromptClaudeSubscriptionLogin(loginState) {
  return loginState === 'REQUIRED' || loginState === 'EXPIRED'
}

export function isClaudeAuthFlowActive(status) {
  return status === 'WAITING_FOR_URL' || status === 'WAITING_FOR_CODE' || status === 'VERIFYING'
}

export function isClaudeAuthFlowTerminal(status) {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED'
}

export function safeClaudeAuthMessage(code) {
  return typeof code === 'string' && Object.hasOwn(SAFE_AUTH_CODE_MESSAGES, code)
    ? SAFE_AUTH_CODE_MESSAGES[code]
    : 'Claude 登入未完成，請重新開始。'
}

export function claudeAuthLoginStateLabel(state) {
  return ({
    VALID: '本機登入有效；費用狀態仍須獨立確認。',
    REQUIRED: 'Claude 尚未登入。',
    EXPIRED: 'Claude 登入已失效。',
    UNKNOWN: '目前無法確認 Claude 登入狀態。',
    AUTHENTICATING: 'Claude 登入正在進行。'
  })[state] || '目前無法確認 Claude 登入狀態。'
}

export function claudeAuthFlowStatusLabel(status) {
  return ({
    WAITING_FOR_URL: '正在準備官方登入頁面。',
    WAITING_FOR_CODE: '請在官方 Claude 頁面完成授權，再貼上完整授權碼。',
    VERIFYING: '正在確認 Claude 登入狀態。',
    SUCCEEDED: 'Claude 登入已完成；正在更新工具狀態。',
    FAILED: 'Claude 登入未完成，請重新開始。',
    CANCELLED: '已取消 Claude 登入。',
    EXPIRED: 'Claude 登入已逾時，請重新開始。'
  })[status] || 'Claude 登入未完成，請重新開始。'
}

function isConsistentLocalState(value) {
  const expires = value.expiresAt === null ? null : Date.parse(value.expiresAt)
  switch (value.loginState) {
    case 'VALID': return value.code === null && expires !== null && expires > Date.now()
    case 'EXPIRED': return value.code === 'AI_AUTH_EXPIRED' && expires !== null && expires <= Date.now()
    case 'AUTHENTICATING': return value.code === 'AI_AUTH_IN_PROGRESS' && expires === null
    case 'REQUIRED':
    case 'UNKNOWN': return value.code === 'AI_AUTH_REQUIRED' && expires === null
    default: return false
  }
}

function isConsistentFlow(value) {
  if ((value.status === 'WAITING_FOR_CODE') !== (value.authorizationUrl !== null)) return false
  if (value.status === 'FAILED') return value.code !== null
  if (value.status === 'EXPIRED') return value.code === 'AUTH_TIMEOUT'
  return value.code === null
}

function normalizeLocalState(value, expectedToolId) {
  if (!hasExactKeys(value, ['schemaVersion', 'toolId', 'loginState', 'expiresAt', 'code']) ||
    value.schemaVersion !== 1 || value.toolId !== expectedToolId || !LOGIN_STATES.has(value.loginState) ||
    !(value.expiresAt === null || isUtcInstant(value.expiresAt)) ||
    !(value.code === null || SAFE_AUTH_CODES.has(value.code)) || !isConsistentLocalState(value)) return null
  return {
    schemaVersion: 1,
    toolId: value.toolId,
    loginState: value.loginState,
    expiresAt: value.expiresAt,
    code: value.code
  }
}

export function normalizeClaudeAuthBootstrap(value, expectedToolId = CLAUDE_TOOL_ID) {
  if (!hasExactKeys(value, ['schemaVersion', 'csrfToken', 'login']) || value.schemaVersion !== 1 ||
    !isOpaque256(value.csrfToken) || !isToolId(expectedToolId)) return null
  const login = normalizeLocalState(value.login, expectedToolId)
  return login ? { schemaVersion: 1, csrfToken: value.csrfToken, login } : null
}

export function normalizeClaudeAuthFlow(value, expectedToolId = CLAUDE_TOOL_ID) {
  if (!hasExactKeys(value, ['schemaVersion', 'flowId', 'toolId', 'status', 'authorizationUrl', 'expiresAt', 'code']) ||
    value.schemaVersion !== 1 || !isOpaque256(value.flowId) || value.toolId !== expectedToolId ||
    !FLOW_STATUSES.has(value.status) || !isUtcInstant(value.expiresAt) ||
    !(value.authorizationUrl === null || isOfficialClaudeAuthorizationUrl(value.authorizationUrl)) ||
    !(value.code === null || SAFE_AUTH_CODES.has(value.code)) ||
    !isConsistentFlow(value)) return null
  return {
    schemaVersion: 1,
    flowId: value.flowId,
    toolId: value.toolId,
    status: value.status,
    authorizationUrl: value.authorizationUrl,
    expiresAt: value.expiresAt,
    code: value.code
  }
}
