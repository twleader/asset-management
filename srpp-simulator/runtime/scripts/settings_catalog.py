"""Closed settings protocol; no request-supplied paths or executable expressions."""
from __future__ import annotations
import copy
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import rfc8785

ROOT = Path(__file__).resolve().parents[1]
CATALOG_VERSION = 'SRPP_SETTINGS_CATALOG_V1'
BOND_CODES = frozenset({'00865B','00719B','SGOV','00697B','00679B','00751B'})
CASH_CODES = frozenset({'TWD_term_deposits','demand_deposits_and_cash'})

class SettingsError(ValueError):
    def __init__(self, code, message, status=422):
        super().__init__(message); self.code, self.message, self.status = code, message, status

def canonical(value):
    try: return rfc8785.dumps(value)
    except (ValueError, TypeError, OverflowError) as exc:
        raise SettingsError('INVALID_JSON_NUMBER','JSON 數值不符合 RFC8785。') from exc

def digest(value): return hashlib.sha256(canonical(value)).hexdigest()
def file_digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def strict_json(raw):
    def pairs(items):
        result={}
        for key,value in items:
            if key in result: raise SettingsError('DUPLICATE_MEMBER','JSON 含重複欄位。',400)
            result[key]=value
        return result
    def invalid(_): raise SettingsError('INVALID_JSON_NUMBER','JSON 含非有限數值。',400)
    try:return json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)
    except (UnicodeError,json.JSONDecodeError) as exc:raise SettingsError('INVALID_JSON','無法解析 JSON。',400) from exc

def pointer_get(value,pointer):
    for key in pointer.lstrip('/').split('/'): value=value[key]
    return value

def pointer_set(value,pointer,new):
    parts=pointer.lstrip('/').split('/')
    for key in parts[:-1]:value=value[key]
    value[parts[-1]]=copy.deepcopy(new)

class SettingsContext:
    def __init__(self,root=ROOT):
        self.root=Path(root)
        self.catalog_path=self.root/'assumptions/settings_catalog.json'
        self.catalog=strict_json(self.catalog_path.read_bytes())
        self.model=strict_json((self.root/'assumptions/model_inputs.json').read_bytes())
        self.manifest=strict_json((self.root/'assumptions/policy_manifest.json').read_bytes())
        self.base=self.manifest['POLICY_BUNDLE_SHA256']
        self.catalog_hash=file_digest(self.catalog_path)
        self.model_hash=file_digest(self.root/'assumptions/model_inputs.json')
        if self.catalog['catalogVersion']!=CATALOG_VERSION:raise SettingsError('CATALOG_UNSUPPORTED','欄位規約版本不符。',503)
        self.fields=self.catalog['fields']; self.by_id={f['id']:f for f in self.fields}
        if len(self.by_id)!=len(self.fields):raise SettingsError('CATALOG_INVALID','欄位規約重複。',503)
        self.defaults={f['id']:copy.deepcopy(pointer_get(self.model,f['pointer']) if f['pointer'] else f['defaultAssumption']) for f in self.fields}
        self.normalize(self.defaults)
    def descriptor(self):
        fields=copy.deepcopy(self.fields)
        for f in fields:f['defaultValue']=copy.deepcopy(self.defaults[f['id']])
        return dict(schemaVersion=1,baseBundleSha256=self.base,modelInputsSha256=self.model_hash,catalogVersion=CATALOG_VERSION,catalogSha256=self.catalog_hash,fields=fields,currentValues=copy.deepcopy(self.defaults))
    def normalize(self,values):
        if not isinstance(values,dict) or set(values)-set(self.by_id):raise SettingsError('CONFIG_INVALID','設定含未知欄位。')
        normalized=copy.deepcopy(self.defaults) if hasattr(self,'defaults') else {}
        normalized.update(copy.deepcopy(values))
        for f in self.fields:
            key=f['id'];v=normalized[key];label=f['label']
            def bad(reason):raise SettingsError('CONFIG_INVALID',label+'：'+reason)
            if f['type']=='date':
                try:
                    if not isinstance(v,str) or dt.date.fromisoformat(v).isoformat()!=v:bad('請填合法日期。')
                except (TypeError,ValueError):bad('請填合法日期。')
            elif f['type']=='weights':
                if not isinstance(v,dict) or set(v)!=set(f['assetKeys']):bad('配置代號必須與正式版本一致。')
                if any(type(w) not in (int,float) or not math.isfinite(w) or not 0<=w<=1 for w in v.values()):bad('每項比例必須介於 0 與 1。')
                if abs(math.fsum(v.values())-1)>1e-9:bad('配置比例合計必須為 1。')
            else:
                if type(v) not in (int,float) or not math.isfinite(v):bad('必須是有限數值。')
                if f['type']=='integer' and type(v) is not int:bad('必須是整數。')
                if not f['min']<=v<=f['max']:bad('超出允許範圍。')
        v=normalized
        def require(ok,msg):
            if not ok:raise SettingsError('CONFIG_INVALID',msg)
        require(v['careStartAge']<v['planningAge'],'長照起始年齡必須小於規劃終點年齡。')
        require(v['entryK']<v['overboughtK'] and v['entryD']<v['overboughtD'],'進場觀察門檻必須低於對應過熱門檻。')
        require(v['bondPremiumCeiling']<v['bondPremiumPause'],'適宜價格最高溢價必須低於暫停折溢價。')
        require(v['bondDailyLots']<=v['bondWeeklyLots'],'每週額度不得低於每日額度。')
        require(v['riskCautiousTotal']<v['riskDefensiveTotal'],'審慎風險總分必須低於防禦總分。')
        equity=math.fsum(w for code,w in v['targetWeights'].items() if code not in BOND_CODES|CASH_CODES)
        require(v['equityFloor']<v['equityCap'] and v['equityFloor']<=equity+1e-9<=v['equityCap']+1e-9,'股票目標比例必須介於再平衡下緣與上緣。')
        return normalized
    def config_hash(self,values,base=None):return digest(dict(baseBundleSha256=base or self.base,catalogVersion=CATALOG_VERSION,values=values))
    def changes(self,values):
        return [dict(fieldId=f['id'],label=f['label'],before=copy.deepcopy(self.defaults[f['id']]),after=copy.deepcopy(values[f['id']]),retirementImpact=f['retirementImpact']) for f in self.fields if canonical(values[f['id']])!=canonical(self.defaults[f['id']])]
    def validate(self,request):
        if not isinstance(request,dict) or type(request.get('schemaVersion')) is not int or request.get('schemaVersion')!=1:raise SettingsError('INVALID_REQUEST','設定請求版本不符。',400)
        if request.get('baseBundleSha256')!=self.base:raise SettingsError('BASE_POLICY_CHANGED','正式規則版本已變更，請重新載入。',409)
        values=self.normalize(request.get('values'))
        expected=self.config_hash(values)
        if request.get('configSha256')!=expected:raise SettingsError('CONFIG_HASH_MISMATCH','設定內容與雜湊不符。',409)
        return dict(schemaVersion=1,baseBundleSha256=self.base,catalogVersion=CATALOG_VERSION,catalogSha256=self.catalog_hash,configSha256=expected,valid=True,normalizedValues=values,changes=self.changes(values))
