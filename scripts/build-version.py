"""Keep update version codes increasing when moving to a new Actions repository."""
import sys


def build_version(offset, run):
    if not offset.isascii() or not offset.isdecimal() or not run.isascii() or not run.isdecimal():
        raise ValueError('Invalid build version.')
    number = int(offset) + int(run)
    if int(run) < 1 or not 1 <= number <= 2**31 - 1:
        raise ValueError('Invalid build version.')
    return number


if __name__ == '__main__':
    try:
        version = build_version(*sys.argv[1:])
        print(f'APP_VERSION_CODE={version}\nAPP_VERSION_NAME=0.1.{version}')
    except (TypeError, ValueError):
        raise SystemExit('Invalid build version settings.')
