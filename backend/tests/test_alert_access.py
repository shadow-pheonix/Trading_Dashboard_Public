"""Human setup helper against temporary synthetic instance directories only."""
import importlib.util
import json
import os
from pathlib import Path
from contextlib import closing
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('alert_access',Path(__file__).resolve().parents[1]/'deploy/configure-alert-access.py')
setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(setup)


class AccessCase(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)
        self.roots={name:self.root/name for name in ('main','vinay','madhu')}
        instances=[]
        for name,parent in self.roots.items():
            parent.mkdir();state=parent/'state.json';state.write_text('must not read')
            instances.append({'id':name,'alertsPath':str(parent/'dashboard_alerts.sqlite'), 'accounts':[{'statePath':str(state)}]})
        self.value={'instances':instances}
    def test_plan_reads_metadata_only_and_stays_in_three_instance_roots(self):
        with patch.object(Path,'read_bytes',side_effect=AssertionError('No trade state reads')),patch.object(Path,'read_text',side_effect=AssertionError('No trade state reads')):
            planned=setup.plan(self.value,self.roots)
        self.assertEqual(3,len(planned));self.assertTrue(all(uid==os.getuid() for _,uid in planned))
        self.value['instances'][0]['alertsPath']=str(self.root/'unrelated.sqlite')
        with self.assertRaises(ValueError):setup.plan(self.value,self.roots)
    def test_missing_instance_or_symlink_is_rejected_before_preparing_files(self):
        with self.assertRaises(ValueError):setup.plan({'instances':self.value['instances'][:2]},self.roots)
        state=self.roots['main']/'state.json';state.unlink();state.symlink_to(self.roots['vinay']/'state.json')
        with self.assertRaises(ValueError):setup.plan(self.value,self.roots)
    def test_prepare_preserves_existing_events_and_makes_no_env_or_service_calls(self):
        path,uid=setup.plan(self.value,self.roots)[0]
        setup.prepare(path,uid,os.getgid())
        self.assertEqual(0o640,path.stat().st_mode & 0o777)
        with closing(sqlite3.connect(path)) as db,db:
            db.execute('INSERT INTO alerts(id,timestamp,message) VALUES(?,?,?)',('stable-id',1000,'Actual event'))
        setup.prepare(path,uid,os.getgid())
        with closing(sqlite3.connect(path)) as db:
            self.assertEqual('Actual event',db.execute('SELECT message FROM alerts').fetchone()[0])
        for parent in self.roots.values():self.assertEqual('must not read',(parent/'state.json').read_text())
    def test_outbox_symlink_never_changes_target(self):
        path,uid=setup.plan(self.value,self.roots)[0]
        target=self.root/'unrelated';target.write_text('preserve');path.symlink_to(target)
        with self.assertRaises(ValueError):setup.prepare(path,uid,os.getgid())
        self.assertEqual('preserve',target.read_text())

if __name__=='__main__':unittest.main()
