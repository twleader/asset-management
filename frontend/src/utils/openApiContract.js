const HTTP_METHODS = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'])
const TOP_LEVEL_KEY = /^([A-Za-z][A-Za-z0-9_-]*):/
const PATH_LINE = /^  (\/[^\s:]+):\s*$/
const METHOD_LINE = /^    (get|post|put|patch|delete|head|options|trace):\s*$/

export class OpenApiContractParseError extends Error {
  constructor(message) {
    super(message)
    this.name = 'OpenApiContractParseError'
  }
}

function scalarValue(lines, start, end, key, indent = '  ') {
  const expression = new RegExp(`^${indent}${key}:\\s*(.+?)\\s*$`)
  for (let index = start + 1; index < end; index += 1) {
    const match = lines[index].match(expression)
    if (match) return match[1].replace(/^['"]|['"]$/g, '')
  }
  return null
}

function topLevelSections(lines) {
  const sections = new Map()
  lines.forEach((line, index) => {
    const match = line.match(TOP_LEVEL_KEY)
    if (match && !sections.has(match[1])) sections.set(match[1], index)
  })
  return sections
}

function sectionEnd(sections, start, lineCount) {
  return [...sections.values()].filter(index => index > start).sort((a, b) => a - b)[0] ?? lineCount
}

function parseServers(lines, start, end) {
  const urls = []
  for (let index = start + 1; index < end; index += 1) {
    const match = lines[index].match(/^  - url:\s*(.+?)\s*$/)
    if (match) urls.push(match[1].replace(/^['"]|['"]$/g, ''))
  }
  return urls
}

function operationDetails(lines, start, end) {
  const summary = scalarValue(lines, start, end, 'summary', '      ')
  const operationId = scalarValue(lines, start, end, 'operationId', '      ')
  const responsesStart = lines.findIndex((line, index) =>
    index > start && index < end && line === '      responses:')
  const responseCodes = responsesStart === -1 ? [] : lines
    .slice(responsesStart + 1, end)
    .map(line => line.match(/^        ['"]?([0-9]{3}|default)['"]?:/))
    .filter(Boolean)
    .map(match => match[1])
  return { summary, operationId, responseCodes }
}

/**
 * Parses the constrained OpenAPI structure needed by the documentation view.
 * It deliberately never attempts general YAML deserialization or request execution.
 */
export function parseOpenApiContract(source) {
  if (typeof source !== 'string' || !source.trim()) {
    throw new OpenApiContractParseError('文件內容是空的，無法載入 API 契約。')
  }

  const rawYaml = source.replace(/\r\n/g, '\n')
  const lines = rawYaml.split('\n')
  const sections = topLevelSections(lines)
  const required = ['openapi', 'info', 'servers', 'paths', 'components']
  const missing = required.filter(name => !sections.has(name))
  if (missing.length) {
    throw new OpenApiContractParseError(`文件缺少必要區段：${missing.join('、')}。`)
  }

  const infoStart = sections.get('info')
  const infoEnd = sectionEnd(sections, infoStart, lines.length)
  const title = scalarValue(lines, infoStart, infoEnd, 'title')
  const version = scalarValue(lines, infoStart, infoEnd, 'version')
  const openapi = lines[sections.get('openapi')].replace(/^openapi:\s*/, '').trim()
  const serversStart = sections.get('servers')
  const serverUrls = parseServers(lines, serversStart, sectionEnd(sections, serversStart, lines.length))
  const pathsStart = sections.get('paths')
  const pathsEnd = sections.get('components')

  if (!openapi || !title || !version || !serverUrls.length || pathsEnd <= pathsStart) {
    throw new OpenApiContractParseError('文件結構不完整，無法安全列出 API。')
  }

  const pathEntries = []
  for (let index = pathsStart + 1; index < pathsEnd; index += 1) {
    const pathMatch = lines[index].match(PATH_LINE)
    if (!pathMatch) continue
    pathEntries.push({ path: pathMatch[1], start: index })
  }
  if (!pathEntries.length) {
    throw new OpenApiContractParseError('文件沒有可辨識的 API 路徑。')
  }

  const operations = []
  pathEntries.forEach((pathEntry, pathIndex) => {
    const pathEnd = pathEntries[pathIndex + 1]?.start ?? pathsEnd
    const methods = []
    for (let index = pathEntry.start + 1; index < pathEnd; index += 1) {
      const methodMatch = lines[index].match(METHOD_LINE)
      if (methodMatch && HTTP_METHODS.has(methodMatch[1])) {
        methods.push({ method: methodMatch[1], start: index })
      }
    }
    methods.forEach((methodEntry, methodIndex) => {
      const operationEnd = methods[methodIndex + 1]?.start ?? pathEnd
      const details = operationDetails(lines, methodEntry.start, operationEnd)
      if (!details.summary) {
        throw new OpenApiContractParseError(`路徑 ${pathEntry.path} 的 ${methodEntry.method.toUpperCase()} 缺少摘要。`)
      }
      operations.push({
        id: `${methodEntry.method}:${pathEntry.path}`,
        method: methodEntry.method,
        path: pathEntry.path,
        fragment: [lines[pathEntry.start], ...lines.slice(methodEntry.start, operationEnd)].join('\n'),
        ...details
      })
    })
  })

  if (!operations.length) {
    throw new OpenApiContractParseError('文件沒有可辨識的 API operation。')
  }

  return { openapi, title, version, serverUrls, operations, rawYaml }
}
