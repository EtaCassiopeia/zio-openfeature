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
         "                    failedOn, deciding >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ bound' = [d \\in Domains |-> None]\n"
         "    /\\ registered' = {}\n"
         "    /\\ userOwned' = {}\n"
         "    /\\ shuts' = [p \\in Providers |-> shuts[p] + (IF p \\in registered THEN 1 ELSE 0)]\n"
         "    /\\ by' = [p \\in Providers |-> IF p \\in registered THEN \"sdk\" ELSE by[p]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, everBound, failedOn, deciding >>")]),
    # A domain client shuts its own provider down when it closes: a sibling sharing it loses it, and the owner shuts
    # it down again.
    'a domain client shuts its provider down directly': (
        ('RegisteredNotShutDown', 'ForeignNeverShutByLibrary', 'SharedShutDownOnce'), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ shuts' = [shuts EXCEPT ![bound[domainOf[i]]] = @ + 1]\n"
         "    /\\ by' = [by EXCEPT ![bound[domainOf[i]]] = \"library\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, everBound, failedOn, deciding >>")]),
    # A non-owner's close retires its siblings, as only the owner's may.
    'a non-owner retires its siblings': (
        ('SiblingsUnaffected',), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding >>",
         "    /\\ phase' = [j \\in Instances |-> IF phase[j] = \"open\" THEN \"closed\" ELSE phase[j]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, userOwned, shuts, by, everBound,\n"
         "                    failedOn, deciding >>")]),
    # getClient looks the domain up and inserts its promise without the semaphore: two callers build two clients.
    'getClient without the semaphore': (
        ('OneInstancePerDomain',), [
        ("    /\\ lock = None\n"
         "    /\\ ~\\E j \\in Instances : phase[j] # \"absent\" /\\ domainOf[j] = d\n"
         "    /\\ lock' = i\n",
         "    /\\ ~\\E j \\in Instances : phase[j] \\notin {\"absent\", \"checking\"} /\\ domainOf[j] = d\n"
         "    /\\ lock' = lock\n"),
        ("    /\\ phase[i] = \"checking\"\n    /\\ lock = i\n    /\\ own => owner = None\n    /\\ lock' = None\n",
         "    /\\ phase[i] = \"checking\"\n    /\\ own => owner = None\n    /\\ lock' = lock\n")]),
    # The rollback of a failed swap shuts the failed provider down although another slot holds it.
    'the library shuts a Foreign provider on rollback': (
        ('RegisteredNotShutDown', 'ForeignNeverShutByLibrary'), [
        ("    /\\ IF p \\in registered\n       THEN UNCHANGED << shuts, by >>\n       ELSE /\\ shuts' = [shuts EXCEPT ![p] = 1]",
         "    /\\ IF FALSE\n       THEN UNCHANGED << shuts, by >>\n       ELSE /\\ shuts' = [shuts EXCEPT ![p] = @ + 1]")]),
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
