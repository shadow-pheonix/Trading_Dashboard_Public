"""Wallet allocation percentages use existing demand-only equity samples."""
import asyncio
from dataclasses import replace
import unittest
from unittest.mock import Mock

from dashboard_backend.balances import Balances
from dashboard_backend.config import Account, Instance


class WalletAllocationCase(unittest.TestCase):
    def instance(self, pools):
        accounts = tuple(Account(f'{group}-{model}', model, '/synthetic/state.json',
                                 strategy=model, wallet=group, public_address=f'{group}-{model}')
                         for group, values in pools.items() for model in values)
        return Instance('synthetic', 'Synthetic', accounts)

    def fetch(self, pools, instance=None):
        def query(address):
            group, model = address.split('-', 1)
            value = pools[group][model]
            if isinstance(value, Exception):
                raise value
            return value
        calls = Mock(side_effect=query)
        instance = instance or self.instance(pools)
        balances = Balances(calls, clock=lambda: 1000, monotonic=lambda: 0)
        async def exercise():
            body = await balances.get(instance)
            self.assertEqual(body, await balances.get(instance))
            self.assertEqual(len(instance.accounts), calls.call_count)
            return body
        return asyncio.run(exercise())

    def test_unequal_wallet_totals_use_local_pools_and_keep_combined_dollars(self):
        pools = {'B': {'M1': 2000, 'M2': 1000, 'M3': 2000},
                 'A': {'M1': 500, 'M2': 200, 'M3': 300}}
        body = self.fetch(pools)
        self.assertEqual(6000, body['equity'])
        self.assertEqual([2500, 1200, 2300], [m['equity'] for m in body['models']])
        for model, expected in zip(body['models'], [(50, 40), (20, 20), (30, 40)]):
            self.assertEqual([{'wallet': 'A', 'pct': expected[0]},
                              {'wallet': 'B', 'pct': expected[1]}], model['walletAllocations'])
        self.assertNotIn('publicAddress', str(body))
        self.assertNotIn('/synthetic', str(body))

    def test_single_wallet_a_or_b_zero_equity_model_and_missing_models(self):
        for group in ('A', 'B'):
            body = self.fetch({group: {'M1': 400, 'M2': 0, 'M3': 600}})
            for model, pct in zip(body['models'], (40, 0, 60)):
                self.assertEqual([{'wallet': group, 'pct': pct}], model['walletAllocations'])
            body = self.fetch({group: {'M1': 400}})
            self.assertEqual([{'wallet': group, 'pct': 100}], body['models'][0]['walletAllocations'])
            self.assertEqual([], body['models'][1]['walletAllocations'])

    def test_zero_or_negative_pool_has_no_percentage_but_other_wallet_is_valid(self):
        for equity in (0, -100):
            body = self.fetch({'A': {'M1': equity}, 'B': {'M1': 100, 'M2': 100}})
            self.assertEqual([{'wallet': 'A', 'pct': None}, {'wallet': 'B', 'pct': 50}],
                             body['models'][0]['walletAllocations'])

    def test_partial_or_unconfigured_equity_suppresses_all_percentages(self):
        pools = {'A': {'M1': 400, 'M2': 200, 'M3': 400},
                 'B': {'M1': 2000, 'M2': RuntimeError('synthetic-private'), 'M3': 2000}}
        instance = self.instance(pools)
        missing_address = replace(instance, accounts=tuple(
            replace(a, public_address='') if a.id == 'B-M2' else a for a in instance.accounts))
        def fetch_equity(address):
            group, model = address.split('-', 1)
            value = pools[group][model]
            if isinstance(value, Exception):
                raise value
            return value
        for instance in (instance, missing_address):
            # Missing-address accounts never make an exchange query.
            query = Mock(side_effect=fetch_equity)
            body = asyncio.run(Balances(query, clock=lambda: 1000).get(instance))
            self.assertIsNone(body['equity'])
            self.assertEqual(2400, body['models'][0]['equity'])
            self.assertTrue(all(a['pct'] is None for m in body['models'] for a in m['walletAllocations']))
            self.assertNotIn('synthetic-private', str(body))
            self.assertEqual(6 if instance.accounts[4].public_address else 5, query.call_count)

    def test_legacy_wallet_mapping_and_unknown_mapping_do_not_guess(self):
        pools = {'A': {'M1': 400, 'M2': 600}}
        instance = self.instance(pools)
        legacy = replace(instance, accounts=tuple(replace(a, wallet='', edge_model=a.strategy+'-Fast')
                                                 for a in instance.accounts))
        self.assertEqual([{'wallet': 'A', 'pct': 40}], self.fetch(pools, legacy)['models'][0]['walletAllocations'])
        unknown = replace(instance, accounts=(instance.accounts[0], replace(instance.accounts[1], wallet='')))
        body = self.fetch(pools, unknown)
        self.assertEqual([{'wallet': 'A', 'pct': None}], body['models'][0]['walletAllocations'])
        self.assertEqual([], body['models'][1]['walletAllocations'])

    def test_refresh_replaces_percentages_without_mutating_cached_samples(self):
        pools = {'A': {'M1': 400, 'M2': 600}}
        clock = [0]
        query = Mock(side_effect=[400, 600, 500, RuntimeError('synthetic-private')])
        balances = Balances(query, clock=lambda: 1000, monotonic=lambda: clock[0])
        async def exercise():
            instance = self.instance(pools)
            first = await balances.get(instance)
            clock[0] = 10
            failed = await balances.get(instance)
            self.assertIsNone(failed['models'][0]['walletAllocations'][0]['pct'])
            self.assertEqual(40, first['models'][0]['walletAllocations'][0]['pct'])
            self.assertEqual(4, query.call_count)
        asyncio.run(exercise())


if __name__ == '__main__':
    unittest.main()
