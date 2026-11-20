"""Put each ownership bug back into the registry model and check that each property named for it catches it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a named property does not catch its mutant.
"""
import os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Registry.tla')).read()
cfg = open(os.path.join(here, 'Registry.cfg')).read()

# Each mutant names the properties it exists to exercise, and each is checked alone: a mutant usually breaks several,
# and with several in one run TLC reports whichever its workers reach first.  A property not named may break as well.
mutants = {
    # A non-owner's close tears the API down: siblings lose their provider (the slot of a closed sibling is empty before
    # the owner closes), and the owner shuts it down again.
    'a non-owner tears the API down': (
        ('ClosedNonOwnerKeepsSlot', 'SiblingsUnaffected'), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ bound' = [d \\in Domains |-> None]\n"
         "    /\\ registered' = {}\n"
         "    /\\ userOwned' = {}\n"
         "    /\\ shuts' = [p \\in Providers |-> shuts[p] + (IF p \\in registered THEN 1 ELSE 0)]\n"
         "    /\\ by' = [p \\in Providers |-> IF p \\in registered THEN \"sdk\" ELSE by[p]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, everBound, failedOn, deciding, prior, swappedOff, builtOn, replaced >>")]),
    # A domain client shuts its own provider down when it closes: a sibling sharing it loses it, and the owner shuts
    # it down again.
    'a domain client shuts its provider down directly': (
        ('RegisteredNotShutDown', 'ForeignNeverShutByLibrary', 'SharedShutDownOnce'), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ shuts' = [shuts EXCEPT ![bound[domainOf[i]]] = @ + 1]\n"
         "    /\\ by' = [by EXCEPT ![bound[domainOf[i]]] = \"library\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, everBound, failedOn, deciding, prior, swappedOff, builtOn, replaced >>")]),
    # A non-owner's close retires its siblings, as only the owner's may.
    'a non-owner retires its siblings': (
        ('SiblingsUnaffected',), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>",
         "    /\\ phase' = [j \\in Instances |-> IF phase[j] = \"open\" THEN \"closed\" ELSE phase[j]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding, prior, swappedOff, builtOn, replaced >>")]),
    # getClient looks the domain up and inserts its promise without the semaphore: two callers build two clients.
    'getClient without the semaphore': (
        ('OneInstancePerDomain',), [
        ("    /\\ lock = None\n"
         "    /\\ ~\\E j \\in Instances : phase[j] \\notin {\"absent\", \"evicted\"} /\\ domainOf[j] = d\n"
         "    /\\ lock' = i\n",
         "    /\\ ~\\E j \\in Instances : phase[j] \\notin {\"absent\", \"evicted\", \"checking\"} /\\ domainOf[j] = d\n"
         "    /\\ lock' = lock\n"),
        ("    /\\ phase[i] = \"checking\"\n    /\\ lock = i\n    /\\ own => owner = None\n",
         "    /\\ phase[i] = \"checking\"\n    /\\ own => owner = None\n"),
        ("    /\\ p \\notin pending /\\ ~Waiting(p)\n    /\\ lock' = None\n",
         "    /\\ p \\notin pending /\\ ~Waiting(p)\n    /\\ lock' = lock\n")]),
    # The rollback of a failed swap shuts the failed provider down although a live sibling is bound to it.
    'the library shuts a provider a sibling holds on rollback': (
        ('LiveSiblingKeepsProvider',), [
        ("                ELSE p \\in registered /\\ LibraryShuts(i, p)\n",
         "                ELSE p \\in registered\n")]),
    # The rollback of a failed swap leaves the provider to the SDK, which skips one another slot holds: a sibling that
    # failed on it and deferred to this instance is left with nobody to shut it down.
    'a rollback leaves a failed sibling\'s wrapper to the SDK': (
        ('FailedWrapperNotAbandoned',), [
        ("                ELSE p \\in registered /\\ LibraryShuts(i, p)\n",
         "                ELSE FALSE\n")]),
    # A failed build or an init deadline shuts down whatever the instance holds, a provider the user registered included.
    'a failure shuts down a provider the user registered': (
        ('UserProviderNeverShutByLibrary',), [
        ("    /\\ p \\notin userOwned\n    /\\ \\A j", "    /\\ \\A j")]),
    # The same for the library's own wrapper while a sibling on another domain is bound to it.
    'a failure shuts down a wrapper a live sibling is bound to': (
        ('LiveSiblingKeepsProvider',), [
        ("    /\\ \\A j \\in Instances \\ {i} : ~(Live(j) /\\ bound[domainOf[j]] = p /\\ failedOn[j] # p)\n", "")]),
    # A failure defers to a sibling that has failed on the provider too: with no owner, nothing ever shuts it down.
    'failures defer to each other': (
        ('FailedWrapperNotAbandoned',), [
        (" = p /\\ failedOn[j] # p)\n", " = p)\n")]),
    # The registry's finalizer shuts the API down without retiring its clients first (ApiInstances.retireAll dropped):
    # an instance on the API answers after the API is gone.
    'the registry closes without retiring its clients': (
        ('ClosedApiServesNone',), [
        ("    /\\ RetireAll\n    /\\ ShutApi({q \\in leftBehind",
         "    /\\ phase' = phase\n    /\\ ShutApi({q \\in leftBehind")]),
    # The registry's close shuts down every provider it ever bound, not the API's registered ones: one a swap or a
    # failure shut down already is shut down again.
    'the registry\'s close shuts a provider down twice': (
        ('SharedShutDownOnce',), [
        ("    /\\ RetireAll\n    /\\ ShutApi({q \\in leftBehind : q \\in registered /\\ q \\notin ApiShuts})\n",
         "    /\\ RetireAll\n"
         "    /\\ apiOpen' = FALSE\n"
         "    /\\ bound' = [d \\in Domains |-> None]\n"
         "    /\\ registered' = {}\n"
         "    /\\ userOwned' = {}\n"
         "    /\\ shuts' = [p \\in Providers |-> shuts[p] + (IF p \\in everBound THEN 1 ELSE 0)]\n"
         "    /\\ by' = [p \\in Providers |-> IF p \\in everBound THEN \"sdk\" ELSE by[p]]\n")]),
    # The registry's close does not wait for a swap in flight (a retirement that skips the swap lock): the swap settles
    # on a dead API, and its rollback binds a provider nothing will shut down.
    'the registry closes during a swap': (
        ('SharedShutDownOnce',), [
        ("    /\\ owner = None\n    /\\ swapping = {}\n", "    /\\ owner = None\n")]),
    # A live client's promise is evicted (a client a caller got, or a build still running, removed from the map): the
    # next getClient builds a second client for the domain.
    'a live client\'s promise is evicted': (
        ('OnlyFailedBuildsEvicted',), [
        ("Evict(i) ==\n    /\\ phase[i] = \"failed\"\n",
         "Evict(i) ==\n    /\\ phase[i] \\in {\"failed\", \"open\"}\n")]),
    # A registry client's timed-out swap whose new provider then fails leaves the provider it replaced running: the
    # registry's close does not release it (FeatureFlagRegistry.releaseLeftBehind dropped, as before it existed), and
    # the API's shutdown cannot reach a provider no slot holds.
    'a registry client\'s timed-out swap leaves the provider it replaced running': (
        ('SharedShutDownOnce',), [
        ("    /\\ ShutApi({q \\in leftBehind : q \\in registered /\\ q \\notin ApiShuts})\n",
         "    /\\ ShutApi({})\n")]),
    # A non-owner's timed-out swap on an API an instance owns whose new provider then fails leaves the provider it
    # replaced running: the owner's close does not release it (ApiInstances.releaseLeftBehind dropped from its close).
    'a non-owner\'s timed-out swap leaves the provider it replaced running': (
        ('SharedShutDownOnce',), [
        ("    /\\ ShutApi({q \\in orphans : q \\in registered} \\cup {q \\in leftBehind : q \\in registered /\\ q \\notin ApiShuts})\n",
         "    /\\ ShutApi({q \\in orphans : q \\in registered})\n")]),
    # A registry closed while a retry with another provider is still initializing, whose initialize() then fails, leaves
    # the provider the retry replaced running: the close interrupts the retry before its release, and the API's
    # shutdown reaches no provider a slot does not hold.
    'a registry close during a retry with another provider leaves the replaced one running': (
        ('SharedShutDownOnce',), [
        ("    /\\ leftBehind' = IF bound[d] \\notin {None, p} THEN leftBehind \\cup {bound[d]} ELSE leftBehind\n",
         "    /\\ leftBehind' = leftBehind\n")]),
    # An owner's timed-out swap records no orphan, as a non-owner's: when the new provider fails, nothing shuts the
    # previous one down.
    'an owner\'s timed-out swap records no orphan': (
        ('SharedShutDownOnce',), [
        ("    /\\ orphans' = IF owner = i /\\ ~HeldVisibly(q) THEN orphans \\cup {q} ELSE orphans\n",
         "    /\\ orphans' = orphans\n")]),
    # The orphan list shuts down a provider of the user's a slot the library can see holds, when the new provider fails.
    'an orphan the user\'s slot holds is shut down': (
        ('UserProviderNeverShutByLibrary', 'RegisteredNotShutDown'), [
        ("               ELSE {q \\in waiting \\cap orphans : q \\in registered /\\ ~HeldVisibly(q)}\n",
         "               ELSE {q \\in waiting \\cap orphans : q \\in registered}\n"),
        ("    /\\ orphans' = IF owner = i /\\ ~HeldVisibly(q) THEN orphans \\cup {q} ELSE orphans\n",
         "    /\\ orphans' = IF owner = i THEN orphans \\cup {q} ELSE orphans\n")]),
    # The orphan list counts only a provider of the user's as held by a slot: an owner's timed-out swap off its wrapper
    # shuts it down under the sibling that still serves it, whether the new provider then fails or initializes.
    'an orphan a sibling\'s slot holds is shut down': (
        ('RegisteredNotShutDown',), [
        ("HeldVisibly(p) == bound[Default] = p \\/ Visible(p)\n",
         "HeldVisibly(p) == p \\in userOwned /\\ (bound[Default] = p \\/ Visible(p))\n")]),
}


