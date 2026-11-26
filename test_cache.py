import json
import sqlite3
import threading
import time
import unittest

from bridge import ActivityCache


class CacheTests(unittest.TestCase):
    def test_waits_for_change_and_handles_unchanged_heartbeat(self):
        current = {'count': 1, 'total': 1, 'rows': []}
        cache = ActivityCache(lambda: current.copy(), interval=.025, wait=.5, max_age=.15, initial_wait=.5)
        try:
            body, revision, _ = cache.read()
            self.assertEqual(json.loads(body)['count'], 1)
            # The reader runs independently while the client waits.
            def change():
                current['count'] = 2
            timer = threading.Timer(.08, change)
            timer.start()
            start = time.monotonic()
            changed, next_revision, _ = cache.read(revision)
            timer.join()
            self.assertNotEqual(next_revision, revision)
            self.assertEqual(json.loads(changed)['count'], 2)
            self.assertLess(time.monotonic() - start, .5)
            start = time.monotonic()
            self.assertEqual(cache.read(next_revision)[:2], (changed, next_revision))
            self.assertGreaterEqual(time.monotonic() - start, .45)
        finally:
            cache.close()

    def test_stale_snapshot_is_not_reported_as_fresh(self):
        fail = threading.Event()
        def reader():
            if fail.is_set():
                raise sqlite3.OperationalError('temporary')
            return {'count': 1}
        cache = ActivityCache(reader, interval=.02, wait=.2, max_age=.08, initial_wait=.2)
        try:
            _, revision, _ = cache.read()
            fail.set()
            with self.assertRaises(sqlite3.OperationalError):
                cache.read(revision)
            stale, same_revision, age = cache.read(revision, allow_stale=True)
            self.assertEqual(same_revision, revision)
            self.assertGreater(age, 80)
            self.assertEqual(json.loads(stale)['count'], 1)
            fail.clear()
            body, _, _ = cache.read()
            self.assertEqual(json.loads(body)['count'], 1)
        finally:
            cache.close()


if __name__ == '__main__':
    unittest.main()
