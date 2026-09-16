"""Validate a certificate and successful apksigner/aapt reports against the original APK.

Does not sign APKs, recover private keys, or itself cryptographically verify APKs.
The workflow runs Android's apksigner verify before this report check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


BASELINE = Path(__file__).with_name('legacy-apk.json')


def check_certificate(data, expected):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected['certificateSha256']:
        raise ValueError('The key does not match the original app-debug.apk. '
                         'Use the ORIGINAL debug.keystore; a new key cannot update it. '
                         f'Expected: {expected["certificateSha256"]}. Actual: {actual}.')


def check_apk_reports(package_report, signature_report, expected):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", package_report, re.M)
    if not package:
        raise ValueError('Cannot read package name and versionCode from aapt output.')
    if package[1] != expected['packageName']:
        raise ValueError('Wrong applicationId: ' + package[1])
    if int(package[2]) <= expected['versionCode']:
        raise ValueError('The update versionCode must be greater than the original versionCode.')
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$', signature_report, re.M)
    if [s.replace(':', '').lower() for s in signers] != [expected['certificateSha256']]:
        raise ValueError('The built APK does not have the original signing certificate.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    key = actions.add_parser('key')
    key.add_argument('certificate_der', type=Path)
    apk = actions.add_parser('apk')
    apk.add_argument('package_report', type=Path)
    apk.add_argument('signature_report', type=Path)
    args = parser.parse_args()
    expected = json.loads(BASELINE.read_text(encoding='utf-8'))
    try:
        if args.action == 'key':
            check_certificate(args.certificate_der.read_bytes(), expected)
            print('Signing certificate matches the original APK.')
        else:
            check_apk_reports(args.package_report.read_text(encoding='utf-8'),
                              args.signature_report.read_text(encoding='utf-8'), expected)
            print('APK package, signing certificate and version match the update requirements.')
    except (ValueError, OSError) as error:
        print('UPDATE CHECK FAILED: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
