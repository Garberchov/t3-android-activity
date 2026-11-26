import json
import http.client
import ssl
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
import time

from bridge import Handler, Server
from setup_mac import create_pairing, private_ipv4


class PairingTests(unittest.TestCase):
    def test_private_lan_validation(self):
        self.assertEqual(private_ipv4('192.168.1.10'), '192.168.1.10')
        for address in ('0.0.0.0', '127.0.0.1', '8.8.8.8', '1.2.3.4\nCN=bad'):
            with self.assertRaises(ValueError):
                private_ipv4(address)

    def test_authenticated_tls_and_rotation(self):
        sample = {'count': 1, 'total': 1, 'rows': [{'title': 'Example agent', 'project': 'Demo', 'status': 'Working'}]}
        with tempfile.TemporaryDirectory() as temp, Server(('127.0.0.1', 0), Handler, reader=lambda: sample.copy(), interval=.025, wait=.3, initial_wait=.3) as server:
            state = Path(temp)
            pairing = create_pairing(state, '127.0.0.1', server.server_port)
            self.assertEqual(pairing, create_pairing(state, '127.0.0.1', server.server_port))
            self.assertEqual((state / 'pairing.json').stat().st_mode & 0o777, 0o600)
            self.assertEqual((state / 'key.pem').stat().st_mode & 0o777, 0o600)
            self.assertNotIn('PRIVATE KEY', pairing['certificate'])
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(state / 'cert.pem', state / 'key.pem')
            server.tls = tls
            server.authorization = 'Bearer ' + pairing['token']
            trust = ssl.create_default_context(cafile=str(state / 'cert.pem'))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                headers = {'Authorization': server.authorization, 'X-T3-Feed-Version': '2'}
                req = urllib.request.Request(pairing['url'], headers=headers)
                with urllib.request.urlopen(req, context=trust, timeout=3) as response:
                    self.assertEqual(json.load(response), sample)
                    revision = response.headers['X-T3-Revision']
                    self.assertGreaterEqual(int(response.headers['X-T3-Age-Ms']), 0)
                self.assertTrue(revision)
                sample['count'] = 2
                changed = urllib.request.Request(pairing['url'], headers={**headers, 'X-T3-Revision': revision})
                with urllib.request.urlopen(changed, context=trust, timeout=3) as response:
                    self.assertEqual(json.load(response)['count'], 2)
                    self.assertNotEqual(response.headers['X-T3-Revision'], revision)
                    revision = response.headers['X-T3-Revision']
                heartbeat = urllib.request.Request(pairing['url'], headers={**headers, 'X-T3-Revision': revision})
                started = time.monotonic()
                with urllib.request.urlopen(heartbeat, context=trust, timeout=3) as response:
                    self.assertEqual(response.headers['X-T3-Revision'], revision)
                    self.assertEqual(json.load(response)['count'], 2)
                self.assertGreaterEqual(time.monotonic() - started, .25)
                # v3 keeps the authenticated TLS socket and sends a bodyless heartbeat.
                conn = http.client.HTTPSConnection('127.0.0.1', server.server_port, context=trust, timeout=3)
                try:
                    headers3 = {**headers, 'X-T3-Feed-Version': '3'}
                    conn.request('GET', '/activity', headers=headers3)
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.loads(response.read())['count'], 2)
                    revision = response.getheader('X-T3-Revision')
                    socket = conn.sock
                    conn.request('GET', '/activity', headers={**headers3, 'X-T3-Revision': revision})
                    response = conn.getresponse()
                    self.assertEqual(response.status, 204)
                    self.assertEqual(response.read(), b'')
                    self.assertIs(conn.sock, socket)
                    sample['count'] = 3
                    conn.request('GET', '/activity', headers={**headers3, 'X-T3-Revision': revision})
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.loads(response.read())['count'], 3)
                    self.assertIs(conn.sock, socket)
                finally:
                    conn.close()
                with self.assertRaises(urllib.error.HTTPError) as unauthorized:
                    urllib.request.urlopen(pairing['url'], context=trust, timeout=3)
                self.assertEqual(unauthorized.exception.code, 401)
                with self.assertRaises(urllib.error.URLError):
                    urllib.request.urlopen(req, timeout=3)
            finally:
                server.shutdown()
                thread.join()
            self.assertNotEqual(create_pairing(state, '127.0.0.1', server.server_port, True)['token'], pairing['token'])


if __name__ == '__main__':
    unittest.main()
