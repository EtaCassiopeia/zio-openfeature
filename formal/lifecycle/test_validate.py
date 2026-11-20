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


class DroppedDeliveries(unittest.TestCase):
    """A delivery the swap's gate drops is carried on the swap's SwapReconcile label, by kind."""

    def swap(self, provider):
        rec = [
            {"seq": 0, "point": "Begin", "provider": "p0", "providerInstance": "a0"},
            {"seq": 1, "point": "SwapStart", "provider": "p1", "providerInstance": "a1"},
            {"seq": 2, "point": "SdkBind"},
            {"seq": 3, "point": "DeliverEvent", "arg": "PROVIDER_STALE", "provider": provider},
            {"seq": 4, "point": "GateDown"},
            {"seq": 5, "point": "SwapReconcile"},
            {"seq": 6, "point": "SwapDone"},
        ]
        return next(a for a in trace_labels.labels(rec) if a["action"] == "SwapReconcile")

    def test_a_delivery_from_the_bound_provider_under_the_gate_is_dropped(self):
        self.assertEqual(self.swap("p1")["dropped"], ["STALE"])

    def test_a_delivery_from_another_provider_is_not(self):
        self.assertEqual(self.swap("p0")["dropped"], [])

    def test_validation_bounds_the_events_by_the_dropped_ones_too(self):
        path = os.path.join(here, "traces", "accept", "recorded-emit-dropped-under-the-gate-read-by-the-reconcile.ndjson")
        actions = trace_labels.labels(trace_labels.read(path))
        self.assertEqual(sum(len(a["dropped"]) for a in actions), 2)
        self.assertFalse(any(a["action"] == "Deliver" for a in actions))



class ActionCounts(unittest.TestCase):
    def test_counts_each_action_once_per_run_and_ignores_runs_not_modelled(self):
        results = [
            ("a", [{"action": "SwapStart"}, {"action": "SwapStart"}, {"action": "ScopeClose"}], "accepted", "", None),
            ("b", [{"action": "ScopeClose"}], "accepted", "", None),
            ("c", None, None, "not modelled", None),
        ]
        self.assertEqual(validate.action_counts(results), {"ScopeClose": 2, "SwapStart": 1})


if __name__ == "__main__":
    unittest.main()
