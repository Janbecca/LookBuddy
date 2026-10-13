from datetime import datetime, timezone
import unittest
from demo_preflight import clock_skew, device_count, origin


class PreflightTests(unittest.TestCase):
    def test_origins(self):
        for value in ['http://127.0.0.1:8000/', 'http://10.0.2.2:8000', 'https://example.com']:
            self.assertEqual(origin(value), value.rstrip('/'))
        for value in ['http://10.fake.com', 'http://8.8.8.8', 'https://u:p@example.com',
                      'http://localhost/v1', 'http://localhost?token=x', 'http://localhost:bad',
                      'ftp://localhost', 'http://localhost#token']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                origin(value)

    def test_clock(self):
        now = datetime(2026, 10, 10, tzinfo=timezone.utc)
        self.assertIsNone(clock_skew(None, now))
        self.assertEqual(clock_skew('Sat, 10 Oct 2026 00:00:00 GMT', now), 0)
        self.assertEqual(clock_skew('Fri, 09 Oct 2026 23:59:00 GMT', now), 60)

    def test_devices(self):
        self.assertEqual(device_count('List of devices attached\na\tdevice\nb\tunauthorized\nc\toffline\n'), 1)
        self.assertEqual(device_count('List of devices attached\n'), 0)


if __name__ == '__main__':
    unittest.main()
