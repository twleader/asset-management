"""Native Claude subscription login lifecycle. Never handles OAuth tokens."""
from __future__ import annotations
import contextlib
import datetime as dt
import fcntl
import hmac
import os
import pathlib
import re
import secrets
import selectors
import signal
import stat
import subprocess
import tempfile
import threading
import time
import urllib.parse
import uuid

NONCE = re.compile(r'[A-Za-z0-9_-]{43}\Z')
SCOPE = frozenset(('org:create_api_key','user:profile','user:inference','user:sessions:claude_code',
                   'user:mcp_servers','user:file_upload','user:plugins','user:privacy_consents:write'))
ACTIVE = frozenset(('WAITING_FOR_URL','WAITING_FOR_CODE','VERIFYING'))


def iso(epoch):
    return dt.datetime.fromtimestamp(epoch,dt.timezone.utc).isoformat().replace('+00:00','Z')


def validated_url(value):
    if not isinstance(value,str) or len(value)>8192 or any(ord(c)<33 or ord(c)>126 for c in value):
        raise ValueError('invalid authorization URL')
    p=urllib.parse.urlsplit(value)
    if (p.scheme!='https' or p.netloc!='claude.com' or p.path!='/cai/oauth/authorize' or p.fragment):
        raise ValueError('invalid authorization URL')
    pairs=urllib.parse.parse_qsl(p.query,keep_blank_values=True,strict_parsing=True)
    q=dict(pairs)
    required={'code','client_id','response_type','redirect_uri','scope','code_challenge','code_challenge_method','state'}
    if len(q)!=len(pairs) or not required<=q.keys() or q.keys()-required-{'orgUUID'}:
        raise ValueError('invalid authorization query')
    if (q['code']!='true' or q['client_id']!='9d1c250a-e61b-44d9-88ed-5944d1962f5e'
        or q['response_type']!='code' or q['redirect_uri']!='https://platform.claude.com/oauth/code/callback'
        or q['code_challenge_method']!='S256'):
        raise ValueError('invalid authorization query')
    scopes=q['scope'].split(' ')
    if set(scopes)!=SCOPE or len(scopes)!=len(SCOPE): raise ValueError('invalid scopes')
    for key in ('code_challenge','state'):
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,256}',q[key]): raise ValueError('invalid state')
    if 'orgUUID' in q:
        if str(uuid.UUID(q['orgUUID']))!=q['orgUUID'].lower(): raise ValueError('invalid organization')
    return q['state']


