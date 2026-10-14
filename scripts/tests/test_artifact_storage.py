"""Cleanup policy and API failures use synthetic data and never access GitHub."""
import copy
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

SCRIPT = Path(__file__).resolve().parents[1] / "prune-actions-artifacts.py"
spec = importlib.util.spec_from_file_location("artifact_storage", SCRIPT)
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
SHA = "a" * 40


def artifact(number, **changes):
    data = {"id": number, "name": f"Trading-Dashboard-0.1.{number}-signed-apk",
            "expired": False, "size_in_bytes": 100,
            "workflow_run": {"head_branch": "main", "head_sha": SHA, "id": number}}
    data.update(changes)
    return data


class FakeGitHub:
    def __init__(self):
        # Deliberately shuffled; versions must be ordered numerically, not by name.
        self.items = [artifact(n) for n in [9, 11, 8, 12, 10]]
        self.releases = {}
        self.tags = {}
        for number in range(8, 13):
            tag = f"v0.1.{number}"
            self.releases[tag] = {"tag_name": tag, "draft": False, "prerelease": False,
                "assets": [{"name": name, "size": 10, "state": "uploaded"}
                           for name in [f"Trading-Dashboard-0.1.{number}.apk",
                                        "SHA256SUMS.txt", "signature.txt", "build-info.txt"]]}
            self.tags[tag] = {"object": {"type": "commit", "sha": SHA}}
        self.deleted = []
        self.fail_path = None
        self.revalidation = {}

    def artifacts(self):
        return copy.deepcopy(self.items)

    def request(self, path, method="GET"):
        if path == self.fail_path:
            raise storage.ApiError("synthetic API failure")
        if path.startswith("releases/tags/"):
            return self.releases.get(path.removeprefix("releases/tags/"))
        if path.startswith("git/ref/tags/"):
            return self.tags.get(path.removeprefix("git/ref/tags/"))
        number = int(path.removeprefix("actions/artifacts/"))
        if method == "DELETE":
            self.deleted.append(number)
            return None
        if number in self.revalidation:
            return self.revalidation[number]
        return copy.deepcopy(next(a for a in self.items if a["id"] == number))


