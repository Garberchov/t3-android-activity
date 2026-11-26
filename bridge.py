"""Read-only local T3 agent activity endpoint for the paired phone."""
from contextlib import closing, nullcontext
import hashlib
import hmac
import json
import sqlite3
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATE = Path.home() / 'Library/Application Support/T3AndroidActivity'
DB = Path.home() / '.t3/userdata/statev2.sqlite'


def snapshot(db=DB, connection=None):
    # Match T3's visible root threads, keep awaiting background work, and retain
    # unsettled completion results for the same 15-minute window as its iOS card.
    with (closing(sqlite3.connect(db.as_uri() + '?mode=ro', uri=True, timeout=2)) if connection is None else nullcontext(connection)) as con:
        rows = con.execute('''
            WITH live AS MATERIALIZED (
                SELECT COALESCE(json_extract(t.payload_json, '$.lineage.rootThreadId'), t.thread_id) AS root_id,
                    MAX(CASE r.status WHEN 'waiting' THEN 1 ELSE 2 END) AS working
                FROM orchestration_v2_projection_runs r
                JOIN orchestration_v2_projection_threads t ON t.thread_id = r.thread_id
                WHERE r.status IN ('preparing', 'starting', 'running', 'waiting')
                    AND t.deleted_at IS NULL AND t.archived_at IS NULL
                GROUP BY 1
            ), root_state AS MATERIALIZED (
                SELECT t.thread_id, t.title, t.project_id, t.updated_at, t.payload_json,
                    r.status AS run_status, r.completed_at, r.run_id, live.working,
                    EXISTS (SELECT 1 FROM orchestration_v2_projection_runtime_requests q
                        WHERE q.thread_id = t.thread_id AND q.status = 'pending' AND q.kind = 'user_input') AS input_wait,
                    EXISTS (SELECT 1 FROM orchestration_v2_projection_runtime_requests q
                        WHERE q.thread_id = t.thread_id AND q.status = 'pending' AND q.kind NOT IN ('user_input', 'auth_refresh')) AS approval_wait,
                    (
                        EXISTS (SELECT 1 FROM json_each(t.payload_json, '$.pullRequests') pr
                            WHERE json_type(pr.value, '$.watch') = 'object'
                                AND json_extract(pr.value, '$.source') IS NOT 'stack-dismissed'
                                AND (json_extract(pr.value, '$.watch.checksPending') IS NOT 0
                                    OR json_extract(pr.value, '$.watch.until') = 'merged'
                                    OR json_extract(pr.value, '$.snapshot.state') = 'merged'))
                        OR EXISTS (SELECT 1 FROM orchestration_v2_projection_provider_threads pt,
                            json_each(pt.payload_json, '$.pendingBackgroundTasks') task
                            WHERE pt.provider_thread_id = t.active_provider_thread_id
                                AND (json_extract(task.value, '$.awaited') = 1
                                    OR json_extract(task.value, '$.kind') IN ('subagent', 'background_task')))
                        OR EXISTS (SELECT 1 FROM orchestration_v2_projection_turn_items item
                            WHERE item.thread_id = t.thread_id AND item.type IN ('subagent', 'dynamic_tool')
                                AND item.status IN ('running', 'waiting')
                                AND (item.type != 'dynamic_tool' OR json_extract(item.payload_json, '$.input.persistent') IS NOT 1)
                                AND NOT EXISTS (SELECT 1 FROM orchestration_v2_projection_runs rolled
                                    WHERE rolled.run_id = item.run_id AND rolled.status = 'rolled_back'))
                    ) AS awaiting_background
                FROM orchestration_v2_projection_threads t
                LEFT JOIN orchestration_v2_projection_runs r ON r.run_id = (
                    SELECT rr.run_id FROM orchestration_v2_projection_runs rr
                    WHERE rr.thread_id = t.thread_id AND rr.status != 'rolled_back'
                    ORDER BY rr.ordinal DESC LIMIT 1)
                LEFT JOIN live ON live.root_id = t.thread_id
                WHERE t.deleted_at IS NULL AND t.archived_at IS NULL
                    AND json_extract(t.payload_json, '$.lineage.relationshipToParent') IS NOT 'subagent'
            ), classified AS (
                SELECT t.title, COALESCE(p.title, '') AS project, t.updated_at, t.completed_at,
                    CASE
                        WHEN approval_wait THEN 'Approval'
                        WHEN input_wait THEN 'Input'
                        WHEN working = 2 THEN 'Working'
                        WHEN working = 1 THEN 'Waiting'
                        WHEN run_status IS NOT 'failed' AND awaiting_background THEN 'Waiting'
                        WHEN run_status IN ('completed', 'failed')
                            AND completed_at >= strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-15 minutes')
                            AND json_extract(t.payload_json, '$.settledOverride') IS NOT 'settled'
                            AND NOT EXISTS (SELECT 1 FROM orchestration_v2_projection_turn_items item
                                WHERE item.thread_id = t.thread_id AND item.run_id = t.run_id AND item.type = 'run_interrupt_request')
                            THEN CASE run_status WHEN 'failed' THEN 'Failed' ELSE 'Done' END
                    END AS status
                FROM root_state t LEFT JOIN projection_projects p ON p.project_id = t.project_id
            )
            SELECT title, project, status FROM classified WHERE status IS NOT NULL
            ORDER BY CASE status WHEN 'Approval' THEN 0 WHEN 'Input' THEN 0 WHEN 'Waiting' THEN 1
                WHEN 'Working' THEN 2 WHEN 'Failed' THEN 3 ELSE 4 END,
                CASE WHEN status IN ('Done', 'Failed') THEN completed_at ELSE updated_at END DESC
        ''').fetchall()
    displayed = rows[:5]
    # Keep the latest completion visible even when five or more agents are active.
    # Never displace an actionable waiting/input/approval row.
    terminal = next((r for r in rows if r[2] in ('Done', 'Failed')), None)
    if terminal and terminal not in displayed and displayed[-1][2] == 'Working':
        displayed[-1] = terminal
    return {
        'count': sum(status not in ('Done', 'Failed') for _, _, status in rows),
        'total': len(rows),
        'rows': [{'title': title, 'project': project, 'status': status} for title, project, status in displayed],
    }


