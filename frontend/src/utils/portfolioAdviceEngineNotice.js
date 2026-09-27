export const LOCAL_HYBRID_TEMPLATE_NOTICE =
  'local／hybrid 的目標配置類別比例由固定規則模板決定，屬常見經驗法則，未經回測或個人情境驗證，不構成個人化投資建議。股票與信託基金只在相同（assetClass, subClass）群組內，按既有持倉目前市值比例分攤標的層級金額；存款（現金）增碼按存款市值比例分攤，減碼依提領優先序逐筆抽取。不推薦尚未持有的新標的，也不分析個別標的基本面、損益、交易成本或稅務。'

export function portfolioAdviceEngineNotice({ isAdmin, engine, availableEngines }) {
  if (engine !== 'local' && engine !== 'hybrid') return null

  if (!isAdmin) return '如需完整 AI 引擎，請洽系統管理者設定。'

  const llm = (Array.isArray(availableEngines) ? availableEngines : [])
    .find(option => option && option.id === 'llm')
  return llm
    ? `可在上方「分析引擎」選單切換為${llm.label}。`
    : '完整 AI 引擎目前不可用，請檢查引擎設定。'
}