def mutate(name, edits):
    mutated = spec
    for old, new in edits:
        if mutated.count(old) != 1:
            sys.exit(f'{name}: the mutation no longer applies to Registry.tla; update mutants.py')
        mutated = mutated.replace(old, new)
    return mutated


def only(prop):
    """The configuration with `prop` as its one invariant or property."""
    section = 'PROPERTIES' if prop in cfg.split('PROPERTIES')[1] else 'INVARIANTS'
    return f"{cfg.split('INVARIANTS')[0]}{section}\n    {prop}\n"


def tlc(mutated, config):
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Registry.tla'), 'w').write(mutated)
        open(os.path.join(d, 'Registry.cfg'), 'w').write(config)
        args = ['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-deadlock',
                '-config', 'Registry.cfg', 'Registry.tla']
        return subprocess.run(args, cwd=d, capture_output=True, text=True).stdout


if len(sys.argv) != 2:
    sys.exit(__doc__)
jar = os.path.abspath(sys.argv[1])
missed = []
for name, (props, edits) in mutants.items():
    mutated = mutate(name, edits)
    for prop in props:
        out = tlc(mutated, only(prop))
        if re.search(r'(?:Invariant|Action property|Temporal propert\w+)\b.* (?:is|was|were) violated', out):
            print(f'{name}: caught by {prop}')
        else:
            print(f'{name}: NOT CAUGHT by {prop}\n{out[-600:]}')
            missed.append(name)
sys.exit(1 if missed else 0)
