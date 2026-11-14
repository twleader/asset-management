#!/usr/bin/env python3
"""Private-network bounded settings calculator. No AI, vendors or file writes."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import threading
from settings_catalog import ROOT,SettingsContext,SettingsError,strict_json
from scenario_cashflow import simulate

MAX_BODY=8*1024*1024

def verify_runtime(root):
    root=Path(root);receipt=strict_json((root/'runtime-manifest.json').read_bytes())
    if receipt.get('schemaVersion')!=1 or not isinstance(receipt.get('files'),dict):raise RuntimeError('RUNTIME_MANIFEST_INVALID')
    existing={str(path.relative_to(root)) for path in root.rglob('*') if path.is_file()}
    if existing!=set(receipt['files'])|{'runtime-manifest.json'}:raise RuntimeError('RUNTIME_FILE_SET_MISMATCH')
    official=strict_json((root/'assumptions/policy_manifest.json').read_bytes())
    if receipt.get('baseBundleSha256')!=official.get('POLICY_BUNDLE_SHA256'):raise RuntimeError('RUNTIME_BUNDLE_MISMATCH')
    for name,expected in receipt['files'].items():
        path=root/name
        if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise RuntimeError('RUNTIME_SOURCE_HASH_MISMATCH')
        if name!='assumptions/policy_manifest.json' and official['files'].get(name)!=expected:raise RuntimeError('RUNTIME_POLICY_SOURCE_MISMATCH')
    return receipt

def serve(root=ROOT,port=8080):
    verify_runtime(root);context=SettingsContext(root);lock=threading.BoundedSemaphore(1)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*_):pass
        def reply(self,status,value):
            body=json.dumps(value,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()
            self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
        def do_GET(self):
            if self.path=='/internal/srpp/catalog':self.reply(200,context.descriptor())
            elif self.path in ('/health','/internal/health'):self.reply(200,dict(status='UP',baseBundleSha256=context.base))
            else:self.reply(404,dict(code='NOT_FOUND',message='找不到路徑。'))
        def do_POST(self):
            if self.path not in ('/internal/srpp/validate','/internal/srpp/simulate'):self.reply(404,dict(code='NOT_FOUND',message='找不到路徑。'));return
            if not lock.acquire(blocking=False):self.reply(429,dict(code='BUSY',message='模擬服務忙碌，請稍後再試。'));return
            try:
                if self.headers.get('Content-Type','').split(';',1)[0]!='application/json':raise SettingsError('INVALID_REQUEST','只接受 JSON。',400)
                try:size=int(self.headers.get('Content-Length','0'))
                except ValueError:raise SettingsError('INVALID_REQUEST','請求長度不符。',400)
                if not 0<size<=MAX_BODY:raise SettingsError('INVALID_REQUEST','請求過大或沒有內容。',400)
                self.connection.settimeout(15)
                request=strict_json(self.rfile.read(size))
                if self.path.endswith('/validate') and (not isinstance(request,dict) or set(request)-{'schemaVersion','baseBundleSha256','configSha256','values'}):raise SettingsError('INVALID_REQUEST','設定驗證含未知欄位。',400)
                result=context.validate(request) if self.path.endswith('/validate') else simulate(context,request)
                self.reply(200,result)
            except SettingsError as error:self.reply(error.status,dict(code=error.code,message=error.message))
            except (KeyError,TypeError,ValueError):self.reply(422,dict(code='INPUT_INVALID',message='輸入缺少必要的可驗證資料。'))
            except Exception:self.reply(503,dict(code='SIMULATION_UNAVAILABLE',message='無法完成本次模擬。'))
            finally:lock.release()
    server=ThreadingHTTPServer(('0.0.0.0',port),Handler);server.daemon_threads=True;server.serve_forever()
if __name__=='__main__':serve(port=int(os.environ.get('PORT','8080')))
