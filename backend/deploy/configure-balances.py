#!/usr/bin/env python3
"""Human-run root importer for public addresses and original instance equity."""
import json
import os
from pathlib import Path
import re
import stat
import sys

from dashboard_backend.config import load_config
from dashboard_backend.validation import InvalidData, number

ENVIRONMENTS = {
    'main': Path('/etc/hlmacdbot/hlmacdbot.env'),
    'vinay': Path('/etc/hlmacdbot/hlmacdbot-vinay.env'),
    'madhu': Path('/etc/hlmacdbot/hlmacdbot-madhu.env'),
}
PUBLIC_FIELDS = frozenset(f'HL_{prefix}{model}_SUB_WALLET'
                         for prefix in ('', 'B_') for model in ('M1', 'M2', 'M3'))
MAX_BYTES = 1024 * 1024


def parse_addresses(lines, required, include_equity=False):
    """Parse allowlisted assignments only; never source, eval or expand env text."""
    required = frozenset(required)
    if not required or not required <= PUBLIC_FIELDS:
        raise InvalidData()
    allowed = required | {'ORIGINAL_EQUITY'} if include_equity else required
    assignment = re.compile(r'^[ \t]*(?:export[ \t]+)?(' + '|'.join(sorted(allowed))
                            + r')[ \t]*=[ \t]*(.*)$')
    address = re.compile(r'''(?:"(0x[a-fA-F0-9]{40})"|'(0x[a-fA-F0-9]{40})'|(0x[a-fA-F0-9]{40}))(?:[ \t]+\#.*)?[ \t]*''')
    result = {}
    total = 0
    for line in lines:
        total += len(line)
        if total > MAX_BYTES:
            raise InvalidData()
        match = assignment.fullmatch(line.rstrip('\r\n'))
        if match is None:
            continue
        name, value = match.groups()
        if name == 'ORIGINAL_EQUITY':
            if name in result:
                raise InvalidData()
            # Literal numbers only: never source, expand or evaluate shell syntax.
            parsed = re.fullmatch(r'''(?:"([0-9]+(?:\.[0-9]+)?)"|'([0-9]+(?:\.[0-9]+)?)'|([0-9]+(?:\.[0-9]+)?))(?:[ \t]+\#.*)?[ \t]*''', value)
            if parsed is None:
                raise InvalidData()
            equity = number(float(next(part for part in parsed.groups() if part is not None)))
            if equity <= 0:
                raise InvalidData()
            result[name] = equity
            continue
        parsed = address.fullmatch(value)
        if name in result or parsed is None:
            raise InvalidData()
        public_address = next(part for part in parsed.groups() if part is not None)
        if int(public_address[2:], 16) == 0:
            raise InvalidData()
        result[name] = public_address
    if result.keys() - {'ORIGINAL_EQUITY'} != required:
        raise InvalidData()
    return result


def read_environment(path, required):
    """Human root invocation only. Fixed files, no symlinks, private root metadata."""
    if os.geteuid() != 0 or path not in ENVIRONMENTS.values():
        raise InvalidData()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
    with os.fdopen(fd, 'r', encoding='utf-8') as file:
        before = os.fstat(file.fileno())
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != 0 or before.st_gid != 0
                or stat.S_IMODE(before.st_mode) != 0o600 or before.st_size > MAX_BYTES):
            raise InvalidData()
        values = parse_addresses(file, required, include_equity=True)
        after = os.fstat(file.fileno())
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise InvalidData()
        return values


def configure(value, environment_reader=read_environment):
    expected = {'main': 6, 'vinay': 3, 'madhu': 3}
    if (len(value['instances']) != 3
            or {i['id'] for i in value['instances']} != set(expected)):
        raise InvalidData()
    plans = []
    for instance in value['instances']:
        if len(instance['accounts']) != expected[instance['id']]:
            raise InvalidData()
        slots, accounts = set(), []
        for account in instance['accounts']:
            wallet = account.get('wallet') or ('B' if account.get('edgeModel', '').endswith('-B')
                or Path(account['statePath']).name.startswith('hl_state_b_') else 'A')
            model = account['strategy']
            slot = (model, wallet)
            if model not in ('M1', 'M2', 'M3') or slot in slots:
                raise InvalidData()
            slots.add(slot)
            field = f'HL_{"B_" if wallet == "B" else ""}{model}_SUB_WALLET'
            accounts.append((account, wallet, field))
        required = {(model, wallet) for model in ('M1', 'M2', 'M3')
                    for wallet in (('A', 'B') if instance['id'] == 'main' else ('A',))}
        if slots != required:
            raise InvalidData()
        plans.append((ENVIRONMENTS[instance['id']], instance, accounts))
    for path, instance, accounts in plans:
        parents = {Path(account['statePath']).parent for account, _, _ in accounts}
        if len(parents) != 1:
            raise InvalidData()
        instance['alertsPath'] = str(next(iter(parents)) / 'dashboard_alerts.sqlite')
        addresses = environment_reader(path, {field for _, _, field in accounts})
        instance.pop('originalEquity', None)
        if 'ORIGINAL_EQUITY' in addresses:
            instance['originalEquity'] = addresses['ORIGINAL_EQUITY']
        for account, wallet, field in accounts:
            account['wallet'], account['publicAddress'] = wallet, addresses[field]
    return value


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        sys.exit('Human administrator only: provide dashboard config and a new private output path.')
    source, target = map(Path, sys.argv[1:])
    try:
        helper = os.stat(__file__, follow_symlinks=False)
        if (not stat.S_ISREG(helper.st_mode) or helper.st_uid != 0 or helper.st_gid != 0
                or stat.S_IMODE(helper.st_mode) != 0o600):
            raise InvalidData()
        load_config(source)
        value = configure(json.loads(source.read_bytes()))
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(fd, 'w') as file:
                json.dump(value, file, indent=2)
                file.write('\n')
                file.flush()
                os.fsync(file.fileno())
            load_config(target)
        except BaseException:
            target.unlink(missing_ok=True)
            raise
    except (Exception, KeyboardInterrupt):
        sys.exit('Private address import failed or was cancelled. Check public sub-wallet fields and root-only file permissions. No installed configuration changed.')
    print('Private balance configuration validated from three instances. Trading keys were not imported; bot files were not modified.')


if __name__ == '__main__':
    main()