class SnapshotReader:
    """Keep a read-only SQLite page cache warm; skip unchanged database revisions."""

    def __init__(self, db=DB):
        self.db = db
        self.connection = None
        self.identity = self.version = self.data = None
        self.last_read = 0

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None
        self.version = self.data = None
        self.last_read = 0

    def __call__(self):
        stat = self.db.stat()
        identity = (stat.st_dev, stat.st_ino)
        if self.connection is None or identity != self.identity:
            self.close()
            self.connection = sqlite3.connect(self.db.as_uri() + '?mode=ro', uri=True, timeout=2)
            self.connection.execute('PRAGMA query_only = ON')
            self.connection.execute('PRAGMA cache_size = -16384')
            self.identity = identity
        try:
            version = self.connection.execute('PRAGMA data_version').fetchone()[0]
            # Periodic reads also expire recently completed threads without new DB writes.
            if self.data is None or version != self.version or time.monotonic() - self.last_read >= 5:
                self.data = snapshot(self.db, self.connection)
                self.version = version
                self.last_read = time.monotonic()
            return self.data
        except sqlite3.Error:
            self.close()
            raise


class ActivityCache:
    """Refresh independently of HTTP so slow database reads never block a fresh response."""

    def __init__(self, reader, interval=2, wait=8, max_age=12, initial_wait=15):
        self.reader = reader
        self.interval, self.wait, self.max_age = interval, wait, max_age
        self.initial_wait = max(wait, initial_wait)
        self.condition = threading.Condition()
        self.body = self.revision = None
        self.refreshed = self.demand = 0
        self.closed = False
        self.worker = threading.Thread(target=self.refresh, daemon=True)
        self.worker.start()

    def refresh(self):
        try:
            self.refresh_loop()
        finally:
            if hasattr(self.reader, 'close'):
                self.reader.close()

    def refresh_loop(self):
        while True:
            with self.condition:
                while not self.closed and time.monotonic() - self.demand > 30:
                    self.condition.wait()
                if self.closed:
                    return
            try:
                data = json.dumps(self.reader(), ensure_ascii=False).encode()
                revision = hashlib.sha256(data).hexdigest()
            except (sqlite3.Error, OSError):
                pass
            else:
                with self.condition:
                    self.body, self.revision = data, revision
                    self.refreshed = time.monotonic()
                    self.condition.notify_all()
            with self.condition:
                self.condition.wait_for(lambda: self.closed, timeout=self.interval)
                if self.closed:
                    return

    def read(self, since=None, allow_stale=False):
        with self.condition:
            self.demand = time.monotonic()
            self.condition.notify_all()
            initial = self.body is None or self.demand - self.refreshed > self.max_age
            deadline = self.demand + (self.initial_wait if initial else self.wait)
            while not self.closed:
                now = time.monotonic()
                age = now - self.refreshed
                fresh = self.body is not None and age <= self.max_age
                if fresh and (since != self.revision or now >= deadline):
                    return self.body, self.revision, int(age * 1000)
                if now >= deadline:
                    # A new client understands source age and can show "Syncing"
                    # instead of mistaking a slow SQLite read for a lost connection.
                    if allow_stale and self.body is not None and age <= 30:
                        return self.body, self.revision, int(age * 1000)
                    break
                self.condition.wait(deadline - now)
            raise sqlite3.OperationalError('Activity feed unavailable')

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, reader=None, **cache_options):
        super().__init__(address, handler)
        self.cache = ActivityCache(reader or SnapshotReader(), **cache_options)

    def server_close(self):
        self.cache.close()
        super().server_close()

    def process_request_thread(self, request, client_address):
        # A stalled client's TLS handshake must not block the listener.
        request.settimeout(5)
        try:
            request = self.tls.wrap_socket(request, server_side=True)
        except (OSError, ssl.SSLError):
            request.close()
            return
        super().process_request_thread(request, client_address)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), self.server.authorization.encode()):
            self.send_error(401)
            return
        if self.path != '/activity':
            self.send_error(404)
            return
        try:
            data, revision, age_ms = self.server.cache.read(
                self.headers.get('X-T3-Revision'), self.headers.get('X-T3-Feed-Version') == '2')
        except sqlite3.Error:
            self.send_error(503)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-T3-Revision', revision)
        self.send_header('X-T3-Age-Ms', str(age_ms))
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except OSError:
            pass  # Client changed networks or closed the request.


if __name__ == '__main__':
    config = json.loads((STATE / 'host.json').read_text())
    server = Server((config['address'], config['port']), Handler)
    server.authorization = 'Bearer ' + config['token']
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(STATE / 'cert.pem', STATE / 'key.pem')
    server.tls = tls
    server.serve_forever()