@contextlib.contextmanager
def operation_lock(directory, problem, *, exclusive=False):
    """Only Claude operations participate. Kernel lock also covers worker processes."""
    fd=None
    try:
        fd=os.open(pathlib.Path(directory)/'claude-operation.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW|os.O_NONBLOCK|os.O_CLOEXEC,0o600)
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:
            raise problem('AI_AUTH_REQUIRED')
        try: fcntl.flock(fd,(fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)|fcntl.LOCK_NB)
        except BlockingIOError: raise problem('AUTH_BUSY' if exclusive else 'AI_AUTH_IN_PROGRESS',409) from None
        yield
    except OSError: raise problem('AI_AUTH_REQUIRED') from None
    finally:
        if fd is not None:
            with contextlib.suppress(OSError): fcntl.flock(fd,fcntl.LOCK_UN)
            os.close(fd)


class ClaudeAuthManager:
    def __init__(self,state_dir,tool_lookup,local_check,environment,invalidate,problem):
        self.directory=state_dir;self.lookup=tool_lookup;self.check=local_check
        self.environment=environment;self.invalidate=invalidate;self.problem=problem
        self.mutex=threading.RLock();self.flow=None;self.worker=None

    def _identity(self,body,fields):
        if (not isinstance(body,dict) or set(body)!={'ownerId','sessionBinding'}|set(fields)
            or type(body.get('ownerId')) is not int or not 0<body['ownerId']<2**63
            or not isinstance(body.get('sessionBinding'),str) or not NONCE.fullmatch(body['sessionBinding'])):
            raise self.problem('AI_INVALID_REQUEST',400)
        if 'toolId' in fields:
            tool=self.lookup(body['toolId'])
            if tool is None: raise self.problem('AI_INVALID_SELECTION',400)
            return tool
        if not isinstance(body.get('flowId'),str) or not NONCE.fullmatch(body['flowId']):
            raise self.problem('AUTH_SESSION_NOT_FOUND',404)

    def _owned(self,body):
        f=self.flow
        if f and f.get('finishedAt') is not None and time.monotonic()-f['finishedAt']>60:
            self.flow=None;f=None
        if (f is None or not hmac.compare_digest(f['flowId'],body['flowId']) or f['ownerId']!=body['ownerId']
            or not hmac.compare_digest(f['binding'],body['sessionBinding'])):
            raise self.problem('AUTH_SESSION_NOT_FOUND',404)
        return f

    def _snapshot(self,f):
        return dict(schemaVersion=1,flowId=f['flowId'],toolId=f['tool']['id'],status=f['status'],
                    authorizationUrl=f['url'] if f['status']=='WAITING_FOR_CODE' else None,
                    expiresAt=iso(f['expires']),code=f['code'])

    def state(self,body):
        tool=self._identity(body,{'toolId'})
        with self.mutex:
            if self.flow and self.flow['status'] in ACTIVE:
                return dict(schemaVersion=1,toolId=tool['id'],loginState='AUTHENTICATING',expiresAt=None,code='AI_AUTH_IN_PROGRESS')
        return self.check(tool)

    def start(self,body):
        tool=self._identity(body,{'toolId'})
        with self.mutex:
            if self.flow and self.flow['status'] in ACTIVE:
                f=self.flow
                if f['ownerId']==body['ownerId'] and hmac.compare_digest(f['binding'],body['sessionBinding']) and f['tool']['id']==tool['id']:
                    return self._snapshot(f)
                raise self.problem('AUTH_BUSY',409)
            # Acquire BEFORE spawning; hold until native process and all pipes are gone.
            lease=operation_lock(self.directory,self.problem,exclusive=True);lease.__enter__()
            f=dict(flowId=secrets.token_urlsafe(32),ownerId=body['ownerId'],binding=body['sessionBinding'],tool=tool,
                   status='WAITING_FOR_URL',url=None,state=None,expires=time.time()+300,
                   deadline=time.monotonic()+300,code=None,process=None,sent=False,lease=lease,cancel=threading.Event())
            self.flow=f
            self.worker=threading.Thread(target=self._run,args=(f,),daemon=True)
            try:self.worker.start()
            except Exception:
                lease.__exit__(None,None,None);f['status']='FAILED';f['code']='AUTH_FAILED'
                raise self.problem('AUTH_FAILED') from None
            return self._snapshot(f)

    def status(self,body):
        self._identity(body,{'flowId'})
        with self.mutex:return self._snapshot(self._owned(body))

    def complete(self,body):
        self._identity(body,{'flowId','code'})
        value=body.get('code')
        if (not isinstance(value,str) or not 3<=len(value)<=4096 or value.count('#')!=1
            or any(ord(c)<33 or ord(c)>126 for c in value)):
            raise self.problem('AUTH_INVALID_CODE',400)
        code,state=value.split('#')
        with self.mutex:
            f=self._owned(body)
            if (not code or not state or f['status']!='WAITING_FOR_CODE' or f['sent']
                or time.monotonic()>=f['deadline'] or not hmac.compare_digest(state,f['state'])):
                raise self.problem('AUTH_INVALID_CODE',400)
            f['sent']=True;f['status']='VERIFYING';f['url']=None
            try:
                # <=4097 bytes. Nonblocking PIPE_BUF bounded write; never retry or persist a code.
                written=os.write(f['process'].stdin.fileno(),value.encode('ascii')+b'\n')
                if written!=len(value)+1:raise OSError()
            except OSError:
                f['cancel'].set();f['status']='FAILED';f['code']='AUTH_FAILED'
            return self._snapshot(f)

    def cancel(self,body):
        self._identity(body,{'flowId'})
        with self.mutex:
            f=self._owned(body)
            if f['status'] in ACTIVE:f['cancel'].set();f['status']='CANCELLED';f['url']=None
            worker=self.worker
        if worker:worker.join(2)
        with self.mutex:return self._snapshot(f)

    def _run(self,f):
        p=None;sel=selectors.DefaultSelector();outcome="FAILED";safe_code="AUTH_FAILED"
        try:
            with tempfile.TemporaryDirectory(prefix='srpp-auth-') as cwd:
                env=self.environment();env['BROWSER']='/usr/bin/true'
                p=subprocess.Popen([f['tool']['executable'],'--safe-mode','auth','login','--claudeai'],
                    env=env,cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
                with self.mutex:f['process']=p
                os.set_blocking(p.stdin.fileno(),False);os.set_blocking(p.stdout.fileno(),False)
                sel.register(p.stdout,selectors.EVENT_READ)
                buffer=b'';size=0
                while True:
                    if f['cancel'].is_set():
                        outcome='CANCELLED';safe_code=None;break
                    if time.monotonic()>=f['deadline']:
                        outcome='EXPIRED';safe_code='AUTH_TIMEOUT';break
                    for key,_ in sel.select(.05):
                        block=os.read(key.fd,8192)
                        if not block:sel.unregister(key.fileobj);continue
                        size+=len(block)
                        if size>128*1024:raise ValueError()
                        buffer+=block
                        # URLs end in whitespace/ANSI terminators. Never forward arbitrary terminal text.
                        for match in re.finditer(rb'https://[^\x00-\x20\x7f<>]+(?=[\x00-\x20\x7f<>])',buffer):
                            value=match.group().decode('ascii')
                            if '/cai/oauth/authorize' not in value:continue
                            state=validated_url(value)
                            with self.mutex:
                                if f['state'] is not None and not hmac.compare_digest(f['state'],state):raise ValueError()
                                if f['status']=='WAITING_FOR_URL':f['state']=state;f['url']=value;f['status']='WAITING_FOR_CODE'
                        if len(buffer)>16384:buffer=buffer[-8192:]
                    if p.poll() is not None:
                        if f['cancel'].is_set():
                            outcome='CANCELLED';safe_code=None
                        elif p.returncode==0 and f['sent']:
                            local=self.check(f['tool'])
                            if local['loginState']=='VALID':outcome='SUCCEEDED';safe_code=None
                        break
        except Exception:
            if f['cancel'].is_set():outcome='CANCELLED';safe_code=None
        finally:
            sel.close()
            if p is not None:
                with contextlib.suppress(ProcessLookupError):os.killpg(p.pid,signal.SIGKILL)
                p.wait()
                for pipe in (p.stdin,p.stdout):
                    if pipe:
                        with contextlib.suppress(OSError):pipe.close()
            f['lease'].__exit__(None,None,None)
            # Publish success only AFTER the kernel lease and native callback
            # are gone. A UI reload must never cache an auth-in-progress race.
            with self.mutex:
                if f['cancel'].is_set() and outcome!='EXPIRED':
                    if f['status']=='FAILED':outcome='FAILED';safe_code='AUTH_FAILED'
                    else:outcome='CANCELLED';safe_code=None
                f['url']=None;f['state']=None;f['process']=None;f['finishedAt']=time.monotonic()
                if outcome=='SUCCEEDED':self.invalidate()
                f['status']=outcome;f['code']=safe_code
