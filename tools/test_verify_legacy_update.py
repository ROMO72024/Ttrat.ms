import hashlib
import json
from pathlib import Path
import unittest
from verify_legacy_update import check_certificate, check_apk_reports


class UpdateChecks(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads(Path(__file__).with_name('legacy-apk.json').read_text())
        self.package = "package: name='ps.ghars.sessions.debug' versionCode='5' versionName='1.1.1'\n"
        self.signature = 'Verifies\nSigner #1 certificate SHA-256 digest: ' + self.expected['certificateSha256'] + '\n'

    def test_accept_original_identity_and_newer_version(self):
        check_apk_reports(self.package, self.signature, self.expected)

    def test_reject_previous_wrong_release_identity(self):
        with self.assertRaises(ValueError):
            check_apk_reports(self.package.replace('sessions.debug', 'sessions'), self.signature, self.expected)

    def test_reject_different_signing_key(self):
        with self.assertRaises(ValueError):
            check_apk_reports(self.package, self.signature.replace(self.expected['certificateSha256'], 'ab' * 32), self.expected)

    def test_reject_unchanged_or_lower_version(self):
        for version in ['3', '2']:
            with self.assertRaises(ValueError):
                check_apk_reports(self.package.replace("versionCode='5'", "versionCode='" + version + "'"), self.signature, self.expected)

    def test_reject_unsigned_or_unreadable_reports(self):
        for package, signature in [(self.package, ''), ('', self.signature)]:
            with self.assertRaises(ValueError):
                check_apk_reports(package, signature, self.expected)

    def test_reject_added_signer(self):
        with self.assertRaises(ValueError):
            check_apk_reports(self.package, self.signature + 'Signer #2 certificate SHA-256 digest: ' + 'ab' * 32, self.expected)

    def test_certificate_must_match_exact_der_fingerprint(self):
        data = b'example certificate bytes'
        expected = dict(self.expected, certificateSha256=hashlib.sha256(data).hexdigest())
        check_certificate(data, expected)
        with self.assertRaises(ValueError):
            check_certificate(data + b'changed', expected)


if __name__ == '__main__':
    unittest.main()
