import json
import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from bridge import snapshot


class SnapshotTests(unittest.TestCase):
    def test_waiting_and_recent_terminal_states(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'test.sqlite'
            now = datetime.now(timezone.utc)
            stamp = lambda d: d.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
            with sqlite3.connect(db) as con:
                con.executescript('''
                    CREATE TABLE orchestration_v2_projection_threads (thread_id TEXT PRIMARY KEY, title TEXT, project_id TEXT, updated_at TEXT, payload_json TEXT, deleted_at TEXT, archived_at TEXT, active_provider_thread_id TEXT);
                    CREATE TABLE orchestration_v2_projection_runs (run_id TEXT PRIMARY KEY, thread_id TEXT, ordinal INTEGER, status TEXT, completed_at TEXT);
                    CREATE TABLE orchestration_v2_projection_runtime_requests (thread_id TEXT, status TEXT, kind TEXT);
                    CREATE TABLE orchestration_v2_projection_provider_threads (provider_thread_id TEXT, payload_json TEXT);
                    CREATE TABLE orchestration_v2_projection_turn_items (thread_id TEXT, run_id TEXT, type TEXT, status TEXT, payload_json TEXT);
                    CREATE TABLE projection_projects (project_id TEXT PRIMARY KEY, title TEXT);
                    INSERT INTO projection_projects VALUES ('p','Test project');
                ''')
                def add(title, status, age=1, extra=None, parent=None):
                    payload = {'lineage': {'rootThreadId': parent, 'relationshipToParent': 'subagent' if parent else None}, **(extra or {})}
                    con.execute('INSERT INTO orchestration_v2_projection_threads VALUES (?,?,?,?,?,?,?,?)', (title,title,'p',stamp(now),json.dumps(payload),None,None,None))
                    con.execute('INSERT INTO orchestration_v2_projection_runs VALUES (?,?,?,?,?)', (title+'-run',title,1,status,stamp(now-timedelta(minutes=age)) if status in ('completed','failed') else None))
                add('Working','running')
                add('PR watch','completed',40, {'pullRequests':[{'source':'created','watch':{'checksPending':False,'until':'merged'},'snapshot':{'state':'open'}}]})
                add('Waiting run','waiting')
                add('Recent done','completed')
                add('Old done','completed',16)
                add('Settled','completed',1, {'settledOverride':'settled'})
                add('Child','running',parent='Working')
                add('Failed','failed')
                con.commit()
                data = snapshot(db)
                self.assertEqual(data['count'], 3)
                self.assertEqual(data['total'], 5)
                self.assertEqual({r['title']:r['status'] for r in data['rows']}, {'Working':'Working','PR watch':'Waiting','Waiting run':'Waiting','Recent done':'Done','Failed':'Failed'})
                self.assertEqual([r['status'] for r in data['rows']][:2], ['Waiting','Waiting'])
                con.execute("insert into orchestration_v2_projection_runtime_requests values ('Working','pending','user_input')")
            data = snapshot(db)
            self.assertEqual(data['rows'][0]['status'], 'Input')
            self.assertEqual(data['count'], 3)
            with sqlite3.connect(db) as con:
                for i in range(4):
                    add(f'Busy {i}', 'running')
            data = snapshot(db)
            self.assertEqual(data['count'], 7)
            self.assertEqual(data['total'], 9)
            self.assertEqual(len(data['rows']), 5)
            self.assertIn('Failed', [r['status'] for r in data['rows']])
            self.assertEqual([r['status'] for r in data['rows']][:3], ['Input', 'Waiting', 'Waiting'])
            with sqlite3.connect(db) as con:
                con.execute("DELETE FROM orchestration_v2_projection_runs WHERE thread_id = 'Failed'")
            self.assertEqual(snapshot(db)['rows'][-1]['status'], 'Done')

if __name__ == '__main__': unittest.main()
