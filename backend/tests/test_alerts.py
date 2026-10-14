"""Synthetic bot outboxes. No inference, trading calls, env access or network."""
import asyncio
import json
from contextlib import closing
from dataclasses import replace
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import Mock

from dashboard_backend.alerts import Alerts
from dashboard_backend.config import Config
import test_backend as fixtures
from test_backend import NOW


class AlertCase(unittest.TestCase):
    setUp = fixtures.BackendCase.setUp
    state = fixtures.BackendCase.state
    history = fixtures.BackendCase.history
    scope = fixtures.BackendCase.scope
    request = fixtures.BackendCase.request

    def outbox(self, count=1):
        path = self.path / 'dashboard_alerts.sqlite'
        db = sqlite3.connect(path)
        try:
            db.execute('CREATE TABLE IF NOT EXISTS alerts (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, timestamp INTEGER NOT NULL, message TEXT NOT NULL)')
            db.executemany('INSERT INTO alerts(id,timestamp,message) VALUES(?,?,?)', [(f'event_{i}',NOW,f'Actual event {i}') for i in range(1,count+1)])
            db.commit()
        finally: db.close()
        self.main = replace(self.main, alerts_path=str(path))
        self.app.config = Config((self.main,))
        return path
    def test_auth_scope_read_only_and_no_price_balance_or_state_queries(self):
        path=self.outbox();before=path.read_bytes()
        self.app.files=Mock(side_effect=AssertionError('No saved-state inference'))
        self.app.balances=Mock()
        self.assertEqual(401,self.request(path='/v2/alerts',token='bad')[0])
        self.assertEqual(405,self.request(path='/v2/alerts',method='POST')[0])
        self.assertEqual(400,self.request(path='/v2/alerts',query='model=M1')[0])
        status,body,_=self.request(path='/v2/alerts',query='after=0')
        self.assertEqual(200,status);self.assertEqual('ready',body['status'])
        self.assertEqual('Actual event 1',body['events'][0]['message'])
        self.assertEqual(0,self.prices.acquisitions)
        self.app.balances.get.assert_not_called();self.app.files.build.assert_not_called()
        self.assertEqual(before,path.read_bytes())
    def test_replay_batches_and_gap_detection(self):
        path=self.outbox(220)
        first=Alerts().read(self.main,NOW,1)
        self.assertEqual(100,len(first['events']));self.assertEqual(101,first['cursor']);self.assertTrue(first['hasMore'])
        next=Alerts().read(self.main,NOW,first['cursor'])
        self.assertEqual(102,next['events'][0]['sequence']);self.assertEqual(201,next['cursor'])
        final=Alerts().read(self.main,NOW,201)
        self.assertEqual(19,len(final['events']));self.assertFalse(final['hasMore'])
        bootstrap=Alerts().read(self.main,NOW,0)
        self.assertEqual(121,bootstrap['events'][0]['sequence'])
        with closing(sqlite3.connect(path)) as db, db: db.execute('DELETE FROM alerts WHERE sequence<150')
        self.assertTrue(Alerts().read(self.main,NOW,20)['gap'])
        self.assertFalse(Alerts().read(self.main,NOW,149)['gap'])
        self.assertTrue(Alerts().read(self.main,NOW,999)['gap'])
    def test_missing_corrupt_or_invalid_feed_is_explicitly_unavailable(self):
        self.assertEqual('unavailable',Alerts().read(self.main,NOW)['status'])
        path=self.outbox();path.write_bytes(b'invalid database')
        value=Alerts().read(self.main,NOW,25)
        self.assertEqual('unavailable',value['status']);self.assertEqual(25,value['cursor']);self.assertEqual([],value['events'])
        path.unlink();self.outbox()
        with closing(sqlite3.connect(path)) as db, db: db.execute("UPDATE alerts SET message=?", ('bad\x00text',))
        self.assertEqual('unavailable',Alerts().read(self.main,NOW)['status'])
    def test_second_instance_cannot_read_first_instance_events(self):
        self.outbox()
        other=replace(self.main,id='other',alerts_path=str(self.path/'other'/'dashboard_alerts.sqlite'))
        self.assertEqual([],Alerts().read(other,NOW)['events'])
        for query in ('after=-1','after=hello','after=1&after=2','after=1&instance=other'):
            self.assertEqual(400,self.request(path='/v2/alerts',query=query)[0])
    def test_defense_in_depth_hides_address_url_path(self):
        path=self.outbox()
        with closing(sqlite3.connect(path)) as db, db:
            db.execute('UPDATE alerts SET message=?', ('Wallet 0x'+'1'*40+' https://relay.invalid/private /etc/secret/path',))
        text=Alerts().read(self.main,NOW)['events'][0]['message']
        self.assertNotIn('0x',text);self.assertNotIn('relay',text);self.assertNotIn('/etc/',text)
    def test_websocket_replay_and_revocation_without_exchange_subscription(self):
        self.outbox();messages=[]
        async def run():
            incoming=asyncio.Queue();await incoming.put({'type':'websocket.connect'})
            async def send(message):
                messages.append(message)
                if message['type']=='websocket.send':
                    self.store.authenticate=Mock(return_value=None)
            self.app.config=Config((self.main,),poll_seconds=0.01)
            scope=self.scope(path='/v2/alerts/live',query='after=0');scope['type']='websocket'
            await asyncio.wait_for(self.app(scope,incoming.get,send),timeout=2)
        self.loop.run_until_complete(run())
        self.assertEqual('websocket.accept',messages[0]['type'])
        self.assertEqual(1008,messages[-1]['code']);self.assertEqual(0,self.prices.acquisitions)

    def stream_over_idle_period(self, add_event=False):
        path = self.outbox()
        messages, reads = [], []
        actual = Alerts()
        async def run():
            incoming = asyncio.Queue()
            await incoming.put({'type': 'websocket.connect'})
            def read(instance, now, after):
                reads.append(after)
                if add_event and len(reads) == 5:
                    with closing(sqlite3.connect(path)) as db, db:
                        db.execute('INSERT INTO alerts(id,timestamp,message) VALUES(?,?,?)',
                                   ('new_event', NOW + 1, 'New actual fixture alert'))
                value = actual.read(instance, now, after)
                if len(reads) == 9:
                    self.loop.call_soon_threadsafe(incoming.put_nowait, {'type': 'websocket.disconnect'})
                return value
            self.app.alerts.read = Mock(side_effect=read)
            self.app.clock = lambda: NOW + len(reads) * 10000
            self.app.config = Config((self.main,), poll_seconds=0.01)
            async def send(message):
                if message['type'] == 'websocket.send':
                    messages.append(json.loads(message['text']))
            scope = self.scope(path='/v2/alerts/live', query='after=0')
            scope['type'] = 'websocket'
            await asyncio.wait_for(self.app(scope, incoming.get, send), timeout=2)
        self.loop.run_until_complete(run())
        return messages, reads

    def test_idle_feed_sends_no_empty_ack_or_thirty_second_heartbeat(self):
        messages, reads = self.stream_over_idle_period()
        self.assertEqual(9, len(reads))
        self.assertEqual(1, len(messages))
        self.assertEqual('event_1', messages[0]['events'][0]['id'])
        self.assertEqual(0, self.prices.acquisitions)

    def test_new_event_delivers_on_next_read_without_waiting_for_idle_keepalive(self):
        messages, reads = self.stream_over_idle_period(add_event=True)
        self.assertEqual(2, len(messages))
        self.assertEqual(['event_1', 'new_event'], [m['events'][0]['id'] for m in messages])
        self.assertEqual([0, 1, 1, 1, 1, 2, 2, 2, 2], reads)

if __name__=='__main__': unittest.main()
