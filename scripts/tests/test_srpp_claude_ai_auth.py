"""Synthetic native login tests: no real CLI, Keychain, network or credentials."""
import contextlib
import importlib.util
import json
import os
import pathlib
import sys
import tempfile
import threading
import urllib.request
import urllib.error
import time
import unittest
import urllib.parse
from unittest import mock

ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('auth_bridge_fixture',ROOT/'scripts/srpp-local-ai-bridge.py')
b=importlib.util.module_from_spec(spec);sys.modules[spec.name]=b;spec.loader.exec_module(b)
a=b._auth_module
STATE='s'*43
URL='https://claude.com/cai/oauth/authorize?'+urllib.parse.urlencode(dict(code='true',client_id='9d1c250a-e61b-44d9-88ed-5944d1962f5e',response_type='code',redirect_uri='https://platform.claude.com/oauth/code/callback',scope=' '.join(sorted(a.SCOPE)),code_challenge='c'*43,code_challenge_method='S256',state=STATE))

class AuthTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.temp.name);self.root.chmod(0o700)
        self.exe=self.root/'fake-cli';self.tool=dict(id='claude',adapter='claude_cli',executable=str(self.exe))
        self.invalidated=[]
        self.manager=a.ClaudeAuthManager(self.root,lambda name:self.tool if name=='claude' else None,
            lambda tool:dict(schemaVersion=1,toolId=tool['id'],loginState='VALID',expiresAt='2099-01-01T00:00:00Z',code=None),
            b.clean_environment,lambda:self.invalidated.append(True),b.Problem)
        self.owner=dict(ownerId=3,sessionBinding='b'*43)
        self.fake()
    def tearDown(self):
        f=self.manager.flow
        if f:
            f['cancel'].set()
            if self.manager.worker:self.manager.worker.join(3)
        self.temp.cleanup()
    def fake(self,url=URL,mode='normal'):
        self.exe.write_text('#!'+sys.executable+'\n'+f'''
import os,sys,time
assert sys.argv[1:]==['--safe-mode','auth','login','--claudeai']
assert os.environ['BROWSER']=='/usr/bin/true'
assert 'ANTHROPIC_API_KEY' not in os.environ
url={url!r}
mode={mode!r}
if mode=='overflow':
 print('x'*140000,flush=True);time.sleep(30)
elif mode=='silent':time.sleep(30)
else:
 print('If the browser did not open, visit: '+url,flush=True)
 value=sys.stdin.readline().strip()
 if mode=='failure':sys.exit(1)
 sys.exit(0 if value=='synthetic-code#{STATE}' else 2)
''');self.exe.chmod(0o700)
    def request(self,**fields):return dict(self.owner,**fields)
    def wait(self,desired):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            f=self.manager.flow
            if f and f['status'] in desired:return f
            time.sleep(.01)
        self.fail('synthetic auth did not reach expected status')
    def start(self):
        self.manager.start(self.request(toolId='claude'))
        return self.wait({'WAITING_FOR_CODE'})
    def assert_code(self,code,fn,*args):
        with self.assertRaises(b.Problem) as caught:fn(*args)
        self.assertEqual(code,caught.exception.code)
    def test_validated_native_url_and_closed_queries(self):
        self.assertEqual(STATE,a.validated_url(URL))
        q=dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(URL).query))
        mutations=[dict(client_id='other'),dict(scope=q['scope']+' extra'),dict(code_challenge_method='plain'),dict(redirect_uri='http://localhost:9/callback'),dict(state='x')]
        for updates in mutations:
            with self.subTest(updates=updates),self.assertRaises(ValueError):
                a.validated_url('https://claude.com/cai/oauth/authorize?'+urllib.parse.urlencode(dict(q,**updates)))
        for value in [URL.replace('claude.com/','evil.example/'),URL+'&state=duplicate',URL+'&unknown=x',URL+'#fragment',URL.replace('https://','http://'),URL.replace('claude.com/','user@claude.com/'),URL.replace('claude.com/','claude.com:443/'),URL+'\n',URL+'x'*9000]:
            with self.subTest(value=value[:30]),self.assertRaises(ValueError):a.validated_url(value)
    def test_real_fake_cli_lifecycle_and_one_completion(self):
        f=self.start();fid=f['flowId'];self.assertEqual(43,len(fid))
        snapshot=self.manager.status(self.request(flowId=fid));self.assertEqual(URL,snapshot['authorizationUrl'])
        self.assertEqual({'schemaVersion','flowId','toolId','status','authorizationUrl','expiresAt','code'},set(snapshot))
        result=self.manager.complete(self.request(flowId=fid,code='synthetic-code#'+STATE))
        self.assertIsNone(result['authorizationUrl'])
        self.assert_code('AUTH_INVALID_CODE',self.manager.complete,self.request(flowId=fid,code='synthetic-code#'+STATE))
        self.wait({'SUCCEEDED'});self.manager.worker.join(2)
        self.assertEqual([True],self.invalidated);self.assertIsNone(f['process']);self.assertIsNone(f['state'])
        with a.operation_lock(self.root,b.Problem,exclusive=True):pass
    def test_ownership_replay_and_wrong_code_never_reach_native(self):
        f=self.start();request=self.request(flowId=f['flowId'])
        for updates in [dict(ownerId=4),dict(sessionBinding='z'*43),dict(flowId='x'*43)]:
            bad=dict(request,**updates)
            for action in ['status','cancel']:
                self.assert_code('AUTH_SESSION_NOT_FOUND',getattr(self.manager,action),bad)
            self.assert_code('AUTH_SESSION_NOT_FOUND',self.manager.complete,dict(bad,code='ok#'+STATE))
        for value in ['code#wrong','code#'+STATE+'\n','code #'+STATE,'#'+STATE,'code##'+STATE,'a'*4097,'密碼#'+STATE]:
            self.assert_code('AUTH_INVALID_CODE',self.manager.complete,dict(request,code=value))
        self.assertFalse(f['sent']);self.assertEqual('WAITING_FOR_CODE',f['status'])
    def test_start_dedup_and_other_owner_busy(self):
        f=self.start();worker=self.manager.worker
        self.assertEqual(f['flowId'],self.manager.start(self.request(toolId='claude'))['flowId'])
        self.assertIs(worker,self.manager.worker)
        self.assert_code('AUTH_BUSY',self.manager.start,dict(ownerId=4,sessionBinding='z'*43,toolId='claude'))
    def test_cancel_kills_process_and_unlocks(self):
        f=self.start();p=f['process']
        result=self.manager.cancel(self.request(flowId=f['flowId']))
        self.assertEqual('CANCELLED',result['status']);self.assertIsNone(result['authorizationUrl']);self.assertIsNotNone(p.poll())
        self.assert_code('AUTH_INVALID_CODE',self.manager.complete,self.request(flowId=f['flowId'],code='ok#'+STATE))
        with a.operation_lock(self.root,b.Problem,exclusive=True):pass
    def test_timeout_overflow_invalidurl_and_nativefailure_cleanup(self):
        for mode,url in [('silent',URL),('overflow',URL),('normal',URL.replace('claude.com/','evil.example/')),('failure',URL)]:
            with self.subTest(mode=mode,url=url[:20]):
                self.fake(url,mode);self.manager.start(self.request(toolId='claude'));f=self.manager.flow
                if mode=='silent':f['deadline']=time.monotonic()-.1
                if mode=='failure':
                    self.wait({'WAITING_FOR_CODE'});self.manager.complete(self.request(flowId=f['flowId'],code='synthetic-code#'+STATE))
                self.wait({'EXPIRED','FAILED'});self.manager.worker.join(2)
                self.assertIsNone(f['process']);self.assertIsNone(f['url'])
                with a.operation_lock(self.root,b.Problem,exclusive=True):pass
    def test_cli_exit_success_requires_local_valid_subscription(self):
        self.manager.check=lambda tool:dict(loginState='EXPIRED')
        f=self.start();self.manager.complete(self.request(flowId=f['flowId'],code='synthetic-code#'+STATE))
        self.wait({'FAILED'});self.assertEqual([],self.invalidated)
    def test_claude_only_cross_process_lease(self):
        with a.operation_lock(self.root,b.Problem):
            self.assert_code('AUTH_BUSY',self.manager.start,self.request(toolId='claude'))
        self.start()
        self.assert_code('AI_AUTH_IN_PROGRESS',lambda:a.operation_lock(self.root,b.Problem).__enter__())
        self.assertEqual('AUTHENTICATING',self.manager.state(self.request(toolId='claude'))['loginState'])
    def test_closed_requests_and_restart_no_replay(self):
        for body in [self.request(toolId='claude',extra=True),dict(ownerId=True,sessionBinding='b'*43,toolId='claude'),dict(ownerId=0,sessionBinding='b'*43,toolId='claude'),dict(ownerId=3,sessionBinding='bad',toolId='claude')]:
            self.assert_code('AI_INVALID_REQUEST',self.manager.start,body)
        self.assert_code('AI_INVALID_SELECTION',self.manager.start,self.request(toolId='other'))
        self.assert_code('AUTH_SESSION_NOT_FOUND',self.manager.status,self.request(flowId='x'*43))

    def test_ansi_hyperlink_url_and_terminal_retention(self):
        self.fake(URL+'\x07')
        f=self.start()
        self.assertEqual(URL,self.manager.status(self.request(flowId=f['flowId']))['authorizationUrl'])
        self.manager.cancel(self.request(flowId=f['flowId']))
        f['finishedAt']=time.monotonic()-61
        self.assert_code('AUTH_SESSION_NOT_FOUND',self.manager.status,self.request(flowId=f['flowId']))

    def test_state_and_host_auth_routes_closed_and_no_store(self):
        bridge=b.Bridge(self.root/'registry.json',self.root)
        bridge.auth_manager=self.manager
        server=b.Server(('127.0.0.1',0),bridge,'T'*48)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            endpoint='http://127.0.0.1:'+str(server.server_port)
            def call(action,body,token='T'*48):
                request=urllib.request.Request(endpoint+'/auth/claude/'+action,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
                return urllib.request.urlopen(request,timeout=3)
            with call('state',self.request(toolId='claude')) as response:
                value=json.load(response)
                self.assertEqual('no-store',response.headers['Cache-Control'])
                self.assertEqual({'schemaVersion','toolId','loginState','expiresAt','code'},set(value))
            for action,body,status in [('state',self.request(toolId='claude',extra=True),400),('other',{},404)]:
                with self.assertRaises(urllib.error.HTTPError) as e:call(action,body)
                self.assertEqual(status,e.exception.code)
                value=json.loads(e.exception.read());e.exception.close()
                self.assertEqual({'code','message','execution'},set(value));self.assertIsNone(value['execution'])
            with self.assertRaises(urllib.error.HTTPError) as e:call('start',self.request(toolId='claude'),token='bad')
            self.assertEqual(401,e.exception.code);e.exception.close();self.assertIsNone(self.manager.flow)
        finally:server.shutdown();server.server_close();thread.join(2)

    def test_success_not_visible_before_cleanup_lease_barrier(self):
        entered=threading.Event();release=threading.Event();original=a.operation_lock
        @contextlib.contextmanager
        def barrier(*args,**kwargs):
            with original(*args,**kwargs):
                try:yield
                finally:entered.set();release.wait(3)
        with mock.patch.object(a,'operation_lock',barrier):
            f=self.start()
            self.manager.complete(self.request(flowId=f['flowId'],code='synthetic-code#'+STATE))
            try:
                self.assertTrue(entered.wait(2))
                self.assertEqual('VERIFYING',self.manager.status(self.request(flowId=f['flowId']))['status'])
                self.assertEqual([],self.invalidated)
                self.assert_code('AI_AUTH_IN_PROGRESS',lambda:original(self.root,b.Problem).__enter__())
            finally:release.set();self.manager.worker.join(3)
        self.assertEqual('SUCCEEDED',f['status']);self.assertEqual([True],self.invalidated)
        with original(self.root,b.Problem,exclusive=True):pass

    def test_cancel_during_nonzero_poll_keeps_cancelled_priority(self):
        self.fake(mode='failure');original=a.subprocess.Popen
        def spawn(*args,**kwargs):
            process=original(*args,**kwargs);poll=process.poll
            def raced_poll():
                result=poll()
                if result is not None:
                    self.manager.flow['cancel'].set()
                    self.manager.flow['status']='CANCELLED'
                return result
            process.poll=raced_poll
            return process
        with mock.patch.object(a.subprocess,'Popen',side_effect=spawn):
            f=self.start();self.manager.complete(self.request(flowId=f['flowId'],code='synthetic-code#'+STATE))
            self.manager.worker.join(3)
        self.assertEqual('CANCELLED',f['status']);self.assertIsNone(f['code'])

    def test_direct_worker_holds_claude_lease_across_proof_and_inference(self):
        registry=self.root/'registry.json'
        registry.write_text(json.dumps(dict(schemaVersion=1,tools=[dict(self.tool,label='Claude')])));registry.chmod(0o600)
        bridge=b.Bridge(registry,self.root)
        def proof_and_inference(*args):
            self.assert_code('AUTH_BUSY',self.manager.start,self.request(toolId='claude'))
            with a.operation_lock(self.root,b.Problem):pass  # nested shared is safe
            return {'synthetic':'only'}
        with mock.patch.object(bridge,'_execute_inline_locked',side_effect=proof_and_inference):
            result=bridge.execute_inline(dict(selection=dict(toolId='claude')))
        self.assertEqual({'synthetic':'only'},result)
        with a.operation_lock(self.root,b.Problem,exclusive=True):pass

class ExpiryTests(unittest.TestCase):
    def test_expiry_validation(self):
        self.assertIsNone(b.Bridge.claude_expiry({}))
        self.assertTrue(b.Bridge.claude_expiry(dict(expiresAt=(time.time()+3600)*1000)).endswith('Z'))
        for value,code in [(0,'AI_AUTH_REQUIRED'),(-1,'AI_AUTH_REQUIRED'),(True,'AI_AUTH_REQUIRED'),('1','AI_AUTH_REQUIRED'),(float('inf'),'AI_AUTH_REQUIRED'),(float('nan'),'AI_AUTH_REQUIRED'),(1,'AI_AUTH_EXPIRED')]:
            with self.subTest(value=value),self.assertRaises(b.Problem) as e:b.Bridge.claude_expiry(dict(expiresAt=value))
            self.assertEqual(code,e.exception.code)
    def test_expired_blocks_http_and_local_state_bypasses_fee_cooldown(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root);path.chmod(0o700);bridge=b.Bridge(path/'registry.json',path)
            auth=dict(loggedIn=True,authMethod='claude.ai',subscriptionType='pro')
            def native(argv,**kwargs):
                if argv[0]=='/usr/bin/security':return json.dumps(dict(claudeAiOauth=dict(accessToken='synthetic',expiresAt=1))).encode()
                return json.dumps(auth).encode()
            with mock.patch.object(b,'run_process',side_effect=native),mock.patch.object(b,'http_json') as http:
                with self.assertRaises(b.Problem) as e:bridge.claude_proof(dict(executable='/fake'))
                self.assertEqual('AI_AUTH_EXPIRED',e.exception.code);http.assert_not_called()
                with mock.patch.object(bridge,'claude_cooldown',side_effect=b.Problem('AI_RATE_LIMITED')):
                    state=bridge.claude_local_state(dict(id='claude',executable='/fake'))
                    self.assertEqual('EXPIRED',state['loginState'])
                    with mock.patch.object(b,'run_process') as run:
                        with self.assertRaises(b.Problem) as e:bridge.claude_proof(dict(executable='/fake'))
                        self.assertEqual('AI_RATE_LIMITED',e.exception.code);run.assert_not_called()
