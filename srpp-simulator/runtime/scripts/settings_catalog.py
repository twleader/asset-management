"""Closed settings protocol; no request-supplied paths or executable expressions."""
from __future__ import annotations
import copy
import datetime as dt
import hashlib
import json
import math
from decimal import Decimal
from pathlib import Path
import rfc8785

ROOT = Path(__file__).resolve().parents[1]
CATALOG_VERSION = 'SRPP_SETTINGS_CATALOG_V2'
BOND_CODES = frozenset({'00865B','00719B','SGOV','00697B','00679B','00751B'})
CASH_CODES = frozenset({'TWD_term_deposits','demand_deposits_and_cash'})

PAYROLL_POINTERS = {
    'monthlySalary':'/employee_profile/monthly_salary',
    'salaryPayday':'/payday_day_of_month',
    'annualRaiseAmount':'/employee_profile/annual_salary_increase/amount',
    'annualRaiseFirstDate':'/employee_profile/annual_salary_increase/first_increase_date',
    'withholdingRate':'/employee_profile/income_tax_withholding/rate',
    'selfPensionRate':'/employee_profile/voluntary_labor_pension/employee_rate',
    'nhiDependents':'/employee_profile/nhi_dependents',
    'preRetirementMonthlyExpense':'/planned_living_costs/monthly_living_expense',
    'bonusEvents':'/bonus_events','recurringBonuses':'/recurring_bonuses',
}

def valid_number(value, low, high, precision=None):
    if type(value) not in (int,float):return False
    try:
        if not math.isfinite(value) or not low <= value <= high:return False
        if precision is not None:
            shifted=Decimal(str(value)).scaleb(precision)
            if shifted != shifted.to_integral_value():return False
        return True
    except (ValueError,OverflowError):return False

def valid_date(value):
    try:return isinstance(value,str) and dt.date.fromisoformat(value).isoformat()==value
    except (ValueError,TypeError):return False

