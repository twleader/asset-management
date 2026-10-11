#!/usr/bin/env python3
"""隔離 mocks：不執行任何真 Serve/network 操作。"""
import copy
import importlib.util
import json
import sys
sys.dont_write_bytecode=True
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location("ai_serve",Path(__file__).parents[1]/"configure-tailscale-ai-flow.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ServeObservationTest(unittest.TestCase):
    def test_exact_help_captures_real_stderr_only_output(self):
        with tempfile.TemporaryDirectory(prefix="ai-flow-help-test-") as temporary:
            cli=Path(temporary)/"tailscale"
            cli.write_text("#!"+sys.executable+"\nimport sys\nprint('USAGE tailscale serve --set-path',file=sys.stderr)\n")
            cli.chmod(0o700)
            self.assertIn('--set-path',module.run([str(cli),'serve','--help']))

    def test_status_json_stdout_is_not_polluted_by_real_stderr(self):
        with tempfile.TemporaryDirectory(prefix="ai-flow-json-test-") as temporary:
            cli=Path(temporary)/"tailscale"
            cli.write_text("#!"+sys.executable+"\nimport sys\nprint('{\"Web\":{}}')\nprint('mock diagnostic must stay on stderr',file=sys.stderr)\n")
            cli.chmod(0o700)
            output=module.run([str(cli),'serve','status','--json'])
            self.assertEqual(json.loads(output),{'Web':{}})
            self.assertNotIn('mock diagnostic',output)

    def test_only_exact_help_sets_stderr_capture(self):
        with patch.object(module.subprocess,'check_output',return_value='{}') as execute:
            module.run(['/mock/tailscale','serve','--help'])
            execute.assert_called_once_with(['/mock/tailscale','serve','--help'],text=True,timeout=15,stderr=module.subprocess.STDOUT)
        for args in (['/mock/tailscale','status','--json'],['/mock/tailscale','serve','status','--json'],['curl','--write-out','%{http_code}']):
            with patch.object(module.subprocess,'check_output',return_value='{}') as execute:
                module.run(args)
                execute.assert_called_once_with(args,text=True,timeout=15)

    def fixture(self,new=False):
        return {"TCP":{"9090":{"HTTPS":True}},"Web":{"mock.ts.net:9090":{"Handlers":{p:{"Proxy":module.LOCAL+p} for p in module.LEGACY | ({module.PATH} if new else set())}}}}
    def invoke(self,before,race=None):
        after=copy.deepcopy(before); after["Web"]["mock.ts.net:9090"]["Handlers"][module.PATH]={"Proxy":module.LOCAL+module.PATH}
        calls=[]; reads=iter([before,race or before,after])
        def run(args):
            calls.append(args)
            if args[1:]==['serve','--help']:return '--set-path'
            if args[1:]==['status','--json']:return json.dumps({'BackendState':'Running','Self':{'Online':True,'DNSName':'mock.ts.net.'}})
            return json.dumps(next(reads))
        with patch.object(module.shutil,'which',return_value='/mock/tailscale'),patch.object(module,'run',side_effect=run),patch.object(module,'probe') as probes,patch.object(module.subprocess,'check_call') as mutate:
            module.main()
            return calls,probes,mutate,after
    def test_first_append_preserves_entire_prior_config_and_only_new_probe(self):
        before=self.fixture();snapshot=copy.deepcopy(before)
        calls,probes,mutate,after=self.invoke(before)
        mutate.assert_called_once_with(['/mock/tailscale','serve','--bg','--yes','--https=9090','--set-path='+module.PATH,module.LOCAL+module.PATH],timeout=15)
        self.assertEqual(snapshot,before)
        del after['Web']['mock.ts.net:9090']['Handlers'][module.PATH]
        self.assertEqual(before,after)
        self.assertEqual([c.args[0] for c in probes.call_args_list],[module.LOCAL,'https://mock.ts.net:9090'])
        self.assertFalse(any('reset' in c for c in calls))
    def test_idempotent_no_mutation(self):
        _,_,mutate,_=self.invoke(self.fixture(True));mutate.assert_not_called()
    def test_unknown_handler_rejected_before_probes_and_mutation(self):
        bad=self.fixture();bad['Web']['mock.ts.net:9090']['Handlers']['/unknown']={'Proxy':module.LOCAL+'/unknown'}
        with self.assertRaisesRegex(ValueError,'managed paths'):
            self.invoke(bad)
    def test_wrong_proxy_rejected(self):
        bad=self.fixture();bad['Web']['mock.ts.net:9090']['Handlers']['/api/quotes']={'Proxy':'http://unknown'}
        with self.assertRaisesRegex(ValueError,'handler proxy'):self.invoke(bad)
    def test_preflight_race_rejected(self):
        before=self.fixture();race=self.fixture(True)
        with self.assertRaisesRegex(ValueError,'已改變'):self.invoke(before,race)
    def test_probe_calls_only_new_path_and_uses_tls_validation(self):
        calls=[]
        def fake(args):
            calls.append(args)
            body=Path(args[args.index('--output')+1]);headers=Path(args[args.index('--dump-header')+1]);method=args[args.index('--request')+1]
            body.write_text('{"code":"TOKEN_REQUIRED","status":401}');headers.write_text('Allow: POST\n')
            return '401' if method=='POST' else '404' if args[-1].endswith('/child') else '405'
        with patch.object(module,'run',side_effect=fake):module.probe('https://mock.ts.net:9090')
        self.assertEqual(len(calls),3)
        for args in calls:
            self.assertIn(module.PATH,args[-1]);self.assertNotIn('-k',args);self.assertNotIn('--insecure',args)

if __name__=='__main__':unittest.main()
