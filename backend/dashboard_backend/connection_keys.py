"""Private one-field connection codes; the backend still authenticates the raw token."""
import base64
import json
from urllib.parse import urlsplit


def connection_key(server_url, token):
    try:
        url = urlsplit(server_url)
        if (url.scheme != 'https' or not url.hostname or url.username is not None or
                url.password is not None or '?' in server_url or '#' in server_url or url.path not in ('', '/') or
                any(c.isspace() or ord(c) < 33 for c in server_url) or
                not isinstance(token, str) or not 32 <= len(token) <= 256 or
                any(not 33 <= ord(c) <= 126 for c in token)):
            raise ValueError()
        if url.port is not None and not 1 <= url.port <= 65535:
            raise ValueError()
        payload = json.dumps({'url': server_url.rstrip('/'), 'key': token}, separators=(',', ':')).encode()
        code = 'TD1.' + base64.urlsafe_b64encode(payload).decode().rstrip('=')
        if len(code) > 2048:
            raise ValueError()
        return code
    except Exception:
        raise ValueError('Invalid dashboard connection settings.') from None