def validate_rows(kind, value, bad):
    limit=32 if kind=='recurringBonuses' else 100
    if not isinstance(value,list) or len(value)>limit:bad('清單筆數超出允許範圍。')
    identities=set()
    for row in value:
        required={'date','name','amount'} if kind=='events' else {'name','month','months'} if kind=='recurringBonuses' else {'name','pay_date','months'}
        optional={'month_by_year'} if kind=='recurringBonuses' else {'festival_date'} if kind=='datedBonuses' else set()
        if not isinstance(row,dict) or not required<=row.keys() or row.keys()-required-optional:bad('清單欄位不符。')
        name=row.get('name')
        if not isinstance(name,str) or not 1<=len(name.strip())<=100:bad('名稱長度須為1至100字。')
        row['name']=name.strip()
        if kind=='events':
            if not valid_date(row['date']) or not valid_number(row['amount'],0,100000000,2):bad('支出日期或金額不合法。')
        else:
            if not valid_number(row['months'],0,24,4):bad('獎金月薪倍數須介於0至24，最多4位小數。')
            if kind=='datedBonuses':
                if not valid_date(row['pay_date']) or ('festival_date' in row and not valid_date(row['festival_date'])):bad('獎金日期不合法。')
                identity=(row['name'],row['pay_date'])
            else:
                if type(row['month']) is not int or not 1<=row['month']<=12:bad('獎金月份須為1至12。')
                exceptions=row.get('month_by_year',{})
                if not isinstance(exceptions,dict) or len(exceptions)>100:bad('年份例外超出允許範圍。')
                for year,month in exceptions.items():
                    if not isinstance(year,str) or len(year)!=4 or not year.isascii() or not year.isdigit() or not 1900<=int(year)<=2200 or type(month) is not int or not 1<=month<=12:bad('年份例外不合法。')
                identity=row['name']
            if identity in identities:bad('獎金名稱／日期重複，不能靜默覆蓋。')
            identities.add(identity)


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
        self.payroll=strict_json((self.root/'assumptions/pre_retirement_payroll.json').read_bytes())
        self.manifest=strict_json((self.root/'assumptions/policy_manifest.json').read_bytes())
        self.base=self.manifest['POLICY_BUNDLE_SHA256']
        self.catalog_hash=file_digest(self.catalog_path)
        self.model_hash=file_digest(self.root/'assumptions/model_inputs.json')
        if self.catalog['catalogVersion']!=CATALOG_VERSION:raise SettingsError('CATALOG_UNSUPPORTED','欄位規約版本不符。',503)
        self.fields=self.catalog['fields']; self.by_id={f['id']:f for f in self.fields}
        if len(self.by_id)!=len(self.fields):raise SettingsError('CATALOG_INVALID','欄位規約重複。',503)
        self.defaults={}
        for f in self.fields:
            source=f.get('source','model')
            if source not in ('model','payroll') or (source=='payroll' and PAYROLL_POINTERS.get(f['id'])!=f['pointer']):raise SettingsError('CATALOG_INVALID','設定來源不在白名單。',503)
            document=self.payroll if source=='payroll' else self.model
            self.defaults[f['id']]=copy.deepcopy(pointer_get(document,f['pointer']) if f['pointer'] else f['defaultAssumption'])
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
            if v is None:
                if f.get('nullable') is True:continue
                bad('不能留空。')
            if not f['editable'] and canonical(v)!=canonical(self.defaults[key]):bad('此來源規則為唯讀。')
            kind=f['type']
            if kind=='date':
                if not valid_date(v):bad('請填合法日期。')
            elif kind=='boolean':
                if type(v) is not bool:bad('必須是布林值。')
            elif kind=='symbols':
                allowed=f['allowedSymbols']
                if not isinstance(v,list) or len(v)>len(allowed) or any(not isinstance(code,str) or not code or code not in allowed for code in v) or len(set(v))!=len(v):bad('標的只能選來源白名單且不可重複。')
            elif kind in ('events','datedBonuses','recurringBonuses'):
                validate_rows(kind,v,bad)
            elif kind in ('bearLadder','bullLadder'):
                if canonical(v)!=canonical(self.defaults[key]):bad('此來源規則為唯讀。')
            elif kind=='weights':
                if not isinstance(v,dict) or set(v)!=set(f['assetKeys']):bad('配置代號必須與正式版本一致。')
                if any(not valid_number(w,0,1) for w in v.values()):bad('每項比例必須介於0與1。')
                if abs(math.fsum(v.values())-1)>1e-9:bad('配置比例合計必須為1。')
            elif kind in ('number','integer'):
                if not valid_number(v,f['min'],f['max'],f.get('precision')):bad('超出允許範圍或小數位數。')
                if kind=='integer' and type(v) is not int:bad('必須是整數。')
            else:bad('不支援的設定型別。')
        v=normalized
        def require(ok,msg):
            if not ok:raise SettingsError('CONFIG_INVALID',msg)
        require(v['careStartAge']<v['planningAge'],'長照起始年齡必須小於規劃終點年齡。')
        require(v['entryK']<v['overboughtK'] and v['entryD']<v['overboughtD'],'進場觀察門檻必須低於對應過熱門檻。')
        require(v['bondPremiumCeiling']<v['bondPremiumPause'],'適宜價格最高溢價必須低於暫停折溢價。')
        require(v['bondDailyLots']<=v['bondWeeklyLots'],'每週額度不得低於每日額度。')
        require(v['riskCautiousTotal']<v['riskDefensiveTotal'],'審慎風險總分必須低於防禦總分。')
        for date_key,amount_key in [('laborPensionReceiptDate','laborPensionReceiptAmount'),('laborInsuranceStartDate','laborInsuranceMonthlyAmount')]:
            require((v[date_key] is None)==(v[amount_key] is None),'給付日期與金額必須同時填入或同時留空。')
        today=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
        require(dt.date.fromisoformat(v['birthDate'])<=today,'生日不得晚於台北今日。')
        require(v['livingReserveFloor']<=v['livingReserveCeiling'],'生活費底線不得超過準備目標。')
        require(v['bearLivingDeploymentCap']<=v['livingReserveCeiling']-v['livingReserveFloor'],'熊市動用生活費不得超過目標與底線之差。')
        require(v['bullEquityFloor']<=v['equityCap'] and v['bearEquityCap']>=v['equityFloor'],'牛熊股票界線與一般部位帶不一致。')
        require(v['minimumBondWeight']+v['bearEquityCap']<=1+1e-12,'債券底線與熊市股票上限合計不得超過100%。')
        require(v['shortEquityPositionCap']<=v['shortEquitySleeveCap'] and v['shortBondPositionCap']<=v['shortBondSleeveCap'],'短線單一部位上限不得高於短線總額上限。')
        require(v['usTacticalBatchCap']<=v['usTacticalCompanyCap']<=v['usTacticalSleeveCap'],'美股單批／單公司／戰術總額上限必須依序不減。')
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
