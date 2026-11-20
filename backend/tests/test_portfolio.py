"""Primary-key scope, aggregate accuracy and failure isolation; synthetic data only."""
import contextlib
import copy
from dataclasses import replace
import sqlite3
import unittest
from unittest.mock import patch

import test_backend as fixtures
from dashboard_backend.config import Config


class PortfolioCase(unittest.TestCase):
    setUp = fixtures.BackendCase.setUp
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    row = fixtures.BackendCase.row
    open_trade = fixtures.BackendCase.open_trade
    request = fixtures.BackendCase.request
    scope = fixtures.BackendCase.scope

    def prepare(self):
        instances = [self.main]
        for name in ('second', 'third'):
            accounts = tuple(replace(a, state_path=str(self.path/(name+'-'+a.id+'.json')),
                                     history_path=str(self.path/(name+'-'+a.id+'.csv'))) for a in self.accounts)
            for account in accounts:
                self.state(account)
                self.history(account)
            instances.append(replace(self.main, id=name, label=name.title(), accounts=accounts))
        self.app.config = Config(tuple(instances))
        for index, instance in enumerate(instances, 1):
            self.state(instance.accounts[0], dict(self.open_trade(), sz=index*2, entry_capital=200))
        calls = []
        class Balances:
            async def get(inner, instance):
                calls.append(instance.id)
                equity = (instances.index(instance)+1)*1000
                return dict(schemaVersion=2, instance=instance.label, generatedAt=fixtures.NOW,
                    attemptedAt=fixtures.NOW, sampledAt=fixtures.NOW, status='ok', equity=equity,
                    originalEquity=1000, pnl=equity-1000, returnPct=(equity/1000-1)*100,
                    models=[dict(id=model, equity=equity if model=='M1' else None,
                        status='ok' if model=='M1' else 'not_configured',
                        sampledAt=fixtures.NOW if model=='M1' else None, walletAllocations=[])
                        for model in ('M1','M2','M3')])
            def peek(inner, instance): return None
        self.app.balances = Balances()
        return instances, calls

    def portfolio(self, **kwargs):
        return self.request('/v2/dashboard', query='view=portfolio', **kwargs)

    def set_equities(self, amounts, originals=None, statuses=None):
        get = self.app.balances.get
        async def override(instance):
            value = await get(instance)
            equity = amounts[instance.id]
            original = originals[instance.id] if originals is not None else value['originalEquity']
            value.update(equity=equity, originalEquity=original,
                pnl=equity-original if equity is not None and original is not None else None,
                returnPct=(equity/original-1)*100 if equity is not None and original is not None else None)
            value['models'][0]['equity'] = equity
            if statuses is not None:
                value['status'] = statuses.get(instance.id, 'ok')
            return value
        self.app.balances.get = override

    def test_withdrawn_instance_is_excluded_from_equity_baseline_and_all_combined_totals(self):
        self.prepare()
        self.set_equities({'main':3000, 'second':2000, 'third':100})
        status, value, _ = self.portfolio()
        self.assertEqual(200, status)
        self.assertEqual(['main','second'], [i['id'] for i in value['instances']])
        self.assertEqual(dict(equity=5000, originalEquity=2000, unrealizedPnl=60,
                              openPositions=2, returnPct=150), value['totals'])
        # The installed APK independently validates totals against the included entries.
        self.assertEqual(value['totals']['equity'], sum(i['balances']['equity'] for i in value['instances']))
        self.assertEqual(value['totals']['originalEquity'], sum(i['balances']['originalEquity'] for i in value['instances']))
        self.assertEqual((value['totals']['equity']/value['totals']['originalEquity']-1)*100,
                         value['totals']['returnPct'])

    def test_threshold_is_per_instance_inclusive_and_funded_instances_automatically_return(self):
        self.prepare()
        amounts = {'main':3000, 'second':2000, 'third':0}
        self.set_equities(amounts)
        for equity, included in ((0,False), (999.99,False), (1000,True), (1000.01,True), (0,False)):
            amounts['third'] = equity
            status, value, _ = self.portfolio()
            self.assertEqual(200, status)
            self.assertEqual(included, 'third' in [i['id'] for i in value['instances']])
            self.assertEqual(3000 if included else 2000, value['totals']['originalEquity'])

    def test_missing_or_failed_equity_is_not_treated_as_a_withdrawal(self):
        for equity, status in ((None,'failed'), (100,'failed'), (100,'unconfigured')):
            with self.subTest(equity=equity, status=status):
                self.prepare()
                self.set_equities({'main':3000, 'second':2000, 'third':equity}, statuses={'third':status})
                code, value, _ = self.portfolio()
                self.assertEqual(200, code)
                self.assertEqual('partial', value['status'])
                self.assertEqual(3, len(value['instances']))
                self.assertEqual(3000, value['totals']['originalEquity'])
                if equity is None:
                    self.assertIsNone(value['totals']['equity'])
                    self.assertIsNone(value['totals']['returnPct'])

    def test_inactive_missing_original_is_ignored_but_active_missing_original_stays_unknown(self):
        self.prepare()
        amounts={'main':3000, 'second':2000, 'third':100}
        originals={'main':1000, 'second':1000, 'third':None}
        self.set_equities(amounts, originals)
        _, value, _ = self.portfolio()
        self.assertEqual(150, value['totals']['returnPct'])
        amounts['third']=1000
        _, value, _ = self.portfolio()
        self.assertEqual(3, len(value['instances']))
        self.assertIsNone(value['totals']['originalEquity'])
        self.assertIsNone(value['totals']['returnPct'])

    def test_all_instances_below_threshold_have_no_misleading_successful_portfolio(self):
        self.prepare()
        self.set_equities({'main':100, 'second':0, 'third':999.99})
        status, value, _ = self.portfolio()
        self.assertEqual(503, status)
        self.assertEqual({'error':'Dashboard temporarily unavailable.'}, value)
        self.assertEqual(0, self.prices.refs)

    def test_primary_key_still_reads_funded_secondary_instances_when_main_is_withdrawn(self):
        self.prepare()
        self.set_equities({'main':0, 'second':2000, 'third':3000})
        status, value, _ = self.portfolio()
        self.assertEqual(200, status)
        self.assertEqual(['second','third'], [i['id'] for i in value['instances']])
        self.assertEqual(2000, value['totals']['originalEquity'])
        self.assertEqual(150, value['totals']['returnPct'])

    def test_threshold_uses_combined_instance_equity_not_each_model_or_wallet(self):
        self.prepare()
        self.set_equities({'main':1000, 'second':2000, 'third':0})
        get = self.app.balances.get
        async def split(instance):
            value = await get(instance)
            if instance.id == 'main':
                value['models'][0]['equity'] = 600
                value['models'][1].update(equity=400, status='ok', sampledAt=fixtures.NOW)
            return value
        self.app.balances.get = split
        status, value, _ = self.portfolio()
        self.assertEqual(200, status)
        self.assertEqual(['main','second'], [i['id'] for i in value['instances']])
        self.assertEqual(3000, value['totals']['equity'])
        self.assertEqual(2000, value['totals']['originalEquity'])

    def test_only_primary_key_can_read_portfolio_and_other_keys_do_no_cross_instance_work(self):
        instances, calls = self.prepare()
        keyfile = self.path/'secondary-key.txt'
        self.store.provision('second', keyfile)
        with patch.object(self.files, 'build', wraps=self.files.build) as reads:
            self.assertEqual(401, self.portfolio(token=None)[0])
            self.assertEqual(403, self.portfolio(token=keyfile.read_text().strip())[0])
            self.assertEqual([], calls)
            self.assertEqual(0, reads.call_count)
            self.assertEqual(0, self.prices.acquisitions)
        self.assertTrue(self.files.build(instances[0], 'All', 'all', self.prices, fixtures.NOW, version=2)['portfolioAvailable'])
        self.assertFalse(self.files.build(instances[1], 'All', 'all', self.prices, fixtures.NOW, version=2)['portfolioAvailable'])

    def test_three_instances_have_exact_totals_and_home_only_payloads(self):
        _, calls = self.prepare()
        status, value, _ = self.portfolio()
        self.assertEqual(200, status)
        self.assertEqual(dict(equity=6000, originalEquity=3000, unrealizedPnl=120,
                              openPositions=3, returnPct=100), value['totals'])
        self.assertEqual({'main','second','third'}, set(calls))
        self.assertEqual(3, len(value['instances']))
        self.assertEqual(0, self.prices.refs)
        for item in value['instances']:
            self.assertEqual([], item['dashboard']['trades'])
            self.assertEqual([], item['dashboard']['curve'])
            self.assertIsNone(item['dashboard']['normalizedEquity'])
            self.assertFalse(item['dashboard']['portfolioAvailable'])
        self.assertNotIn(fixtures.SECRET, str(value))
        self.assertNotIn(str(self.path), str(value))

    def test_partial_instance_never_turns_unknown_totals_into_zero(self):
        instances, _ = self.prepare()
        from pathlib import Path
        Path(instances[1].accounts[0].state_path).unlink()
        status, value, _ = self.portfolio()
        self.assertEqual(200, status)
        self.assertEqual('partial', value['status'])
        self.assertIsNone(value['totals']['unrealizedPnl'])
        self.assertIsNone(value['totals']['openPositions'])
        self.assertEqual(6000, value['totals']['equity'])
        self.assertEqual(3, len(value['instances']))

    def test_revocation_during_collection_returns_only_access_denied_and_releases_prices(self):
        self.prepare()
        original = self.app.balances.get
        async def revoke(instance):
            with contextlib.closing(sqlite3.connect(self.store.path)) as db, db:
                db.execute('DELETE FROM dashboard_keys')
            return await original(instance)
        self.app.balances.get = revoke
        status, body, _ = self.portfolio()
        self.assertEqual(401, status)
        self.assertEqual({'error':'Access denied.'}, body)
        self.assertEqual(0, self.prices.refs)

    def test_query_overrides_duplicates_legacy_routes_and_writes_are_rejected(self):
        _, calls = self.prepare()
        for query in ('view=portfolio&instance=second', 'view=portfolio&mode=real',
                      'view=portfolio&view=portfolio', 'view=portfolio&model=all'):
            self.assertEqual(400, self.request('/v2/dashboard', query=query)[0])
        self.assertEqual(400, self.request('/v1/dashboard', query='view=portfolio')[0])
        self.assertEqual(405, self.portfolio(method='POST')[0])
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
