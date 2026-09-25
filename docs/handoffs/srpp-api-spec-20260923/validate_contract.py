#!/usr/bin/env python3
"""Validate this handoff package only. Never calls APIs or edits SRPP."""
from pathlib import Path
from decimal import Decimal
from datetime import datetime
from copy import deepcopy
import hashlib,json,re
import yaml,rfc8785
from jsonschema import Draft202012Validator,FormatChecker,ValidationError
from openapi_spec_validator import validate_spec

ROOT=Path(__file__).resolve().parent
SPEC=json.loads((ROOT/'openapi.json').read_text())
SCHEMAS=SPEC['components']['schemas']
CHECKS={'snapshot_deposits','live_deposits','snapshot_funds','live_funds',
        'snapshot_stocks','live_stocks','snapshot_total','live_total'}
def digest(value): return hashlib.sha256(rfc8785.dumps(value)).hexdigest()
def body_digest(body): return hashlib.sha256(body.encode('utf-8')).hexdigest()
def schema_validate(value,name):
    schema={'$schema':'https://json-schema.org/draft/2020-12/schema',
            '$ref':'#/components/schemas/Srpp'+name,'components':{'schemas':SCHEMAS}}
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(value)
def objects(value):
    if isinstance(value,dict):
        yield value
        for item in value.values():yield from objects(item)
    elif isinstance(value,list):
        for item in value:yield from objects(item)
def dec(metric):return None if metric['value'] is None else Decimal(metric['value'])
def unique(values):assert len(values)==len(set(values)), 'duplicate identity'
def sorted_unique(values):unique(values);assert values==sorted(values),'not canonical order'
def reject_constant(value):raise ValueError('non-finite JSON '+value)
def no_duplicate_keys(pairs):
    out={}
    for key,value in pairs:
        if key in out:raise ValueError('duplicate JSON key')
        out[key]=value
    return out
