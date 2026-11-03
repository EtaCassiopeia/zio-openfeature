"""Put each ownership bug back into the registry model and check that TLC reports the invariant that catches it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught.
"""
import os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = open(os.path.join(here, 'Registry.tla')).read()
cfg = open(os.path.join(here, 'Registry.cfg')).read()

# Each mutant names the invariants that may catch it: TLC reports the first violated in the configuration's order.
mutants = {
    # A non-owner's close tears the API down: siblings lose their provider (the slot of a closed sibling is empty before
    # the owner closes), and the owner shuts it down again.
    'a non-owner tears the API down': (
        ('ClosedNonOwnerKeepsSlot', 'SiblingsUnaffected', 'RegisteredNotShutDown', 'SharedShutDownOnce'), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ bound' = [d \\in Domains |-> None]\n"
         "    /\\ registered' = {}\n"
         "    /\\ shuts' = [p \\in Providers |-> shuts[p] + (IF p \\in registered THEN 1 ELSE 0)]\n"
         "    /\\ by' = [p \\in Providers |-> IF p \\in registered THEN \"sdk\" ELSE by[p]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, everBound >>")]),
    # A domain client shuts its own provider down when it closes: a sibling sharing it loses it, and the owner shuts
    # it down again.
    'a domain client shuts its provider down directly': (
        ('RegisteredNotShutDown', 'ForeignNeverShutByLibrary', 'SharedShutDownOnce'), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>",
         "    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ shuts' = [shuts EXCEPT ![bound[domainOf[i]]] = @ + 1]\n"
         "    /\\ by' = [by EXCEPT ![bound[domainOf[i]]] = \"library\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, everBound >>")]),
    # A non-owner's close retires its siblings, as only the owner's may.
    'a non-owner retires its siblings': (
        ('SiblingsUnaffected',), [
        ("    /\\ phase' = [phase EXCEPT ![i] = \"closed\"]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>",
         "    /\\ phase' = [j \\in Instances |-> IF phase[j] = \"open\" THEN \"closed\" ELSE phase[j]]\n"
         "    /\\ UNCHANGED << domainOf, owner, apiOpen, lock, swapping, swaps, bound, registered, shuts, by, everBound >>")]),
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
}


def mutate(name, edits):
    mutated = spec
    for old, new in edits:
        if mutated.count(old) != 1:
            sys.exit(f'{name}: the mutation no longer applies to Registry.tla; update mutants.py')
        mutated = mutated.replace(old, new)
    return mutated


def tlc(mutated):
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'Registry.tla'), 'w').write(mutated)
        open(os.path.join(d, 'Registry.cfg'), 'w').write(cfg)
        args = ['java', '-XX:+UseParallelGC', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-deadlock',
                '-config', 'Registry.cfg', 'Registry.tla']
        return subprocess.run(args, cwd=d, capture_output=True, text=True).stdout


if len(sys.argv) != 2:
    sys.exit(__doc__)
jar = os.path.abspath(sys.argv[1])
missed = []
for name, (invs, edits) in mutants.items():
    out = tlc(mutate(name, edits))
    m = re.search(r'(?:Invariant|Action property|Temporal property) (\w+) (?:is|was) violated', out)
    if m and m.group(1) in invs:
        print(f'{name}: caught by {m.group(1)}')
    else:
        print(f'{name}: NOT CAUGHT by {" or ".join(invs)}: {m.group(1) if m else "no violation"}\n{out[-600:]}')
        missed.append(name)
sys.exit(1 if missed else 0)
