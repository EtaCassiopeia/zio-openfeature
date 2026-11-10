"""validate.py's verdicts when TLC cannot run: a tool error, never a rejection.

Usage: python3 -m unittest test_validate (from formal/lifecycle; needs java on the path, not the TLC jars).
"""
import os
import subprocess
import sys
import tempfile
import unittest

import trace_labels
import validate

here = os.path.dirname(os.path.abspath(__file__))
missing = os.path.join(tempfile.gettempdir(), "no-such-tla2tools.jar")


def run(*args):
    return subprocess.run([sys.executable, os.path.join(here, "validate.py"), missing, missing, *args],
                          cwd=here, capture_output=True, text=True)


class ToolError(unittest.TestCase):
    def test_a_tlc_that_cannot_start_is_a_tool_error(self):
        actions = trace_labels.labels(trace_labels.read(os.path.join(here, "traces", "reject", "swap-inside-a-swap.ndjson")))
        verdict, why = validate.check(missing, missing, actions)
        self.assertEqual(verdict, "tool error")
        self.assertTrue(why.startswith("TLC failed"), why)
        self.assertIn("tlc2.TLC", why)

    def test_a_missing_java_is_a_tool_error(self):
        actions = trace_labels.labels(trace_labels.read(os.path.join(here, "traces", "reject", "swap-inside-a-swap.ndjson")))
        path = os.environ["PATH"]
        os.environ["PATH"] = ""
        try:
            verdict, _ = validate.check(missing, missing, actions)
        finally:
            os.environ["PATH"] = path
        self.assertEqual(verdict, "tool error")

    def test_the_selftest_fails_both_halves_when_tlc_cannot_run(self):
        result = run("--selftest", "traces")
        self.assertEqual(result.returncode, 1)
        self.assertIn("WRONG reject/swap-inside-a-swap.ndjson was tool error", result.stdout)
        self.assertIn("WRONG accept/swap-then-shutdown.ndjson was tool error", result.stdout)

    def test_a_run_over_traces_fails_and_counts_tool_errors(self):
        result = run(os.path.join("traces", "reject", "swap-inside-a-swap.ndjson"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("0 rejected", result.stdout)
        self.assertIn("1 tool error", result.stdout)


if __name__ == "__main__":
    unittest.main()