class CleanupCase(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub()
        quiet = patch("builtins.print")
        quiet.start()
        self.addCleanup(quiet.stop)
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_keep_latest_three_numeric_versions_delete_only_published_older_copies(self):
        self.assertEqual(200, storage.prune(self.api, apply=True))
        self.assertEqual([9, 8], self.api.deleted)

    def test_dry_run_is_read_only(self):
        self.assertEqual(0, storage.prune(self.api))
        self.assertEqual([], self.api.deleted)

    def test_preserve_unknown_names_reports_expired_and_pull_request_artifacts(self):
        self.api.items.extend([artifact(1, name="backup"),
            artifact(2, name="verification-reports-2"), artifact(3, expired=True),
            artifact(4, workflow_run={"head_branch": "feature", "head_sha": SHA})])
        storage.prune(self.api, apply=True)
        self.assertEqual([9, 8], self.api.deleted)

    def test_keep_unpublished_draft_prerelease_incomplete_and_empty_assets(self):
        for kind in ["missing", "draft", "prerelease", "incomplete", "empty", "uploading"]:
            with self.subTest(kind=kind):
                api = FakeGitHub()
                release = api.releases["v0.1.9"]
                if kind == "missing":
                    del api.releases["v0.1.9"]
                elif kind in ("draft", "prerelease"):
                    release[kind] = True
                elif kind == "incomplete":
                    release["assets"].pop()
                elif kind == "empty":
                    release["assets"][0]["size"] = 0
                else:
                    release["assets"][0]["state"] = "new"
                storage.prune(api, apply=True)
                self.assertEqual([8], api.deleted)

    def test_require_release_tag_exact_artifact_source_commit(self):
        for ref in [None, {"object": {"type": "tag", "sha": SHA}},
                    {"object": {"type": "commit", "sha": "b" * 40}}]:
            with self.subTest(ref=ref):
                api = FakeGitHub()
                api.tags["v0.1.9"] = ref
                storage.prune(api, apply=True)
                self.assertEqual([8], api.deleted)

    def test_failed_plan_read_prevents_every_delete(self):
        self.api.fail_path = "releases/tags/v0.1.8"
        with self.assertRaises(storage.ApiError):
            storage.prune(self.api, apply=True)
        self.assertEqual([], self.api.deleted)

    def test_missing_or_changed_artifact_is_not_deleted(self):
        for updated in [None, artifact(9, name="backup"),
                        artifact(9, workflow_run={"head_branch": "main", "head_sha": "b" * 40})]:
            with self.subTest(updated=updated):
                api = FakeGitHub()
                api.revalidation[9] = updated
                storage.prune(api, apply=True)
                self.assertEqual([8], api.deleted)

    def test_duplicate_versions_do_not_reduce_kept_version_count(self):
        self.api.items.append(artifact(12, id=99))
        storage.prune(self.api, apply=True)
        self.assertEqual([9, 8], self.api.deleted)

    def test_three_or_fewer_versions_never_deleted(self):
        self.api.items = self.api.items[:3]
        self.assertEqual(0, storage.prune(self.api, apply=True))
        self.assertEqual([], self.api.deleted)

    def test_cli_refuses_wrong_repository_non_main_and_local_runner(self):
        valid = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": storage.REPOSITORY,
                 "GITHUB_REF": "refs/heads/main", "GH_TOKEN": "synthetic-token"}
        for field, value in [("GITHUB_REPOSITORY", "other/repository"),
                             ("GITHUB_REPOSITORY", "shadow-pheonix/Trading_Dashboard"),
                             ("GITHUB_REF", "refs/heads/feature"), ("GITHUB_ACTIONS", "false")]:
            with patch.dict(os.environ, {**valid, field: value}, clear=True), patch.object(storage.sys, "argv", [str(SCRIPT), "--apply"]):
                with self.assertRaises(storage.ApiError):
                    storage.main()

    def test_cli_accepts_only_the_new_public_repository_main_runner(self):
        env = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "shadow-pheonix/Trading_Dashboard_Public",
               "GITHUB_REF": "refs/heads/main", "GH_TOKEN": "synthetic-token"}
        with patch.dict(os.environ, env, clear=True), patch.object(storage.sys, "argv", [str(SCRIPT), "--apply"]), \
                patch.object(storage, "GitHub", return_value=self.api):
            storage.main()
        self.assertEqual([9, 8], self.api.deleted)


class ApiCase(unittest.TestCase):
    def test_inventory_fetches_all_pages_before_returning(self):
        api = storage.GitHub("synthetic-token")
        with patch.object(api, "request", side_effect=[{"artifacts": [artifact(n) for n in range(1, 101)]},
                                                     {"artifacts": [artifact(101)]}]) as request:
            self.assertEqual(101, len(api.artifacts()))
            self.assertIn("page=2", request.call_args.args[0])

    def test_inventory_failure_or_overlapping_pages_aborts(self):
        api = storage.GitHub("synthetic-token")
        for second in [None, {"artifacts": [artifact(1)]}]:
            with patch.object(api, "request", side_effect=[{"artifacts": [artifact(n) for n in range(1, 101)]}, second]):
                with self.assertRaises(storage.ApiError):
                    api.artifacts()

    def test_api_errors_do_not_expose_token_or_response_text(self):
        api = storage.GitHub("synthetic-token")
        with patch.object(api.opener, "open", side_effect=urllib.error.HTTPError(
                "https://api.github.com", 403, "synthetic-private-response", {}, None)):
            with self.assertRaises(storage.ApiError) as result:
                api.request("actions/artifacts/9", "DELETE")
            self.assertEqual("GitHub DELETE failed: HTTP 403", str(result.exception))

    def test_redirects_are_refused(self):
        self.assertIsNone(storage.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))


if __name__ == "__main__":
    unittest.main()
