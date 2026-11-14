"""Current V2 digest; immutable V1 receipts keep their original verifier."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

CODES=('fed','geopolitics','oil','taiwan_politics','us_taiwan_inflation','semiconductor_cycle_and_advanced_process')
MAXIMA=(2,3,2,3,2,3)

def rubric_v2(model):
    rule=model['execution_policy']['risk_score_decision_policy']
    if rule.get('rubric_schema')!='SRPP_RISK_RUBRIC_V2':raise ValueError('RISK_RUBRIC_VERSION_MISMATCH')
    values=[rule[k] for k in ('cautious_total_score','defensive_category_score','defensive_total_score','minimum_evaluated_categories')]
    if (any(type(v) is not int for v in values) or not 1<=values[0]<values[2]<=15 or values[1]!=3 or values[3]!=4):raise ValueError('RISK_RUBRIC_THRESHOLDS_INVALID')
    return dict(schema='SRPP_RISK_RUBRIC_V2',categories=[dict(code=c,max=m) for c,m in zip(CODES,MAXIMA)],decision=dict(cautiousTotalScore=values[0],defensiveCategoryScore=values[1],defensiveTotalScore=values[2],minimumEvaluatedCategories=values[3]))
def rubric_sha256(model):
    # This protocol has ASCII keys and safe integers only, the exact RFC8785 subset.
    return hashlib.sha256(json.dumps(rubric_v2(model),sort_keys=True,separators=(',',':')).encode()).hexdigest()
def verify_current_receipt(response,model):
    if response.get('rubricSha256')!=rubric_sha256(model):raise ValueError('RISK_RUBRIC_HASH_MISMATCH')
    return True
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('action',nargs='?',choices=('digest','verify'),default='digest');p.add_argument('--receipt',type=Path);args=p.parse_args()
    root=Path(__file__).resolve().parents[1];m=json.loads((root/'assumptions/model_inputs.json').read_text())
    if args.action=='verify':
        if args.receipt is None:raise SystemExit('--receipt is required')
        import policy_bundle
        proof=policy_bundle.verify(root,purpose='daily',consumer='Codex');response=json.loads(args.receipt.read_text())
        if response.get('identity',{}).get('policyBundleSha256')!=proof['POLICY_BUNDLE_SHA256']:raise ValueError('RISK_RECEIPT_POLICY_MISMATCH')
        verify_current_receipt(response,m)
        print(json.dumps(dict(status='PASS',scope='CURRENT_POLICY_AND_RUBRIC_IDENTITY_ONLY',rubricSha256=rubric_sha256(m))))
    else:print(rubric_sha256(m))
