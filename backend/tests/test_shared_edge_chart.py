"""One Main chart for all keys, while ordinary data stays instance scoped."""
import asyncio
from dataclasses import replace
import unittest
from unittest.mock import patch

from dashboard_backend.config import Config
from dashboard_backend.files import edge_current_open_estimate
from dashboard_backend.validation import decode
import test_backend as fixtures
import test_edge_chart_port as port


class SharedEdgeChartCase(unittest.TestCase):
    setUp = port.EdgeChartPortCase.setUp
    state = port.EdgeChartPortCase.state
    history = port.EdgeChartPortCase.history
    row = port.EdgeChartPortCase.row
    open_trade = port.EdgeChartPortCase.open_trade
    edge_row = port.EdgeChartPortCase.edge_row
    write_edge = port.EdgeChartPortCase.write_edge
    dated = port.EdgeChartPortCase.dated
    artifact = port.EdgeChartPortCase.artifact
    canonical_history = port.EdgeChartPortCase.canonical_history
    request = fixtures.BackendCase.request
    scope = fixtures.BackendCase.scope

    def prepare(self):
        self.canonical_history()
        self.main=replace(self.main,id='main',label='Main')
        instances=[self.main]; tokens=[fixtures.TOKEN]
        for name in ('vinay','madhu'):
            folder=self.path/name;folder.mkdir()
            account=replace(self.main.accounts[0],state_path=str(folder/'state.json'),history_path=str(folder/'trades.csv'))
            self.state(account,dict(self.open_trade(),sz=5 if name=='vinay' else 9))
            self.history(account,[self.row(pnl='123' if name=='vinay' else '456')])
            instance=replace(self.main,id=name,label=name.title(),accounts=(account,),edge_path=str(folder/'edge.csv'))
            self.write_edge([self.dated('2026-09-01 12:00:00',999,1999,'backtest')],instance)
            self.artifact([self.dated('2026-01-05 12:00:00',999,1999)],folder)
            instances.append(instance)
            key=folder/'dummy-key.txt';self.store.provision(name,key);tokens.append(key.read_text().strip())
        self.app.config=Config(tuple(instances))
        return instances,tokens

    def dashboard(self,token,mode='real',headers=None):
        return self.request('/v2/dashboard','period=YTD&model=all&mode='+mode,token=token,headers=headers)

    def test_all_three_keys_get_identical_main_chart_and_dd_but_own_trades_and_positions(self):
        instances,tokens=self.prepare()
        bodies=[self.dashboard(token)[1] for token in tokens]
        self.assertEqual(['Main','Vinay','Madhu'],[body['instance'] for body in bodies])
        for body in bodies:
            self.assertEqual(bodies[0]['normalizedEquity'],body['normalizedEquity'])
            self.assertEqual(bodies[0]['edgeDrawdowns'],body['edgeDrawdowns'])
        self.assertEqual([0,123,456],[body['metrics']['realizedPnl'] for body in bodies])
        self.assertEqual([0,50,90],[body['metrics']['unrealizedPnl'] for body in bodies])
        self.assertAlmostEqual(11115,bodies[0]['normalizedEquity']['points'][-1]['equity'])
        for mode in ('real','edge'):
            values=[self.dashboard(token,mode)[1]['normalizedEquity'] for token in tokens]
            self.assertEqual(values[0],values[1]);self.assertEqual(values[0],values[2])

    def test_main_chart_failure_never_substitutes_other_instances_history(self):
        _,tokens=self.prepare()
        (self.path/'edge_engine.py').write_text('_EMBEDDED_OFFLINE_SHA256 = "invalid"\n')
        for token in tokens:
            status,body,_=self.dashboard(token)
            self.assertEqual(200,status)
            self.assertIsNone(body['normalizedEquity'])
            self.assertIsNone(body['edgeDrawdowns'])

    def test_main_hl_equity_mark_is_shared_without_exposing_main_raw_balances(self):
        instances,tokens=self.prepare()
        calls=[]
        class Samples:
            async def get(inner,instance): calls.append(instance.id)
            def peek(inner,instance):
                return dict(attemptedAt=fixtures.NOW,accounts={a.id:dict(status='ok',equity=1200 if instance.id=='main' else 9999,
                    unrealizedPnl=200 if instance.id=='main' else 1,sampledAt=fixtures.NOW) for a in instance.accounts})
        self.app.balances=Samples()
        for token in tokens:
            headers=[(b'authorization',('Bearer '+token).encode()),(b'x-dashboard-margin',b'1')]
            status,body,_=self.dashboard(token,headers=headers)
            self.assertEqual(200,status)
            self.assertEqual(dict(returnPct=20,sampledAt=fixtures.NOW),body['normalizedEquity']['openEstimate'])
            self.assertNotIn('unrealizedPnl\': 200',str(body))
        self.assertIn('main',calls)
        self.assertIsNone(self.dashboard(tokens[1],mode='edge')[1]['normalizedEquity']['openEstimate'])

    def test_websocket_receives_same_main_curve_and_rechecks_authentication(self):
        _,tokens=self.prepare()
        async def stream(token):
            messages=[];queue=asyncio.Queue();await queue.put({'type':'websocket.connect'})
            async def send(event):
                messages.append(event)
                if event['type']=='websocket.send':await queue.put({'type':'websocket.disconnect','code':1000})
            await asyncio.wait_for(self.app(self.scope(path='/v2/live',query='period=YTD&model=all',token=token),queue.get,send),5)
            return decode(next(event['text'] for event in messages if event['type']=='websocket.send'))
        bodies=[self.loop.run_until_complete(stream(token)) for token in tokens]
        self.assertEqual(bodies[0]['normalizedEquity'],bodies[1]['normalizedEquity'])
        self.assertEqual(bodies[0]['normalizedEquity'],bodies[2]['normalizedEquity'])
        with patch.object(self.files,'build',side_effect=AssertionError('unauthenticated read')):
            self.assertEqual(401,self.dashboard('invalid')[0])

    def test_open_fraction_uses_pre_unrealized_equity_and_rejects_partial_or_stale_accounts(self):
        instances,_=self.prepare();main=instances[0]
        samples=dict(accounts={main.accounts[0].id:dict(status='ok',equity=1200,unrealizedPnl=200,sampledAt=fixtures.NOW)})
        self.assertEqual(20,edge_current_open_estimate(main,'all',samples,fixtures.NOW)['returnPct'])
        for name,value in [('equity',200),('sampledAt',fixtures.NOW-15001),('unrealizedPnl',None),('status','failed')]:
            item=samples['accounts'][main.accounts[0].id];previous=item[name];item[name]=value
            self.assertIsNone(edge_current_open_estimate(main,'all',samples,fixtures.NOW))
            item[name]=previous

    def test_revocation_during_main_balance_collection_returns_no_shared_chart(self):
        _,tokens=self.prepare()
        class Samples:
            async def get(inner,instance):
                if instance.id=='main':self.store.revoke('vinay')
            def peek(inner,instance):return None
        self.app.balances=Samples()
        headers=[(b'authorization',('Bearer '+tokens[1]).encode()),(b'x-dashboard-margin',b'1')]
        with patch.object(self.files,'build',side_effect=AssertionError('no read after revocation')):
            status,body,_=self.dashboard(tokens[1],headers=headers)
        self.assertEqual(401,status)
        self.assertEqual({'error':'Access denied.'},body)


if __name__=='__main__':unittest.main()