def check(value,evidence):
    if value.get('kind')=='EVIDENCE':
        schema_validate(value,'EvidenceResponse')
        assert body_digest(value['body'])==value['bodySha256'],'evidence body hash differs'
        json.loads(value['body'],parse_constant=reject_constant,object_pairs_hook=no_duplicate_keys)
        return
    if value.get('kind')!='SUMMARY':
        schema_validate(value,'Problem');return
    schema_validate(value,'SummaryResponse')
    c=value['context'];assert digest(c)==value['contextContentSha256'],'context hash differs'
    sources={s['sourceId']:s for s in c['sources']}
    sorted_unique([s['sourceId'] for s in c['sources']])
    assert {'ASSETS','POLICY','CALENDAR'}<={s['kind'] for s in c['sources'] if s['state']=='AVAILABLE'}
    available={sid for sid,s in sources.items() if s['state']=='AVAILABLE'}
    for s in c['sources']:
        if s['state']=='AVAILABLE':
            e=evidence[(c['packageId'],s['sourceId'])]
            check(e,evidence)
            assert e['bodySha256']==s['bodySha256'],'source/evidence hash differs'
            assert datetime.fromisoformat(s['dataAsOf'])<=datetime.fromisoformat(c['dataCutoffAt'])
            assert datetime.fromisoformat(s['dataAsOf'])<=datetime.fromisoformat(s['capturedAt'])
            assert datetime.fromisoformat(s['capturedAt'])<=datetime.fromisoformat(c['generatedAt'])
    assert datetime.fromisoformat(c['dataCutoffAt'])<=datetime.fromisoformat(c['generatedAt'])
    assert datetime.fromisoformat(c['generatedAt'])<=datetime.fromisoformat(value['freshness']['checkedAt'])
    for row in objects(c['modules']):
        if 'sourceIds' in row:
            sorted_unique(row['sourceIds'])
            assert set(row['sourceIds'])<=available,'missing source reference'
    for name,module in c['modules'].items():
        if module['status']=='COMPLETE':
            assert all(o.get('quality')!='UNAVAILABLE' for o in objects(module))
            assert all(o.get('status') not in ('PARTIAL','UNAVAILABLE') for o in objects(module['data']))
            assert not module['data'].get('unmappedHoldingIds',[])
            assert not module['data'].get('missingIncomeRowIds',[])
    complete=all(m['status']=='COMPLETE' for m in c['modules'].values())
    assert c['coverage']==('COMPLETE' if complete else 'PARTIAL'),'coverage overclaim'
    a=c['modules']['assets']
    assert a['status']!='UNAVAILABLE' and a['data'] is not None,'cannot publish without assets'
    rows=a['data']['checks'];assert {x['name'] for x in rows}==CHECKS
    unique([r['name'] for r in rows])
    for row in rows:
        actual,reported=Decimal(row['detailTwd']),Decimal(row['reportedTwd'])
        tol=max(Decimal('.01'),max(abs(actual),abs(reported))*Decimal('.0001'))
        assert Decimal(row['toleranceTwd'])==tol
        assert Decimal(row['differenceTwd'])==actual-reported
        assert abs(actual-reported)<=tol
    for row in a['data']['depositGroups']:unique(row['sourceRowIds'])
    allocation=c['modules']['allocation']['data']
    if allocation:
        denominator=dec(allocation['denominatorTwd']);assert denominator>0
        assert denominator==dec(a['data']['liveTotalAssets'])
        sorted_unique([r['assetKey'] for r in allocation['rows']])
        for row in allocation['rows']:
            exposure,current,target,gap,valuegap=[dec(row[k]) for k in
                ('exposureTwd','currentWeight','targetWeight','gapWeight','gapValueTwd')]
            # Fixtures use exactly representable ratios. Production rounding is formula-version-specific.
            if exposure is not None and current is not None:assert current==exposure/denominator
            if target is not None:
                assert 0<=target<=1
                if current is not None and gap is not None:assert gap==target-current
                if exposure is not None and valuegap is not None:assert valuegap==target*denominator-exposure
    income=c['modules']['cashIncome']['data']
    if income:
        gross=[dec(income[k]) for k in ['stockAndEtfDistributions','fundDistributions','depositInterest','sourceAccruedAnnualIncome']]
        if all(x is not None for x in gross):assert sum(gross[:3])==gross[3]
        source,reinvested,spendable=[dec(income[k]) for k in ['sourceAccruedAnnualIncome','permanentTermInterestReinvested','spendableAnnualGross']]
        if None not in (source,reinvested,spendable):assert spendable==source-reinvested
        taxes=[dec(income[k]) for k in ['taiwanIncomeTaxOrRefund','usWithholding','additionalBasicTax','supplementaryNhi']]
        net=dec(income['afterAllTaxAnnualCashIncome'])
        if spendable is not None and net is not None and all(t is not None for t in taxes):
            assert net==spendable-sum(taxes),'net income arithmetic'
    funding=c['modules']['funding']['data']
    if funding:
        total,floor,room=[dec(funding[k]) for k in
                          ['twdTotalIncludingNegativeTransit','totalTwdDepositFloorNominal','headroomAboveTotalFloor']]
        if None not in (total,floor,room):assert room==max(total-floor,0),'headroom arithmetic'
        term,termfloor=dec(funding['twdTermDeposits']),dec(funding['permanentTermFloorNominal'])
        if term is not None and termfloor is not None and funding['termFloorMet'] is not None:
            assert funding['termFloorMet']==(term>=termfloor)
        if funding['calculationDate']!=c['tradingDate']:
            assert c['modules']['funding']['status']=='PARTIAL'
            assert 'CALCULATION_DATE_MISMATCH' in c['modules']['funding']['reasonCodes']
    technicals=c['modules']['completedTechnicals']['data']
    if technicals:
        assert technicals['requiredCompletedSession']<c['tradingDate']
        unique([(r['market'],r['symbol']) for r in technicals['rows']])
        for row in technicals['rows']:
            for key in ['kdLastThreeCompletedSessions','adjustedClosesLast20']:
                dates=[x['date'] for x in row[key]];sorted_unique(dates)
                assert all(d<=row['completedSession'] for d in dates)
            if row['status']=='COMPLETE':
                assert row['completedSession']==technicals['requiredCompletedSession']
                assert len(row['kdLastThreeCompletedSessions'])==3
                assert len(row['adjustedClosesLast20'])==20
                assert row['corporateActionEvidenceComplete'] is True
                assert row['adjustmentBasis']=='VERIFIED_CASH_ADJUSTED'
                for bar in row['kdLastThreeCompletedSessions']:
                    assert all(v is not None and 0<=Decimal(v)<=100 for v in [bar['k'],bar['d']])
                assert all(Decimal(b['close'])>0 for b in row['adjustedClosesLast20'])
                assert dec(row['highOf20AdjustedCloses'])==max(Decimal(b['close']) for b in row['adjustedClosesLast20'])
    assert set(value['freshness']['changedSourceIds'])<=set(sources)
