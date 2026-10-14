"""Source isolation and combined edge calculations, using synthetic CSVs only."""
import csv
from dataclasses import replace
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from dashboard_backend.config import Config, Instance, load_config
from dashboard_backend.validation import InvalidData, decode
from test_backend import BackendCase, COLUMNS, NOW


class HistoryModeCase(BackendCase):
    # Reuse fixtures/helpers without rerunning the inherited baseline test methods.
    def setUp(self):
        super().setUp()
        accounts = tuple(replace(a, edge_model=f'M1-{a.id}') for a in self.accounts)
        self.edge_path = self.path / 'edge.csv'
        self.main = Instance('main', 'Main', accounts, str(self.edge_path))
        self.config = Config((self.main,))
        self.app.config = self.config
        self.accounts = accounts
        self.write_edge()

    def write_edge(self, rows=None):
        if rows is None:
            backtest = self.row(number='same-number', pnl='12', source='backtest')
            live = self.row(offset=500, number='same-number', pnl='-2', source='live')
            other = self.row(number='b', pnl='5', source='backtest')
            outside = self.row(number='foreign', pnl='9999', source='live')
            backtest[-1] = live[-1] = 'M1-a'
            other[-1] = 'M1-b'
            outside[-1] = 'Unconfigured-model'
            rows = [backtest, live, other, outside]
        with self.edge_path.open('w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(COLUMNS)
            writer.writerows(rows)
        import os
        os.utime(self.edge_path, ns=((NOW - 2000) * 1000000,) * 2)

    def test_edge_combines_both_sources_without_regular_files_or_prices(self):
        for account in self.accounts:
            Path(account.state_path).unlink()
            Path(account.history_path).unlink()
        with patch.object(self.prices, 'get', side_effect=AssertionError('No market reads')):
            status, body, _ = self.request(query='period=All&account=all&mode=edge')
        self.assertEqual(200, status)
        self.assertEqual(15, body['metrics']['realizedPnl'])
        self.assertEqual(3, body['metrics']['closedTrades'])
        self.assertAlmostEqual(200 / 3, body['metrics']['winRatePct'])
        self.assertEqual({'backtest', 'live'}, {t['source'] for t in body['trades']})
        self.assertEqual(15, body['curve'][-1]['pnl'])
        self.assertEqual(15, body['monthlyReturns'][0]['pnl'])
        self.assertEqual([], body['positions'])
        self.assertEqual([], body['strategies'])
        self.assertEqual('edge', body['historyMode'])
        self.assertEqual('edge_csv', body['dataSource'])
        self.assertFalse(body['live'])
        self.assertIsNone(body['stateUpdatedAt'])
        self.assertIsNone(body['priceUpdatedAt'])
        self.assertEqual(0, self.prices.acquisitions)

    def test_real_uses_only_regular_history_even_if_edge_is_invalid(self):
        self.history(rows=[self.row(pnl='9')])
        self.edge_path.write_text('invalid edge data')
        status, body, _ = self.request()
        self.assertEqual(200, status)
        self.assertEqual(9, body['metrics']['realizedPnl'])
        self.assertEqual({'live'}, {t['source'] for t in body['trades']})
        self.assertEqual('real', body['historyMode'])
        self.assertNotIn(str(self.edge_path), self.files.history_cache)

    def test_edge_period_account_scope_and_empty_period(self):
        status, body, _ = self.request(query='period=1M&account=a&mode=edge')
        self.assertEqual(200, status)
        self.assertEqual(10, body['metrics']['realizedPnl'])
        self.assertEqual(50, body['metrics']['winRatePct'])
        self.assertEqual({'a'}, {t['accountId'] for t in body['trades']})
        self.assertEqual(2, len({t['id'] for t in body['trades']}))
        old = self.row(offset=40 * 86400000, source='backtest')
        old[-1] = 'M1-a'
        self.write_edge([old])
        self.assertEqual(0, self.request(query='period=1M&account=a&mode=edge')[1]['metrics']['closedTrades'])
        self.assertEqual(1, self.request(query='period=3M&account=a&mode=edge')[1]['metrics']['closedTrades'])

    def test_edge_socket_scope_auth_revocation_and_no_market_activation(self):
        events = self.websocket(query='period=All&account=a&mode=edge')
        body = decode(events[1]['text'])
        self.assertEqual('edge', body['historyMode'])
        self.assertEqual(10, body['metrics']['realizedPnl'])
        self.assertEqual(0, self.prices.acquisitions)
        self.assertEqual(0, self.app.viewers)
        self.assertEqual(0, self.prices.refs)
        self.files.history_cache.clear()
        self.assertEqual(401, self.request(query='mode=edge', token=None)[0])
        self.assertEqual({}, self.files.history_cache)
        self.assertEqual(403, self.request(query='mode=edge&account=foreign')[0])
        events = self.websocket(query='mode=edge', after_frame=lambda: self.store.revoke('main'), disconnect=False)
        self.assertEqual({'type': 'websocket.close', 'code': 1008}, events[-1])
        self.assertEqual(0, self.prices.acquisitions)

    def test_edge_invalid_mode_configuration_append_and_duplicates_fail_closed(self):
        for query in ['mode=backtest', 'mode=edge&mode=real', 'mode=', 'mode=edge&extra=x']:
            self.assertEqual(400, self.request(query=query)[0])
        self.assertEqual(0, self.prices.acquisitions)
        self.app.config = Config((replace(self.main, edge_path=''),))
        self.assertEqual(503, self.request(query='mode=edge')[0])
        self.app.config = self.config
        self.edge_path.write_text(self.edge_path.read_text().rstrip('\n'))
        self.assertEqual(503, self.request(query='mode=edge')[0])
        row = self.row(source='backtest'); row[-1] = 'M1-a'
        self.write_edge([row, row])
        self.assertEqual(503, self.request(query='mode=edge')[0])
        row[-2] = 'offline_backtest'
        self.write_edge([row])
        self.assertEqual(503, self.request(query='mode=edge')[0])

    def test_edge_cache_refreshes_on_append_and_unknown_open_time_is_supported(self):
        self.assertEqual(15, self.request(query='mode=edge')[1]['metrics']['realizedPnl'])
        row = self.row(number='manual', source='live', pnl='3')
        row[2] = 'unknown'; row[-1] = 'M1-a'
        self.write_edge([row])
        body = self.request(query='mode=edge')[1]
        self.assertEqual(3, body['metrics']['realizedPnl'])
        self.assertIsNone(body['trades'][0]['openedAt'])

    def test_edge_config_mappings_are_explicit_unique_and_paths_private(self):
        value = {'instances': [{'id': 'main', 'label': 'Main', 'edgePath': str(self.edge_path),
            'accounts': [{'id': a.id, 'label': a.label, 'statePath': a.state_path,
                          'historyPath': a.history_path, 'strategy': a.strategy, 'edgeModel': a.edge_model}
                         for a in self.accounts]}]}
        path = self.path / 'config.json'; path.write_text(json.dumps(value))
        self.assertEqual(str(self.edge_path), load_config(path).instances[0].edge_path)
        self.assertNotIn(str(self.edge_path), repr(load_config(path)))
        value['instances'][0]['accounts'][1]['edgeModel'] = 'M1-a'
        path.write_text(json.dumps(value))
        with self.assertRaises(InvalidData):
            load_config(path)


def load_tests(loader, tests, pattern):
    # Reuse the fixture while running only the tests declared in this module.
    return unittest.TestSuite(HistoryModeCase(name) for name in HistoryModeCase.__dict__
                              if name.startswith('test_'))

if __name__ == '__main__':
    unittest.main()
