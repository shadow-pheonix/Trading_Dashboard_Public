"""Private human setup and service scope; no privileged actions executed."""
import copy
import importlib.util
from pathlib import Path
import unittest
from dashboard_backend.validation import InvalidData

root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('configure_balances',root/'deploy/configure-balances.py')
setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(setup)

class UpdateCase(unittest.TestCase):
    def config(self):
        return {'instances':[{'id':name,'label':name,'accounts':[
            {'id':f'{model}-{wallet}','label':model,'strategy':model,
             'statePath':f'/test/{name}/hl_state_{"b_" if wallet=="B" else ""}{model.lower()}.json',
             'historyPath':f'/test/{name}/{model}-{wallet}.csv',
             'edgeModel':f'{model}-Fast'+('-B' if wallet=='B' else '')}
            for model in ('M1','M2','M3') for wallet in (('A','B') if name=='main' else ('A',))]}
            for name in ('main','vinay','madhu')]}

    def test_human_setup_adds_only_private_addresses_and_wallet_mapping(self):
        original=self.config();calls=[];index=[0]
        def reader(path, fields):
            calls.append((path,fields));values={}
            for field in sorted(fields):
                index[0]+=1;values[field]='0x'+format(index[0],'040x')
            return values
        configured=setup.configure(copy.deepcopy(original),reader)
        self.assertEqual(12,index[0])
        self.assertEqual(list(setup.ENVIRONMENTS.values()),[path for path,_ in calls])
        self.assertEqual([6,3,3],[len(fields) for _,fields in calls])
        for old,new in zip(original['instances'],configured['instances']):
            for a,b in zip(old['accounts'],new['accounts']):
                self.assertEqual(a,{k:v for k,v in b.items() if k not in ('publicAddress','wallet')})
        import json,tempfile
        from dashboard_backend.config import load_config
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'config.json';path.write_text(json.dumps(configured))
            self.assertEqual([6,3,3],[len(i.accounts) for i in load_config(path).instances])
        self.assertEqual(3,len(calls))
        def missing(path, fields):raise InvalidData()
        with self.assertRaises(InvalidData):setup.configure(configured,missing)

    def test_setup_rejects_wrong_scope_before_any_environment_read(self):
        def unread(path,fields):self.fail('Invalid scope must not read any env file')
        for change in ('missing','duplicate','bad_model','bad_wallet','duplicate_instance'):
            value=self.config()
            if change=='missing':value['instances'][1]['accounts'].pop()
            elif change=='duplicate':value['instances'][0]['accounts'][0]['strategy']='M2'
            elif change=='bad_model':value['instances'][2]['accounts'][0]['strategy']='unknown'
            elif change=='bad_wallet':value['instances'][2]['accounts'][0]['wallet']='B'
            else:value['instances'].append(copy.deepcopy(value['instances'][0]))
            with self.subTest(change=change),self.assertRaises(InvalidData):
                setup.configure(value,unread)

    def test_proxy_exact_endpoint_allowlist_and_service_controls(self):
        caddy=(root/'deploy/Caddyfile').read_text()
        paths=next(line for line in caddy.splitlines() if '@dashboard path' in line).split()[2:]
        self.assertEqual(['/healthz','/v1/dashboard','/v1/live','/v2/dashboard','/v2/live','/v2/balances','/v2/alerts','/v2/alerts/live'],paths)
        import re
        for name in ['update.sh','rollback.sh']:
            script=(root/'deploy'/name).read_text()
            for command in re.findall(r'^systemctl .+$',script,re.M):
                self.assertRegex(command,r'^systemctl (?:stop|start|is-active --quiet) trading-dashboard-(?:https|api)\.service trading-dashboard-(?:https|api)\.service$')
            self.assertNotIn('/etc/hlmacdbot',script)
            self.assertNotIn('key-create',script)
        unit=(root/'deploy/trading-dashboard-api.service').read_text()
        self.assertIn('InaccessiblePaths=-/etc/hlmacdbot',unit)
        self.assertIn('ProtectSystem=strict',unit)
