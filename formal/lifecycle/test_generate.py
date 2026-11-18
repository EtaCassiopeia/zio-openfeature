"""generate.py's verdict on actions it never generated: by design while the reason holds, a failure otherwise.

Usage: python3 -m unittest test_generate (from formal/lifecycle; needs neither java nor the TLC jars).
"""
import importlib.util
import os
import unittest

here = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


shared = load("model_generate", os.path.join(os.path.dirname(here), "generate.py"))
lifecycle = load("lifecycle_generate", os.path.join(here, "generate.py"))


class ClassifyNever(unittest.TestCase):

    def test_an_absence_by_design_whose_reason_holds_is_expected(self):
        expected, unexpected = shared.classify_never(["Die"], {"Die": ("AllowDeath is FALSE", True)})
        self.assertEqual(expected, [("Die", "AllowDeath is FALSE")])
        self.assertEqual(unexpected, [])

    def test_an_absence_by_design_whose_reason_no_longer_holds_is_a_failure(self):
        expected, unexpected = shared.classify_never(["Die"], {"Die": ("AllowDeath is FALSE", False)})
        self.assertEqual(expected, [])
        self.assertEqual(unexpected, ["Die"])

    def test_any_other_absence_is_a_failure(self):
        expected, unexpected = shared.classify_never(
            ["SdkBindFail", "CallerInterrupt"], {"CallerInterrupt": ("Interruptible is empty", True)})
        self.assertEqual(expected, [("CallerInterrupt", "Interruptible is empty")])
        self.assertEqual(unexpected, ["SdkBindFail"])


class LifecycleByDesign(unittest.TestCase):

    def test_both_reasons_hold_in_the_checked_in_model_and_configuration(self):
        table = lifecycle.by_design(shared.read(here, "Lifecycle.tla"), shared.read(here, "Generate.cfg.in"))
        self.assertEqual(table, {"CallerInterrupt": ("Interruptible is empty", True),
                                 "Die": ("AllowDeath is FALSE", True)})

    def test_an_interruptible_phase_or_allowed_death_makes_the_action_required(self):
        table = lifecycle.by_design('Interruptible == {"wait"}\n', "    AllowDeath = TRUE\n")
        self.assertEqual(table, {"CallerInterrupt": ("Interruptible is empty", False),
                                 "Die": ("AllowDeath is FALSE", False)})


if __name__ == "__main__":
    unittest.main()
