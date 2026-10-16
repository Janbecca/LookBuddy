"""Read-only rehearsal checks. No login, camera, database writes or installs."""
import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import ipaddress
import json
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in {'http', 'https'} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.path not in {'', '/'} or parsed.query or parsed.fragment):
        raise ValueError('Expected an HTTP(S) origin without credentials or path')
    parsed.port  # validate malformed port
    if parsed.scheme == 'http' and parsed.hostname != 'localhost':
        try:
            address = ipaddress.ip_address(parsed.hostname)
        except ValueError as error:
            raise ValueError('HTTP requires localhost or a private/loopback IP') from error
        if not (address.is_loopback or address.is_private):
            raise ValueError('Remote hosts require HTTPS')
    return value.rstrip('/')


def clock_skew(date_header, now):
    if not date_header:
        return None
    server = parsedate_to_datetime(date_header)
    if server.tzinfo is None:
        server = server.replace(tzinfo=timezone.utc)
    return abs((now - server).total_seconds())


def device_count(output):
    return sum(len(parts := line.split()) == 2 and parts[1] == 'device'
               for line in output.splitlines())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', default='http://127.0.0.1:8000')
    parser.add_argument('--frontend', default='http://127.0.0.1:5189')
    parser.add_argument('--offline', action='store_true', help='Only check local artifacts')
    parser.add_argument('--require-android', action='store_true', help='Fail without an authorized adb device')
    parser.add_argument('--adb', help='Explicit adb executable; otherwise search PATH')
    args = parser.parse_args(argv)
    try:
        backend, frontend = origin(args.backend), origin(args.frontend)
    except ValueError as error:
        parser.error(str(error))
    root = Path(__file__).resolve().parent.parent
    failures = 0

    def report(status, label):
        nonlocal failures
        print(f'[{status}] {label}')
        failures += status == 'FAIL'

    for relative in [
        'apps/web/dist/index.html',
        'apps/web/dist/models/face_landmarker.task',
        'apps/web/dist/models/pose_landmarker_lite.task',
        'apps/web/dist/wasm/vision_wasm_internal.wasm',
        'apps/desktop/release/LookBuddy-win32-x64/LookBuddy.exe',
        'apps/android-local/integration-build/app/outputs/apk/debug/app-debug.apk',
    ]:
        path = root / relative
        report('PASS' if path.is_file() and path.stat().st_size > 0 else 'FAIL', relative)

    if not args.offline:
        # Direct requests: no credentials, no implicit system proxy, no redirects.
        opener = build_opener(ProxyHandler({}), NoRedirect())
        for label, address, html in [
            ('backend health', backend + '/health', False),
            ('frontend proxy health', frontend + '/health', False),
            ('frontend HTML', frontend + '/', True),
        ]:
            try:
                with opener.open(address, timeout=5) as response:
                    payload = response.read(65536)
                    valid = b'LookBuddy' in payload if html else json.loads(payload).get('status') == 'ok'
                    report('PASS' if valid else 'FAIL', label)
                    if label == 'backend health':
                        skew = clock_skew(response.headers.get('Date'), datetime.now(timezone.utc))
                        report('WARN' if skew is None else 'PASS' if skew <= 30 else 'FAIL',
                               'HTTP server clock: unavailable' if skew is None else f'HTTP server clock skew: {skew:.1f}s')
            except Exception as error:
                # No response body, URL query or environment values are logged.
                report('FAIL', f'{label}: {type(error).__name__}; check process/port/origin')
    else:
        report('WARN', 'Offline: HTTP and clock checks NOT executed')

    adb = args.adb or shutil.which('adb')
    count = 0
    if adb:
        try:
            result = subprocess.run([adb, 'devices'], capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                count = device_count(result.stdout)
        except (OSError, subprocess.TimeoutExpired):
            pass
    report('PASS' if count else 'FAIL' if args.require_android else 'WARN',
           f'Authorized Android devices: {count}; UI/Keystore still require manual acceptance')
    report('WARN', 'Physical camera, emotion validity, sleep/resume and official SDK NOT verified by this script')
    print(f'Result: {failures} failed checks. PASS is readiness evidence, not full product acceptance.')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
