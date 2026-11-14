#!/usr/bin/env python3
"""Task583 audited USD native FinMind spot import. Default is offline dry-run; --apply writes one transaction."""
import argparse, datetime as dt, decimal, hashlib, json, pathlib, re, subprocess, urllib.parse
from zoneinfo import ZoneInfo
D=decimal.Decimal

def sha(value): return hashlib.sha256(value.encode('utf-8')).hexdigest()
def canonical(value): return format(D(str(value)).normalize(),'f')
def member(rawsha,day,buy,sell): return sha('FX_SPOT_MEMBER_V1\n'+rawsha+'\nUSD\n'+day+'\n'+canonical(buy)+'\n'+canonical(sell))
def literal(value): return "'"+str(value).replace("'","''")+"'"
def inspect(raw,receipt,start,end):
    rawsha=hashlib.sha256(raw).hexdigest()
    if rawsha!=receipt['rawSha256']: raise ValueError('RAW_SHA_MISMATCH')
    url=urllib.parse.urlsplit(receipt['url']);q=urllib.parse.parse_qs(url.query)
    if url.scheme!='https' or url.netloc!='api.finmindtrade.com' or url.path!='/api/v4/data' or set(q)!={'dataset','data_id','start_date','end_date'} or q.get('dataset')!=['TaiwanExchangeRate'] or q.get('data_id')!=['USD'] or any(len(v)!=1 for v in q.values()) or dt.date.fromisoformat(q['start_date'][0])>start or dt.date.fromisoformat(q['end_date'][0])<end: raise ValueError('SOURCE_URL_INVALID')
    captured=dt.datetime.fromisoformat(receipt['capturedAt'])
    if captured.tzinfo is None or captured.astimezone(ZoneInfo('Asia/Taipei')).date()<end or captured>dt.datetime.now(dt.timezone.utc): raise ValueError('CAPTURE_TIME_INVALID')
    payload=json.loads(raw,parse_float=D)
    if payload.get('status')!=200 or not isinstance(payload.get('data'),list):raise ValueError('SOURCE_STATUS_INVALID')
    rows={}
    for row in payload['data']:
        day=dt.date.fromisoformat(row['date'])
        if row.get('currency')!='USD' or day>captured.astimezone(ZoneInfo('Asia/Taipei')).date():raise ValueError('SOURCE_IDENTITY_OR_FUTURE_DATE')
        if row['date'] in rows:raise ValueError('DUPLICATE_DATE')
        buy,sell=D(str(row.get('spot_buy',0))),D(str(row.get('spot_sell',0)))
        if not buy.is_finite() or not sell.is_finite() or buy<=0 or sell<=buy or buy.quantize(D('.0001'))!=buy or sell.quantize(D('.0001'))!=sell:raise ValueError('NATIVE_SPOT_INVALID')
        if start<=day<=end:rows[row['date']]=(buy,sell)
    if not rows or min(rows)>start.isoformat() or max(rows)!=end.isoformat():raise ValueError('WINDOW_OR_LATEST_SOURCE_UNAVAILABLE')
    return rawsha,rows

