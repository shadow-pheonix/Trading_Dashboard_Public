"""Synthetic mark-P&L samples, opt-in delivery, and position-set boundaries."""
import asyncio
from dataclasses import replace
import json
from pathlib import Path
from unittest.mock import Mock
import unittest

from dashboard_backend.config import Config
from dashboard_backend.validation import encode
import test_alerts as alert_fixtures
import test_backend as fixtures
from test_backend import NOW, TOKEN


class PnlAlertCase(unittest.TestCase):
    setUp = fixtures.BackendCase.setUp
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    open_trade = fixtures.BackendCase.open_trade
    row = fixtures.BackendCase.row
    scope = fixtures.BackendCase.scope
    outbox = alert_fixtures.AlertCase.outbox

    def sample(self):
        return self.files.pnl_sample(self.main, self.prices, NOW)

    def test_combines_all_models_and_wallets_with_the_same_calculation_as_home(self):
        # LONG +20, SHORT -10, flat models 0; no balance or ticker input.
        self.state(trade=self.open_trade())
        self.state(self.accounts[1], self.open_trade('SHORT') | {'sz': 1})
        value = self.sample()
        home = self.files.build(self.main, 'All', 'all', self.prices, NOW, version=2)
        self.assertEqual(10, value['value'])
        self.assertEqual(home['metrics']['unrealizedPnl'], value['value'])
        self.assertEqual(2, value['activeCount'])
        self.assertEqual(NOW, value['priceUpdatedAt'])
        self.assertEqual(64, len(value['positionsKey']))
        self.assertEqual({'positionsKey','value','activeCount','sampledAt','priceUpdatedAt'}, set(value))
        self.assertNotIn('entry_price', encode(value).decode())
        self.assertNotIn(str(self.path), encode(value).decode())
        # One-wallet deployment uses the same projection.
        self.assertEqual(20, self.files.pnl_sample(replace(self.main, accounts=self.accounts[:1]), self.prices, NOW)['value'])

    def test_flat_is_explicit_and_stale_missing_or_invalid_active_prices_fail_closed(self):
        self.prices.available = False
        self.assertEqual(0, self.sample()['value'])
        self.assertEqual(0, self.sample()['activeCount'])
        self.assertIsNone(self.sample()['priceUpdatedAt'])
        self.state(trade=self.open_trade())
        self.assertIsNone(self.sample())
        for price in ((110,NOW-15001),(110,NOW+1),(float('nan'),NOW)):
            self.prices.get = Mock(return_value=price)
            self.assertIsNone(self.sample())
        self.prices.get = Mock(return_value=(110,NOW-15000))
        self.assertEqual(20,self.sample()['value'])

    def test_position_identity_changes_for_add_close_reverse_trim_and_entry_but_not_prices_or_sl(self):
        trade=self.open_trade()
        self.state(trade=trade)
        baseline=self.sample()['positionsKey']
        self.state(trade=trade | {'sl': 105})
        self.assertEqual(baseline,self.sample()['positionsKey'])
        self.prices.get=Mock(return_value=(120,NOW))
        self.assertEqual(baseline,self.sample()['positionsKey'])
        for changed in (trade | {'sz':1},trade | {'side':'SHORT'},trade | {'entry_price':101},None):
            self.state(trade=changed)
            self.assertNotEqual(baseline,self.sample()['positionsKey'])
        self.state(trade=trade)
        self.state(self.accounts[1],trade)
        self.assertNotEqual(baseline,self.sample()['positionsKey'])
        # Config ordering does not reset a position set.
        self.assertEqual(self.sample()['positionsKey'], self.files.pnl_sample(replace(self.main, accounts=tuple(reversed(self.accounts))),self.prices,NOW)['positionsKey'])

    def test_bad_or_unknown_state_and_close_write_races_never_create_partial_totals(self):
        self.state(trade=self.open_trade())
        for changed in (self.open_trade() | {'entry_time':'unknown'},self.open_trade() | {'sz':0}):
            self.state(trade=changed)
            self.assertIsNone(self.sample())
        self.state(trade=self.open_trade())
        # Use an exact open timestamp to model a crossed close write.
        row=self.row(); row[2]=self.open_trade()['entry_time']; self.history(rows=[row])
        self.assertIsNone(self.sample())
        self.history()
        Path(self.accounts[1].state_path).write_text('{bad json')
        self.assertIsNone(self.sample())
        self.state(self.accounts[1])
        Path(self.accounts[0].history_path).unlink()
        self.assertIsNone(self.sample())

    def stream(self, header=True, after_send=None, before=None, token=TOKEN):
        if not (self.path / "dashboard_alerts.sqlite").exists():
            self.outbox()
        self.app.balances=Mock()
        self.app.files.build=Mock(side_effect=AssertionError('No portfolio/history projection'))
        self.prices.wait_ready=Mock(side_effect=AssertionError('Bot events must not wait for mark readiness'))
        messages=[]
        async def run():
            incoming=asyncio.Queue(); await incoming.put({'type':'websocket.connect'})
            async def send(message):
                messages.append(message)
                if message['type']=='websocket.send':
                    if after_send:
                        await after_send(message,incoming)
                    else:
                        await incoming.put({'type':'websocket.disconnect'})
            self.app.config=Config((self.main,),poll_seconds=0.01)
            scope=self.scope(path='/v2/alerts/live',query='after=0',token=token)
            if header:
                scope['headers'].append((b'x-dashboard-pnl',b'1'))
            if before:
                before()
            await asyncio.wait_for(self.app(scope,incoming.get,send),2)
        self.loop.run_until_complete(run())
        self.assertEqual(0,self.prices.refs)
        self.app.balances.get.assert_not_called()
        self.app.files.build.assert_not_called()
        return messages

    def test_opt_in_receives_complete_instance_pnl_without_balances_or_portfolio_and_releases_subscription(self):
        self.state(trade=self.open_trade())
        messages=self.stream()
        value=json.loads(next(m['text'] for m in messages if m['type']=='websocket.send'))
        self.assertEqual(20,value['openPnl']['value'])
        self.assertEqual('event_1',value['events'][0]['id'])
        self.assertEqual(1,self.prices.acquisitions)

    def test_legacy_stream_keeps_outbox_only_behavior_and_unauthenticated_opt_in_does_no_work(self):
        messages=self.stream(header=False)
        value=json.loads(next(m['text'] for m in messages if m['type']=='websocket.send'))
        self.assertNotIn('openPnl',value)
        self.assertEqual(0,self.prices.acquisitions)
        self.assertEqual('websocket.close',self.stream(token='bad')[0]['type'])
        self.assertEqual(0,self.prices.acquisitions)

    def test_missing_prices_do_not_delay_actual_bot_events_and_pnl_failure_does_not_advance_it(self):
        self.state(trade=self.open_trade());self.prices.available=False
        messages=self.stream()
        value=json.loads(next(m['text'] for m in messages if m['type']=='websocket.send'))
        self.assertIsNone(value['openPnl']);self.assertEqual('ready',value['status'])
        self.assertEqual(1,value['cursor']);self.assertEqual('event_1',value['events'][0]['id'])

    def test_price_change_delivers_an_empty_event_batch_with_new_pnl_and_unchanged_cursor(self):
        self.state(trade=self.open_trade())
        frames=[]
        async def changed(message,incoming):
            frames.append(json.loads(message['text']))
            if len(frames)==1:
                self.prices.get=Mock(return_value=(700,NOW))
            else:
                await incoming.put({'type':'websocket.disconnect'})
        self.stream(after_send=changed)
        self.assertEqual([20,1200],[f['openPnl']['value'] for f in frames])
        self.assertEqual([],frames[1]['events']);self.assertEqual(1,frames[1]['cursor'])

    def test_same_value_samples_do_not_send_idle_heartbeats_and_revocation_closes_opt_in(self):
        frames=[]
        reads=[]
        original=self.files.pnl_sample
        def sample(instance,prices,now):
            reads.append(1)
            if len(reads)==4:
                self.store.authenticate=Mock(return_value=None)
            return original(instance,prices,now+len(reads))
        self.files.pnl_sample=sample
        async def on_frame(message,incoming): frames.append(json.loads(message['text']))
        messages=self.stream(after_send=on_frame)
        self.assertEqual(1,len(frames));self.assertEqual(1008,messages[-1]['code'])
        self.assertGreaterEqual(len(reads),4)


if __name__ == '__main__':
    unittest.main()
