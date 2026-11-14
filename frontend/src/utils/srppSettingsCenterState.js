function sameJsonValue(left, right) {
  if (Object.is(left, right)) return true
  if (left == null || right == null || typeof left !== 'object' || typeof right !== 'object') return false
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) &&
      left.length === right.length && left.every((item, index) => sameJsonValue(item, right[index]))
  }
  const leftKeys = Object.keys(left).sort()
  const rightKeys = Object.keys(right).sort()
  return leftKeys.length === rightKeys.length &&
    leftKeys.every((key, index) => key === rightKeys[index] && sameJsonValue(left[key], right[key]))
}

/** 比較編輯內容與已載入的 server snapshot；只處理 JSON 結構，不做金融計算。 */
export function hasSrppDraftChanges(values, savedValues) {
  return !sameJsonValue(values, savedValues)
}

/** 只有與目前草稿 revision/config 完全相符的 run 才能供發布流程引用。 */
export function isCurrentSrppRun(run, draft) {
  return !!run && !!draft &&
    run.draftId === draft.id &&
    Number(run.draftRevision) === Number(draft.revision) &&
    typeof run.configSha256 === 'string' &&
    run.configSha256 === draft.configSha256 &&
    run.status === 'SUCCEEDED'
}

/** 本機只檢查編輯版本是否一致；正式可發布與否仍由 server publishEligible 決定。 */
export function canPublishSrppRun({ isAdmin, canPublish, draft, run, dirty, publishing = false }) {
  return !!isAdmin && !!canPublish && !!draft && !!run && !dirty && !publishing &&
    isCurrentSrppRun(run, draft) && run.publishEligible === true
}
