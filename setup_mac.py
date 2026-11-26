#!/usr/bin/env python3
"""Install the local, authenticated T3 activity bridge on macOS."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import secrets
import subprocess
import sys
import tempfile
import urllib.request
import ssl

from bridge import DB, STATE, snapshot

LABEL = 'org.t3activity.companion.bridge'


def lan_address():
    route = subprocess.check_output(['/sbin/route', '-n', 'get', 'default'], text=True)
    interface = next(line.split()[-1] for line in route.splitlines() if line.strip().startswith('interface:'))
    return subprocess.check_output(['/usr/sbin/ipconfig', 'getifaddr', interface], text=True).strip()


def private_ipv4(value):
    ip = ipaddress.IPv4Address(value)
    if not any(ip in ipaddress.IPv4Network(network) for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')):
        raise ValueError('Use your Mac’s private Wi-Fi IPv4 address (10.x, 172.16–31.x or 192.168.x).')
    return str(ip)


def private_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(content)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def create_pairing(state, address, port, rotate=False):
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    state.chmod(0o700)
    config_path = state / 'host.json'
    config = json.loads(config_path.read_text()) if config_path.exists() else {}
    if (rotate or config.get('address') != address or config.get('port') != port
            or not all((state / name).is_file() for name in ('cert.pem', 'key.pem'))):
        with tempfile.TemporaryDirectory(dir=state) as temp:
            temp = Path(temp)
            openssl_config = temp / 'openssl.cnf'
            openssl_config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n'
                                      '[dn]\nCN=T3 Activity local bridge\n[extensions]\n'
                                      'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                                      'extendedKeyUsage=serverAuth\nsubjectAltName=IP:' + address + '\n')
            subprocess.run(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                            '-days', '365', '-sha256', '-config', str(openssl_config),
                            '-keyout', str(temp / 'key.pem'), '-out', str(temp / 'cert.pem')],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for name in ('cert.pem', 'key.pem'):
                private_write(state / name, (temp / name).read_bytes())
        config = {'address': address, 'port': port, 'token': secrets.token_urlsafe(32)}
        private_write(config_path, json.dumps(config).encode())
    pairing = {'version': 1, 'url': f'https://{address}:{port}/activity',
               'token': config['token'], 'certificate': (state / 'cert.pem').read_text()}
    private_write(state / 'pairing.json', json.dumps(pairing).encode())
    return pairing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--address', help='Override detected private IPv4 address')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--rotate', action='store_true', help='Generate a new pairing certificate and token')
    parser.add_argument('--uninstall', action='store_true', help='Stop and remove the launch agent (keeps private pairing files)')
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('This installer currently supports macOS only.')
    plist = Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
    domain = f'gui/{os.getuid()}'
    if args.uninstall:
        subprocess.run(['/bin/launchctl', 'bootout', domain + '/' + LABEL], capture_output=True)
        plist.unlink(missing_ok=True)
        print('Bridge stopped. Private pairing files remain in', STATE)
        return
    if not 1024 <= args.port <= 65535:
        parser.error('Choose a port from 1024 to 65535.')
    if not DB.is_file():
        parser.error('T3 Code database not found. Open T3 Code on this Mac first.')
    try:
        snapshot()
        address = private_ipv4(args.address or lan_address())
    except Exception:
        parser.error('Could not read the T3 database or detect a private IPv4 address. Check T3 Code and Wi-Fi; use --address if needed.')
    os.umask(0o077)
    pairing = create_pairing(STATE, address, args.port, args.rotate)
    private_write(STATE / 'bridge.py', Path(__file__).with_name('bridge.py').read_bytes())
    subprocess.run(['/bin/launchctl', 'bootout', domain + '/' + LABEL], capture_output=True)
    private_write(plist, plistlib.dumps({
        'Label': LABEL,
        'ProgramArguments': [sys.executable, str(STATE / 'bridge.py')],
        'RunAtLoad': True, 'KeepAlive': True,
        'StandardOutPath': str(STATE / 'bridge.log'),
        'StandardErrorPath': str(STATE / 'bridge.log'),
    }))
    subprocess.run(['/bin/launchctl', 'bootstrap', domain, str(plist)], check=True)
    tls = ssl.create_default_context(cafile=str(STATE / 'cert.pem'))
    request = urllib.request.Request(pairing['url'], headers={'Authorization': 'Bearer ' + pairing['token']})
    import time
    for attempt in range(10):
        try:
            with urllib.request.urlopen(request, context=tls, timeout=3) as response:
                json.load(response)
            break
        except (OSError, ValueError):
            if attempt == 9:
                raise SystemExit('Bridge did not respond. Check the port, firewall, and bridge.log in ' + str(STATE))
            time.sleep(0.5)
    print('Bridge running. Transfer this private file to your Android phone and import it in T3 Activity:')
    print(STATE / 'pairing.json')
    print('Use a local transfer or USB; delete the transferred copy after importing. Do not share it.')


if __name__ == '__main__':
    main()