def plan_sql(rawtext,receipt,start,end,rawsha,rows):
    vals=',\n'.join('('+','.join([literal(day),canonical(buy),canonical(sell),literal(member(rawsha,day,buy,sell))])+')' for day,(buy,sell) in sorted(rows.items()))
    outside="currency<>'USD' OR rate_date NOT IN ("+','.join(literal(day)+"::date" for day in sorted(rows))+')' 
    return f"""BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT pg_advisory_xact_lock(hashtext('USD_SPOT_HISTORY_REPAIR'));
CREATE TEMP TABLE fx_outside_before ON COMMIT DROP AS SELECT md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY id),'')) AS fingerprint FROM exchange_rate_history r WHERE {outside};
INSERT INTO exchange_rate_source_capture(provider,rate_basis,source_url,captured_at,source_sha256,raw_body)
VALUES('FINMIND_TAIWAN_EXCHANGE_RATE','BANK_SPOT_SELLING',{literal(receipt['url'])},{literal(receipt['capturedAt'])}::timestamptz,{literal(rawsha)},{literal(rawtext)}) ON CONFLICT(provider,rate_basis,source_sha256,source_url,captured_at) DO NOTHING;
CREATE TEMP TABLE fx_expected_raw ON COMMIT DROP AS SELECT {literal(rawtext)}::text AS body;
DO $$ BEGIN IF (SELECT raw_body FROM exchange_rate_source_capture WHERE provider='FINMIND_TAIWAN_EXCHANGE_RATE' AND source_sha256={literal(rawsha)} AND source_url={literal(receipt['url'])} AND captured_at={literal(receipt['capturedAt'])}::timestamptz) IS DISTINCT FROM (SELECT body FROM fx_expected_raw) THEN RAISE EXCEPTION 'FX_CAPTURE_RAW_CONFLICT'; END IF; END $$;
CREATE TEMP TABLE fx_native(day,buy,sell,member) ON COMMIT DROP AS SELECT day::date,buy::numeric,sell::numeric,member::text FROM (VALUES {vals}) AS v(day,buy,sell,member);
CREATE TEMP TABLE fx_price_audit ON COMMIT DROP AS SELECT count(*) FILTER(WHERE h.id IS NOT NULL AND (h.buy_rate,h.sell_rate) IS DISTINCT FROM (n.buy,n.sell)) AS price_corrections,count(*) FILTER(WHERE h.id IS NULL) AS inserted FROM fx_native n LEFT JOIN exchange_rate_history h ON h.currency='USD' AND h.rate_date=n.day;
INSERT INTO exchange_rate_history(currency,rate_date,buy_rate,sell_rate,source_capture_id,source_member_sha256)
SELECT 'USD',n.day,n.buy,n.sell,c.id,n.member FROM fx_native n CROSS JOIN exchange_rate_source_capture c WHERE c.provider='FINMIND_TAIWAN_EXCHANGE_RATE' AND c.rate_basis='BANK_SPOT_SELLING' AND c.source_sha256={literal(rawsha)} AND c.source_url={literal(receipt['url'])} AND c.captured_at={literal(receipt['capturedAt'])}::timestamptz
ON CONFLICT(currency,rate_date) DO UPDATE SET buy_rate=EXCLUDED.buy_rate,sell_rate=EXCLUDED.sell_rate,source_capture_id=EXCLUDED.source_capture_id,source_member_sha256=EXCLUDED.source_member_sha256
WHERE (exchange_rate_history.buy_rate,exchange_rate_history.sell_rate,exchange_rate_history.source_capture_id,exchange_rate_history.source_member_sha256) IS DISTINCT FROM (EXCLUDED.buy_rate,EXCLUDED.sell_rate,EXCLUDED.source_capture_id,EXCLUDED.source_member_sha256);
DO $$ BEGIN
 IF (SELECT count(*) FROM fx_native n JOIN exchange_rate_history h ON h.currency='USD' AND h.rate_date=n.day JOIN exchange_rate_source_capture c ON c.id=h.source_capture_id WHERE h.buy_rate=n.buy AND h.sell_rate=n.sell AND h.source_member_sha256=n.member AND c.source_sha256={literal(rawsha)} AND c.source_url={literal(receipt['url'])} AND c.captured_at={literal(receipt['capturedAt'])}::timestamptz) <> {len(rows)} THEN RAISE EXCEPTION 'FX_BACKFILL_NATIVE_READBACK_MISMATCH'; END IF;
 IF (SELECT fingerprint FROM fx_outside_before) <> (SELECT md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY id),'')) FROM exchange_rate_history r WHERE {outside}) THEN RAISE EXCEPTION 'FX_OUTSIDE_WINDOW_CHANGED'; END IF;
END $$;
SELECT json_build_object('status','PASS','rows',{len(rows)},'sourceSha256',{literal(rawsha)},'outsideWindowUnchanged',true,'priceCorrections',(SELECT price_corrections FROM fx_price_audit),'rowsInserted',(SELECT inserted FROM fx_price_audit),'storedRawBody',raw_body) FROM exchange_rate_source_capture WHERE provider='FINMIND_TAIWAN_EXCHANGE_RATE' AND source_sha256={literal(rawsha)} AND source_url={literal(receipt['url'])} AND captured_at={literal(receipt['capturedAt'])}::timestamptz;
COMMIT;
"""

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--raw',type=pathlib.Path,required=True);p.add_argument('--receipt',type=pathlib.Path,required=True);p.add_argument('--start',type=dt.date.fromisoformat,required=True);p.add_argument('--end',type=dt.date.fromisoformat,required=True);p.add_argument('--audit-dir',type=pathlib.Path,required=True);p.add_argument('--apply',action='store_true');p.add_argument('--container',default='asset-postgres');p.add_argument('--db-user',default='assets');p.add_argument('--db-name',default='assets');a=p.parse_args()
    if a.start>a.end: p.error('start>end')
    raw=a.raw.read_bytes();receipt=json.loads(a.receipt.read_text());rawsha,rows=inspect(raw,receipt,a.start,a.end)
    sql=plan_sql(raw.decode('utf-8'),receipt,a.start,a.end,rawsha,rows)
    a.audit_dir.mkdir(parents=True,exist_ok=True);(a.audit_dir/'reviewed-import.sql').write_text(sql)
    result={'mode':'APPLY' if a.apply else 'DRY_RUN','sourceSha256':rawsha,'rows':len(rows),'first':min(rows),'last':max(rows),'sourceUrl':receipt['url'],'capturedAt':receipt['capturedAt']}
    if a.apply:
        run=subprocess.run(['docker','exec','-i',a.container,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U',a.db_user,'-d',a.db_name],input=sql,text=True,capture_output=True)
        if run.returncode:raise RuntimeError('FX_IMPORT_TRANSACTION_FAILED: '+run.stderr[-1500:])
        records=[json.loads(line) for line in run.stdout.splitlines() if line.lstrip().startswith('{')]
        if len(records)!=1 or sha(records[0].pop('storedRawBody'))!=rawsha:raise RuntimeError('STORED_RAW_SHA_READBACK_FAILED')
        result.update(records[0])
    (a.audit_dir/'receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