def mutate(value,path,new):
    out=deepcopy(value);target=out
    for key in path[:-1]:target=target[key]
    target[path[-1]]=new
    if 'context' in out:out['contextContentSha256']=digest(out['context'])
    return out
def main():
    validate_spec(SPEC)
    assert yaml.safe_load((ROOT/'openapi.yaml').read_text())==SPEC
    assert set(SPEC['paths'])=={'/api/public/srpp/daily-context'}
    assert set(SPEC['paths']['/api/public/srpp/daily-context'])=={'get'}
    examples={p.name:json.loads(p.read_text()) for p in (ROOT/'examples').glob('*.json')}
    evidence={(v['packageId'],v['sourceId']):v for v in examples.values() if v.get('kind')=='EVIDENCE'}
    for v in examples.values():check(v,evidence)
    partial=examples['summary-partial.json'];full=examples['summary-complete.json']
    variants=[
      ('unknown property',mutate(full,['context','extra'],True)),
      ('trade authorization',mutate(full,['context','tradingAuthorized'],True)),
      ('boolean money',mutate(full,['context','modules','assets','data','liveTotalAssets','value'],True)),
      ('NaN money',mutate(full,['context','modules','assets','data','liveTotalAssets','value'],'NaN')),
      ('negative zero',mutate(full,['context','modules','assets','data','liveTotalAssets','value'],'-0')),
      ('trailing decimal zero',mutate(full,['context','modules','assets','data','liveTotalAssets','value'],'5000000.0')),
      ('null marked exact',mutate(full,['context','modules','assets','data','liveTotalAssets','value'],None)),
      ('money wrong unit',mutate(full,['context','modules','assets','data','liveTotalAssets','unit'],'RATIO')),
      ('false complete coverage',mutate(partial,['context','coverage'],'COMPLETE')),
      ('false complete income',mutate(partial,['context','modules','cashIncome','status'],'COMPLETE')),
      ('bad source reference',mutate(full,['context','modules','assets','sourceIds'],['missing'])),
      ('duplicate reconciliation',mutate(full,['context','modules','assets','data','checks',0,'name'],'live_deposits')),
      ('wrong net arithmetic',mutate(full,['context','modules','cashIncome','data','afterAllTaxAnnualCashIncome','value'],'74001')),
      ('wrong funding arithmetic',mutate(full,['context','modules','funding','data','headroomAboveTotalFloor','value'],'650001')),
      ('wrong allocation',mutate(full,['context','modules','allocation','data','rows',0,'gapValueTwd','value'],'1')),
      ('current marked changed',mutate(full,['freshness','changedSourceIds'],['assets'])),
      ('future generated time',mutate(full,['context','generatedAt'],'2026-09-25T09:05:01+08:00')),
      ('duplicate KD date',mutate(full,['context','modules','completedTechnicals','data','rows',0,'kdLastThreeCompletedSessions',0,'date'],'2026-09-22')),
      ('intraday candle',mutate(full,['context','modules','completedTechnicals','data','rows',0,'adjustedClosesLast20',19,'date'],'2026-09-24')),
      ('unverified corporate actions',mutate(full,['context','modules','completedTechnicals','data','rows',0,'corporateActionEvidenceComplete'],False)),
      ('source evidence mismatch',mutate(full,['context','sources',0,'bodySha256'],'0'*64)),
      ('unknown body fields',mutate(examples['evidence-assets.json'],['extra'],True))
    ]
    bad_hash=deepcopy(full);bad_hash['contextContentSha256']='0'*64
    variants.append(('context tampering',bad_hash))
    bad_body=deepcopy(examples['evidence-assets.json']);bad_body['body']+=' '
    variants.append(('evidence tampering',bad_body))
    passed=[]
    for name,value in variants:
        try:check(value,evidence)
        except (AssertionError,ValueError,ValidationError):
            # A validator exception is expected for each malformed fixture.
            passed.append(name)
        else:raise AssertionError('invalid fixture accepted: '+name)
    result={'status':'PASS','scope':'OpenAPI/schema/synthetic-fixture validation only; no live API or SRPP changes',
            'openapi':'3.1.0','schema_count':len(SCHEMAS),'positive_examples':len(examples),
            'negative_cases_rejected':len(passed),'negative_case_names':passed,
            'yaml_json_equal':True,'fixture_context_hash':full['contextContentSha256'],
            'production_deployed':False,'calculator_equivalence_verified':False,
            'srpp_integration_enabled':False}
    (ROOT/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
