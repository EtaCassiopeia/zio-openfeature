"""The registry generator's action discovery and its by-design table, per topology.

Usage: python3 -m unittest test_generate (from formal/registry; needs neither java nor the TLC jars).
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
registry = load("registry_generate", os.path.join(here, "generate.py"))

REGISTRY_ACTIONS = {
    "BuildFail", "BuildTimeout", "Check", "CloseInstance", "Complete", "Create", "CreateRefused", "Evict", "FailDecide",
    "FailMark", "InitSettle",
    "Lookup", "OwnerClose", "RegistryClose", "ReleaseReplaced", "SwapDone", "SwapFail", "SwapFailMark", "SwapOldShut",
    "SwapStart", "SwapTimeout", "UserBind",
}


class DiscoverNext(unittest.TestCase):

    def test_the_registry_next_yields_its_twenty_two_actions_and_none_of_its_constants(self):
        self.assertEqual(shared.discover_next(here, "Registry.tla", "Next"), REGISTRY_ACTIONS)

    def test_the_lifecycle_next_is_unchanged(self):
        lifecycle = os.path.join(os.path.dirname(here), "lifecycle")
        self.assertEqual(shared.discover_next(lifecycle, "Lifecycle.tla", "LifecycleNext"), {
            "CallerInterrupt", "Deliver", "Die", "Emit", "EmitStaleWhileStarting", "OrphanResolved", "ScopeClose",
            "SdkBind", "SdkBindFail", "SdkInitFail", "SdkInitOk", "SdkShutDownOld", "ShutdownDone", "ShutdownStart",
            "SwapDone", "SwapFailNoRollback", "SwapReconcile", "SwapRollback", "SwapRollbackUnbound", "SwapStart",
            "SwapTimeout"})

    def test_constants_declared_in_a_block_or_on_one_line_are_not_actions(self):
        module = ("CONSTANTS\n"
                  "    Workers,   \\* who works\n"
                  "    Limit\n"
                  "CONSTANT Spare\n"
                  "\n"
                  "Next ==\n"
                  "    \\/ \\E w \\in Workers : Start(w)\n"
                  "    \\/ Stop(Limit, Spare)\n"
                  "\n")
        self.assertEqual(shared.constants_of(module), {"Workers", "Limit", "Spare"})
        self.assertEqual(shared.next_actions(module, "Next"), {"Start", "Stop"})


class ByDesign(unittest.TestCase):

    def setUp(self):
        self.generate_tla = shared.read(here, "Generate.tla")
        self.cfg = shared.read(here, "Generate.cfg.in")
        self.registry_tla = shared.read(here, "Registry.tla")

    def table(self, topology, generate_tla=None, cfg=None, registry_tla=None):
        return registry.by_design(topology, generate_tla or self.generate_tla, cfg or self.cfg,
                                  registry_tla or self.registry_tla)

    def test_the_registry_topology_leaves_out_the_shared_api_steps(self):
        self.assertEqual(self.table("registry"), {
            "UserBind": ("only a shared API binds a user's provider", True),
            "OwnerClose": ("a registry's API has no owning instance", True),
            "FailMark": ("only a shared API's build has an init deadline the replay drives", True),
            "FailDecide": ("it follows only FailMark", True),
            "BuildTimeout": ("the replay does not cut a registry build's wait for readiness short", True),
            "CreateRefused": ("it needs a provider BuildTimeout left initializing", True),
        })

    def test_the_shared_topology_leaves_out_the_registry_steps(self):
        self.assertEqual(self.table("shared"), {
            "Lookup": ("a shared API has no registry promise map", True),
            "BuildFail": ("a shared API has no registry promise map", True),
            "Evict": ("a shared API has no registry promise map", True),
            "ReleaseReplaced": ("a shared API has no registry promise map", True),
            "RegistryClose": ("a shared API has no registry", True),
            "BuildTimeout": ("a shared API has no registry promise map", True),
            "CreateRefused": ("a shared API has no registry promise map", True),
        })

    # Each topology guard as Generate.tla writes it, and the actions it keeps out of the other topology.
    GUARDS = [
        ("registry", "/\\ Shared\n       /\\ \\E d \\in Domains, p \\in Providers :\n            /\\ ~\\E j \\in swapping", "UserBind"),
        ("registry", "Shared /\\ OwnerClose", "OwnerClose"),
        ("registry", "Shared /\\ \\E i \\in Instances : phase[i] = \"building\" /\\ Fresh(i) /\\ FailMark", "FailMark"),
        ("shared", "/\\ Reg\n       /\\ \\E i \\in Instances, d \\in Domains :\n            /\\ act # [name |-> \"Lookup\"",
         "Lookup"),
        ("shared", "/\\ Reg\n       /\\ \\E i \\in Instances :\n            /\\ Free(\"BuildFail\", i)", "BuildFail"),
        ("shared", "/\\ Reg\n       /\\ \\E i \\in Instances : Free(\"Evict\", i)", "Evict"),
        ("shared", "/\\ Reg\n       /\\ \\E i \\in Instances :\n            /\\ \\A j \\in Retry(i)", "ReleaseReplaced"),
        ("shared", "Reg /\\ RegistryClose", "RegistryClose"),
        ("shared", "/\\ Reg\n       /\\ \\E i \\in Instances, p \\in Providers :\n            Free(\"Create\", i) /\\ CreateRefused",
         "CreateRefused"),
        ("shared", "/\\ Reg\n       /\\ BuildTimeouts", "BuildTimeout"),
    ]

    def test_each_action_whose_topology_guard_is_dropped_becomes_required(self):
        for topology, text, action in self.GUARDS:
            with self.subTest(action=action):
                self.assertEqual(self.generate_tla.count(text), 1, f"{action}: guard text not found once")
                guard = "Shared" if topology == "registry" else "Reg"
                dropped = self.generate_tla.replace(text, text.replace(guard, "TRUE", 1))
                self.assertFalse(self.table(topology, generate_tla=dropped)[action][1])

    def test_a_negated_guard_or_one_side_of_a_disjunction_is_no_guard(self):
        text = "Shared /\\ OwnerClose"
        for weakened in ("~Shared /\\ OwnerClose", "Shared \\/ OwnerClose"):
            with self.subTest(weakened=weakened):
                changed = self.generate_tla.replace(text, weakened)
                self.assertFalse(self.table("registry", generate_tla=changed)["OwnerClose"][1])

    def test_a_second_unguarded_disjunct_labelling_the_action_makes_it_required(self):
        extra = self.generate_tla.replace(
            "Closing ==\n", "Closing ==\n    \\/ OwnerClose /\\ act' = [name |-> \"OwnerClose\"]\n", 1)
        self.assertNotEqual(extra, self.generate_tla)
        self.assertFalse(self.table("registry", generate_tla=extra)["OwnerClose"][1])

    def test_fail_decide_is_required_once_another_action_also_starts_a_decision(self):
        changed = self.registry_tla.replace("SwapFailMark(i) ==\n", "SwapFailMark(i) ==\n    /\\ deciding' = deciding \\cup {i}\n", 1)
        self.assertNotEqual(changed, self.registry_tla)
        self.assertEqual(self.table("registry", registry_tla=changed)["FailDecide"], ("it follows only FailMark", False))

    def test_build_timeouts_become_required_once_they_are_generated(self):
        allowed = self.generate_tla.replace("BuildTimeouts == FALSE", "BuildTimeouts == TRUE")
        self.assertNotEqual(allowed, self.generate_tla)
        table = self.table("registry", generate_tla=allowed)
        self.assertFalse(table["BuildTimeout"][1])
        self.assertFalse(table["CreateRefused"][1])

    def test_timeouts_are_generated_in_both_topologies(self):
        self.assertIn("AllowTimeouts = TRUE", self.cfg)
        for topology in ("registry", "shared"):
            table = self.table(topology)
            self.assertNotIn("SwapTimeout", table)
            self.assertNotIn("InitSettle", table)

    def test_an_unknown_topology_is_refused(self):
        with self.assertRaises(SystemExit):
            self.table("both")


def state(act, then="None", shuts=(0, 0), total=(0, 0), domain_of=None):
    return {"act": act, "then": then, "shuts": {"p1": shuts[0], "p2": shuts[1]},
            "total": {"p1": total[0], "p2": total[1]}, "domainOf": domain_of or {"i1": "None", "i2": "None"}}


INIT = state({"name": "Init"})


class CheckShape(unittest.TestCase):

    def test_the_default_provider_is_read_from_the_configuration(self):
        self.assertEqual(registry.default_provider_of(shared.read(here, "Generate.cfg.in")), "p1")
        self.assertEqual(registry.default_provider_of("    DefaultProvider = p3\n"), "p3")
        with self.assertRaises(SystemExit):
            registry.default_provider_of("    Providers = {p1}\n")

    def test_a_well_formed_behaviour_has_no_problem(self):
        behaviour = [
            INIT,
            state({"name": "Check", "i": "i1", "d": "d1"}, then={"name": "Create", "i": "i1"},
                  domain_of={"i1": "d1", "i2": "None"}),
            state({"name": "Create", "i": "i1", "p": "p2", "own": False}, domain_of={"i1": "d1", "i2": "None"}),
            state({"name": "BuildFail", "i": "i1"}, then={"name": "Evict", "i": "i1"}),
            state({"name": "Evict", "i": "i1"}),
            state({"name": "RegistryClose"}, shuts=(0, 1), total=(0, 1)),
            state({"name": "Done", "n": 1}, shuts=(0, 1), total=(0, 1)),
        ]
        self.assertEqual(registry.check_shape(behaviour, "registry", {"UserBind"}), [])

    def test_a_shutdown_the_total_misses_is_reported(self):
        behaviour = [INIT, state({"name": "RegistryClose"}, shuts=(1, 0), total=(0, 0))]
        self.assertEqual(registry.check_shape(behaviour, "registry", set()), ["state 1: total[p1] is 0, not 1"])

    def test_a_new_registration_restarting_shuts_keeps_the_total(self):
        behaviour = [INIT, state({"name": "Complete", "i": "i1"}, shuts=(1, 0), total=(1, 0)),
                     state({"name": "Create", "i": "i2", "p": "p1", "own": False}, shuts=(0, 0), total=(1, 0))]
        self.assertEqual(registry.check_shape(behaviour, "shared", set()), [])

    def test_a_step_other_than_the_one_held_to_is_reported(self):
        behaviour = [INIT, state({"name": "BuildFail", "i": "i1"}, then={"name": "Evict", "i": "i1"}),
                     state({"name": "Lookup", "i": "i2", "d": "d1"})]
        self.assertEqual(registry.check_shape(behaviour, "registry", set()),
                         ["state 2: Lookup where Evict of i1 is held to",
                          "state 2: Lookup right after BuildFail, not Evict of i1"])

    def test_a_failed_build_not_evicted_next_is_reported_even_with_nothing_held(self):
        behaviour = [INIT, state({"name": "BuildFail", "i": "i1"}), state({"name": "Lookup", "i": "i2", "d": "d1"})]
        self.assertEqual(registry.check_shape(behaviour, "registry", set()),
                         ["state 2: Lookup right after BuildFail, not Evict of i1"])

    def test_a_refused_build_takes_the_place_of_its_create_and_is_evicted_next(self):
        checked = dict(state({"name": "Check", "i": "i1", "d": "d1"}), then={"name": "Create", "i": "i1"})
        refused = dict(state({"name": "CreateRefused", "i": "i1", "p": "p2"}), then={"name": "Evict", "i": "i1"})
        self.assertEqual(registry.check_shape([INIT, checked, refused, state({"name": "Evict", "i": "i1"})],
                                              "registry", set()), [])
        self.assertEqual(registry.check_shape([INIT, checked, refused, state({"name": "Lookup", "i": "i2", "d": "d1"})],
                                              "registry", set()),
                         ["state 3: Lookup where Evict of i1 is held to",
                          "state 3: Lookup right after CreateRefused, not Evict of i1"])

    def test_a_release_not_followed_by_its_retry_failing_is_reported(self):
        retrying = dict(INIT, phase={"i1": "evicted", "i2": "building"}, domainOf={"i1": "d1", "i2": "d1"},
                        builtOn={"i1": "p1", "i2": "p2"}, replaced={"p1": "p2", "p2": "None"})
        behaviour = [retrying, dict(retrying, act={"name": "ReleaseReplaced", "i": "i1"}),
                     dict(retrying, act={"name": "Lookup", "i": "i3", "d": "d1"})]
        self.assertEqual(registry.check_shape(behaviour, "registry", set()),
                         ["state 2: Lookup right after ReleaseReplaced, not BuildFail of i2"])

    def test_an_action_the_topology_leaves_out_is_reported(self):
        behaviour = [INIT, state({"name": "UserBind", "d": "d1", "p": "p1"})]
        self.assertEqual(registry.check_shape(behaviour, "registry", {"UserBind"}),
                         ["state 1: UserBind, which the registry topology leaves out"])

    def test_a_repeated_lookup_is_reported(self):
        lookup = {"name": "Lookup", "i": "i2", "d": "d1"}
        behaviour = [INIT, state(lookup), state(lookup)]
        self.assertEqual(registry.check_shape(behaviour, "registry", set()),
                         ["state 2: the same Lookup twice in a row"])

    def test_the_registry_default_slot_built_from_another_provider_is_reported(self):
        on_default = {"i1": "default", "i2": "None"}
        create = {"name": "Create", "i": "i1", "p": "p2", "own": False}
        self.assertEqual(registry.check_shape([INIT, state(create, domain_of=on_default)], "registry", set()),
                         ["state 1: the registry's default slot built from p2"])
        self.assertEqual(registry.check_shape([INIT, state(create, domain_of=on_default)], "shared", set()), [])


if __name__ == "__main__":
    unittest.main()
