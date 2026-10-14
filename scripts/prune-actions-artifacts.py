"""Remove redundant dashboard APK artifacts, preserving Releases and run evidence."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

REPOSITORY = "shadow-pheonix/Trading_Dashboard_Public"
APK_NAME = re.compile(r"Trading-Dashboard-(0\.1\.([1-9][0-9]*))-signed-apk\Z")
KEEP_VERSIONS = 3


class ApiError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the runner token to a redirected host.
        return None


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, method="GET"):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPOSITORY}/{path}",
            headers={"Authorization": f"Bearer {self.token}",
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"}, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                if method == "DELETE":
                    if response.status != 204:
                        raise ApiError("Unexpected artifact deletion status")
                    return None
                return json.load(response)
        except urllib.error.HTTPError as error:
            if method == "GET" and error.code == 404:
                return None
            raise ApiError(f"GitHub {method} failed: HTTP {error.code}") from None
        except (urllib.error.URLError, ValueError, OSError):
            # Response bodies, request headers and exception strings stay private.
            raise ApiError(f"GitHub {method} failed; cleanup stopped") from None

    def artifacts(self):
        result = []
        for page in range(1, 101):
            data = self.request(f"actions/artifacts?per_page=100&page={page}")
            if not isinstance(data, dict) or not isinstance(data.get("artifacts"), list):
                raise ApiError("Incomplete artifact inventory; nothing deleted")
            batch = data["artifacts"]
            result.extend(batch)
            if len(batch) < 100:
                ids = [a["id"] for a in result]
                if len(ids) != len(set(ids)):
                    raise ApiError("Inventory changed during pagination; retry later")
                return result
        raise ApiError("Inventory page limit reached; nothing deleted")


def version(artifact):
    match = APK_NAME.fullmatch(artifact.get("name", ""))
    return (match.group(1), int(match.group(2))) if match else None


def released_copy(api, artifact):
    parsed = version(artifact)
    run = artifact.get("workflow_run", {})
    if not parsed or artifact.get("expired") or run.get("head_branch") != "main":
        return False
    sha = run.get("head_sha", "")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        return False
    tag = "v" + parsed[0]
    release = api.request("releases/tags/" + tag)
    if not release or release.get("draft") or release.get("prerelease"):
        return False
    if release.get("tag_name") != tag:
        return False
    required = {f"Trading-Dashboard-{parsed[0]}.apk", "SHA256SUMS.txt",
                "signature.txt", "build-info.txt"}
    assets = {a["name"] for a in release.get("assets", [])
              if a.get("state") == "uploaded" and a.get("size", 0) > 0}
    if not required <= assets:
        return False
    ref = api.request("git/ref/tags/" + tag)
    return bool(ref and ref.get("object", {}).get("type") == "commit"
                and ref["object"].get("sha") == sha)


def prune(api, apply=False):
    artifacts = api.artifacts()
    apks = [a for a in artifacts if version(a) and not a.get("expired")]
    newest = set(sorted({version(a)[1] for a in apks}, reverse=True)[:KEEP_VERSIONS])
    # Finish every read before the first delete. A denied/incomplete inventory
    # cannot become a destructive partial plan.
    plan = [a for a in apks if version(a)[1] not in newest and released_copy(api, a)]
    total = sum(a["size_in_bytes"] for a in artifacts if not a.get("expired"))
    print(f"Inventory: {len(artifacts)} artifacts; {total} bytes; {len(apks)} APK copies")
    print(f"Keep newest {KEEP_VERSIONS} APK versions plus any unpublished copies; reports untouched")
    freed = 0
    for artifact in plan:
        if apply:
            # Recheck identity and durable release immediately before deletion.
            current = api.request(f"actions/artifacts/{artifact['id']}")
            if not current or current.get("name") != artifact["name"]:
                continue
            if current.get("workflow_run") != artifact.get("workflow_run"):
                continue
            if not released_copy(api, current):
                continue
            api.request(f"actions/artifacts/{artifact['id']}", method="DELETE")
            freed += artifact["size_in_bytes"]
        print(f"{'Deleted' if apply else 'Would delete'} {artifact['name']}: {artifact['size_in_bytes']} bytes")
    summary = f"{'Deleted' if apply else 'Dry run'}: {freed if apply else sum(a['size_in_bytes'] for a in plan)} bytes of redundant APK artifacts. Releases, reports, workflow logs and caches were not deleted."
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as output:
            output.write(summary + "\n")
    return freed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Delete verified duplicate artifacts")
    args = parser.parse_args()
    if (os.environ.get("GITHUB_ACTIONS") != "true"
            or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
            or os.environ.get("GITHUB_REF") != "refs/heads/main"):
        raise ApiError("Cleanup requires this repository's trusted main Actions runner")
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise ApiError("Missing runner token")
    prune(GitHub(token), apply=args.apply)


if __name__ == "__main__":
    try:
        main()
    except ApiError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyError, TypeError):
        print("Artifact cleanup refused or failed; inspect the inventory and runner permissions.", file=sys.stderr)
        sys.exit(1)
