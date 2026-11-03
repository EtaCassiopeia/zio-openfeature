#!/usr/bin/env python3
"""Put each bug the breaker's design excludes into the model and check that TLC reports it.

Usage: python3 mutants.py <path to tla2tools.jar>.  Exits non-zero when a mutant goes uncaught, or is caught by anything
but its expected verdict.
"""
import os
import sys

from breakercheck import edit, liveness_cfg, main_cfg, signals_cfg, spec, tlc

jar = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'tla2tools.jar')
TAKEOVER = 'ELSE IF probeElapsed THEN c.successes'
RESET_GUARD = 'IF s.circuit.kind = "Open" /\\ s.circuit.reason \\in op.openers THEN'
mutants = {
    # A transition keeps the epoch: an outcome from an earlier state passes current(permit).
    'a transition does not bump the epoch': ('StaleOutcomeIgnored', main_cfg, [
        ('epoch |-> s.epoch + 1]', 'epoch |-> s.epoch]')]),
    # An outcome skips current(permit).
    'an outcome is applied whatever its permit': ('StaleOutcomeIgnored', main_cfg, [
        ('if permit[self].epoch = st.epoch /\\ RecordNext', 'if RecordNext')]),
    # recordSuccess and friends write instead of compareAndSet: the epoch was checked on a state that has since moved.
    'an outcome overwrites without a CAS': ('StaleOutcomeIgnored', main_cfg, [
        ('CRecordCas:\n    if st = snap then', 'CRecordCas:\n    if TRUE then')]),
    # acquire grants while the slot is occupied and its timeout has not passed.
    'acquire grants a second probe while probing': ('AtMostOneProbe', main_cfg, [
        (TAKEOVER, 'ELSE IF TRUE THEN c.successes')]),
    # acquire writes instead of compareAndSet: two callers that read a free slot both take it.
    'acquire takes the slot without a CAS': ('AtMostOneProbe', main_cfg, [
        ('CAcquireCas:\n    if st = snap then', 'CAcquireCas:\n    if TRUE then')]),
    # No caller can steal the slot of a probe that died.
    'no probeTimeout takeover': ('temporal', liveness_cfg, [(TAKEOVER, 'ELSE IF FALSE THEN c.successes')]),
    # resetIf also closes a circuit failures opened: an event READY overrides the failure count.
    'resetIf resets a circuit that failures opened': ('ReasonRespected', main_cfg, [
        (RESET_GUARD, 'IF s.circuit.kind = "Open" /\\ (s.circuit.reason \\in op.openers \\/ s.circuit.reason = "Failures") THEN')]),
    # A polled READY closes a circuit an event tripped: the SDK's READY default undoes what the delegate said.
    # It takes two signals: the trip, then the READY.
    'a polled READY closes an event trip': ('ReasonRespected', signals_cfg, [
        (RESET_GUARD, 'IF s.circuit.kind = "Open" /\\ s.circuit.reason # "Failures" THEN')]),
    # transitionToHalfOpen also moves a circuit failures opened: it skips resetTimeout.
    'transitionToHalfOpen moves a circuit that failures opened': ('ReasonRespected', main_cfg, [
        ('IF s.circuit.kind = "Open" /\\ s.circuit.reason # "Failures"\n', 'IF s.circuit.kind = "Open"\n')]),
    # A worse trip leaves a stale circuit stale: the breaker keeps reporting STALE for a delegate in ERROR.
    'a worse trip does not clear stale': ('StaleFlag', signals_cfg, [
        ('ELSE IF c.stale /\\ ~op.stale THEN Transition(s, [c EXCEPT !.stale = FALSE], s.failures)\n        ELSE s',
         'ELSE s')]),
    # An event takes over a poll's circuit with either's stale, not both: an event ERROR would read as STALE.
    'the event takeover keeps stale if either was stale': ('StaleFlag', signals_cfg, [
        ('c.stale /\\ op.stale', 'c.stale \\/ op.stale')]),
}
missed = []
for name, (expected, config, edits) in mutants.items():
    verdict, out = tlc(jar, edit(spec, edits, name), config)
    if verdict == expected:
        print(f'{name}: caught ({verdict})')
    else:
        print(f'{name}: NOT CAUGHT as {expected}, got {verdict}\n{out}')
        missed.append(name)
sys.exit(1 if missed else 0)
