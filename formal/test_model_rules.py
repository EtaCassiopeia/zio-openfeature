"""Unit tests for model_or_reason.py and check_paths.py. Run from formal/: python3 -m unittest test_model_rules"""

import io
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import model_or_reason
from check_paths import filters, problems
from model_or_reason import code_paths, missing, reasons

LIVE = "core/src/main/scala/zio/openfeature/FeatureFlagsLive.scala"
CACHE = "extras/src/main/scala/zio/openfeature/extras/CachingProvider.scala"
FACTORIES = "core/src/main/scala/zio/openfeature/internal/FeatureFlagsFactories.scala"
MODELS = {
    "lifecycle": {LIVE, FACTORIES},
    "acquire": {FACTORIES},
    "caching": {CACHE},
}


class Reasons(unittest.TestCase):
    def test_a_named_line_and_the_bare_lifecycle_form(self):
        body = "Fixes it.\nformal/caching: unaffected because only a log line\nformal: unaffected because no step\n"
        self.assertEqual(reasons(body), {"caching", "lifecycle"})

    def test_crlf_line_endings(self):
        body = "formal/acquire: unaffected because x\r\nformal/lane: unaffected because y\r\n"
        self.assertEqual(reasons(body), {"acquire", "lane"})

    def test_a_line_without_a_reason_does_not_count(self):
        self.assertEqual(reasons("formal/caching: unaffected because\r\nformal/lane: unaffected because   \n"), set())

    def test_a_mention_mid_sentence_does_not_count(self):
        self.assertEqual(reasons("see formal/lane: unaffected because x"), set())

    def test_a_list_bullet_or_quote_in_front_and_extra_spaces(self):
        body = "- formal/caching:  unaffected because x\n  * formal/lane: unaffected  because y\n> formal: unaffected because z"
        self.assertEqual(reasons(body), {"caching", "lane", "lifecycle"})

    def test_the_model_name_and_words_ignore_case(self):
        self.assertEqual(reasons("Formal/Circuitbreaker: Unaffected Because x"), {"circuitbreaker"})


class Missing(unittest.TestCase):
    def test_an_unrelated_file_needs_nothing(self):
        self.assertEqual(missing(["README.md"], "", MODELS), {})

    def test_code_changed_without_its_model_or_a_reason_fails(self):
        self.assertEqual(missing([CACHE], "", MODELS), {"caching": [CACHE]})

    def test_changing_the_model_itself_is_enough(self):
        self.assertEqual(missing([CACHE, "formal/caching/Caching.tla"], "", MODELS), {})

    def test_another_models_change_does_not_excuse_it(self):
        self.assertEqual(missing([LIVE, "formal/caching/Caching.tla"], "", MODELS), {"lifecycle": [LIVE]})

    def test_the_bare_form_counts_for_the_lifecycle_only(self):
        body = "formal: unaffected because the swap is untouched"
        self.assertEqual(missing([LIVE, CACHE], body, MODELS), {"caching": [CACHE]})

    def test_a_file_two_models_describe_needs_both_lines(self):
        body = "formal/lifecycle: unaffected because no lifecycle step changes"
        self.assertEqual(missing([FACTORIES], body, MODELS), {"acquire": [FACTORIES]})
        body += "\nformal/acquire: unaffected because the hand-off is untouched"
        self.assertEqual(missing([FACTORIES], body, MODELS), {})


def git(repo, *args):
    subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True)


class Main(unittest.TestCase):
    """The script end to end, on a throwaway repository with one model."""

    def setUp(self):
        self.repo = tempfile.mkdtemp()
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@example.com")
        git(self.repo, "config", "user.name", "t")
        root = Path(self.repo)
        (root / "formal" / "caching").mkdir(parents=True)
        (root / "formal" / "caching" / "code-paths").write_text("# comment\n\nsrc/Cache.scala\n")
        (root / "src").mkdir()
        (root / "src" / "Cache.scala").write_text("a\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "base")
        self.formal = root / "formal"

    def run_main(self, body):
        out = io.StringIO()
        cwd = os.getcwd()
        os.chdir(self.repo)
        try:
            with mock.patch.dict(os.environ, {"BODY": body}), mock.patch.object(model_or_reason, "FORMAL", self.formal), \
                    mock.patch.object(model_or_reason, "code_paths", lambda: code_paths(self.formal)), \
                    redirect_stdout(out):
                code = model_or_reason.main(["model_or_reason.py", "HEAD~1", "HEAD"])
        finally:
            os.chdir(cwd)
        return code, out.getvalue()

    def commit(self, change):
        change(Path(self.repo))
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "change")

    def test_code_paths_skips_comments_and_blank_lines(self):
        self.assertEqual(code_paths(self.formal), {"caching": {"src/Cache.scala"}})

    def test_a_code_change_without_a_reason_exits_1_naming_the_model_and_file(self):
        self.commit(lambda r: (r / "src" / "Cache.scala").write_text("b\n"))
        code, out = self.run_main("")
        self.assertEqual(code, 1)
        self.assertIn("formal/caching describes without changing the model:\n  src/Cache.scala\n", out)

    def test_the_same_change_with_its_line_exits_0(self):
        self.commit(lambda r: (r / "src" / "Cache.scala").write_text("b\n"))
        self.assertEqual(self.run_main("- formal/caching: unaffected because a log line")[0], 0)

    def test_a_rename_counts_under_the_old_path(self):
        self.commit(lambda r: (r / "src" / "Cache.scala").rename(r / "src" / "Kept.scala"))
        self.assertEqual(self.run_main("")[0], 1)


WORKFLOW = """name: Formal model
on:
  pull_request:
    paths:
      - 'formal/**'
      - 'a/One.scala'
      - 'a/Two.scala'
  push:
    branches: [main]
    paths:
      - 'a/One.scala'
jobs:
  x:
    steps:
      - 'a/Two.scala'
"""


class CheckPaths(unittest.TestCase):
    def test_reads_each_events_filter_and_stops_at_the_next_top_level_key(self):
        self.assertEqual(
            filters(WORKFLOW),
            {"pull_request": {"formal/**", "a/One.scala", "a/Two.scala"}, "push": {"a/One.scala"}},
        )

    def test_reports_a_path_missing_from_a_filter_and_a_file_that_does_not_exist(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / "a").mkdir()
            (Path(root) / "a" / "One.scala").write_text("")
            (Path(root) / "a" / "Two.scala").write_text("")
            models = {"m": {"a/One.scala", "a/Two.scala", "a/Gone.scala"}}
            self.assertEqual(
                problems(models, WORKFLOW, Path(root)),
                [
                    "formal/m/code-paths: a/Gone.scala does not exist",
                    "formal/m/code-paths: a/Gone.scala is not in formal.yml's pull_request paths",
                    "formal/m/code-paths: a/Gone.scala is not in formal.yml's push paths",
                    "formal/m/code-paths: a/Two.scala is not in formal.yml's push paths",
                ],
            )

    def test_a_model_listing_no_file(self):
        self.assertEqual(problems({"m": set()}, WORKFLOW, Path(".")), ["formal/m/code-paths lists no file"])

    def test_the_repository_passes(self):
        from check_paths import FORMAL, WORKFLOW as REAL
        self.assertEqual(problems(code_paths(), REAL.read_text(), FORMAL.parent), [])


if __name__ == "__main__":
    unittest.main()
