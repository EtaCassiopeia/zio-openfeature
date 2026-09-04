/*
 * HookPipeline.als
 *
 * Alloy 6 model of the OpenFeature hook execution pipeline in
 * zio-openfeature.
 *
 * Source of truth:
 *   core/src/main/scala/zio/openfeature/Hook.scala:111-165
 *   core/src/main/scala/zio/openfeature/FeatureFlagsLive.scala:173-228
 *   core/src/main/scala/zio/openfeature/internal/FeatureFlagsState.scala:11-12
 *
 * Run with Alloy Analyzer 6:
 *   java -jar alloy6.jar HookPipeline.als
 *
 * Or from the command line (headless):
 *   java -cp alloy6.jar edu.mit.csail.sdg.alloy4whole.ExampleUsingTheCompiler \
 *     HookPipeline.als
 *
 * The five assertions at the bottom can be run individually with:
 *   check ReverseSymmetry          for 4
 *   check FinallyTotality          for 4
 *   check ProviderHookExactlyOnce  for 4
 *   check HookDataIdentityKey      for 4
 *   check ApiClientInvocationOrder for 4
 */

// ── Tier: where a hook is registered ──────────────────────────────────
abstract sig Tier {}
one sig ApiTier, ClientTier, InvocationTier, ProviderTier extends Tier {}

// ── Hook ──────────────────────────────────────────────────────────────
sig Hook {
  tier : one Tier,
  // Registration position within the tier (0-based)
  regIndex : one Int
}

// ── FlagValueType ─────────────────────────────────────────────────────
abstract sig FlagValueType {}
one sig BoolType, StringType, IntType, DoubleType extends FlagValueType {}

// ── Stage ─────────────────────────────────────────────────────────────
abstract sig Stage {}
one sig Before, After, ErrorStage, FinallyStage extends Stage {}

// ── Execution: one invocation of a hook stage in an evaluation ────────
sig Execution {
  eval     : one Evaluation,
  hook     : one Hook,
  stage    : one Stage,
  // Execution order within this (eval, stage) pair (0-based)
  execIdx  : one Int,
  // Did the evaluation fail before this stage was reached?
  evalFailed : one Bool
}

// ── Evaluation ────────────────────────────────────────────────────────
sig Evaluation {
  flagType : one FlagValueType,
  // Did a hook's before stage throw / fail (causing error path)?
  beforeFailed : one Bool
}

// ── Bool utility ──────────────────────────────────────────────────────
abstract sig Bool {}
one sig True, False extends Bool {}

// ── allHooks: hooks the ZIO layer composes (post-e41ca60) ─────────────
// allHooks = apiHooks ++ clientHooks ++ invocationHooks
// Provider hooks are NOT in allHooks — they run inside the Java SDK call.
fun allHooks : set Hook { Hook - (tier.ProviderTier) }

// ── applicableHooks: filtered by flag type (spec 4.4.2.1) ─────────────
// In the model all hooks support all types (simplification);
// to model per-type support, add a `supportedTypes : set FlagValueType` field.
fun applicableHooks[e: Evaluation] : set Hook { allHooks }

// ── Helpers ───────────────────────────────────────────────────────────

// Execution index among all executions of (eval, stage)
fun execsForStage[e: Evaluation, s: Stage] : set Execution {
  { x : Execution | x.eval = e and x.stage = s }
}

// Execution index among (eval, Before)
fun beforeExecs[e: Evaluation] : set Execution {
  execsForStage[e, Before]
}

fun finallyExecs[e: Evaluation] : set Execution {
  execsForStage[e, FinallyStage]
}

// ── Well-formedness: indexes are dense 0..n-1 ────────────────────────
pred denseIndexes {
  // execIdx values for each (eval, stage) pair form 0..n-1
  all e: Evaluation, s: Stage |
    let execs = execsForStage[e, s] |
      all i: Int |
        (i >= 0 and i < #execs) => one x: execs | x.execIdx = i

  // regIndex values within each tier form 0..m-1
  all t: Tier |
    let hs = tier.t |
      all i: Int |
        (i >= 0 and i < #hs) => one h: hs | h.regIndex = i
}

// ── Before ordering matches registration order ────────────────────────
// Hook with smaller regIndex in its tier runs earlier in Before.
// Tier ordering: ApiTier < ClientTier < InvocationTier (ProviderTier excluded).
fun tierOrder[t: Tier] : Int {
  t = ApiTier        => 0 else
  t = ClientTier     => 1 else
  t = InvocationTier => 2 else 3
}

// Global registration position: tier * MAX_HOOKS_PER_TIER + regIndex
// (MAX_HOOKS_PER_TIER large enough — use 10 in bound-4 models)
fun globalRegPos[h: Hook] : Int {
  mul[tierOrder[h.tier], 10] + h.regIndex
}

pred beforeOrderMatchesRegistration {
  all e: Evaluation |
    all disj x, y: beforeExecs[e] |
      (globalRegPos[x.hook] < globalRegPos[y.hook]) => (x.execIdx < y.execIdx)
}

// ── After / Error / Finally run in reverse Before order ───────────────
pred reverseOrderForPost {
  all e: Evaluation |
    all s: Stage - Before |
      all disj x, y: execsForStage[e, s] |
        (globalRegPos[x.hook] < globalRegPos[y.hook]) => (x.execIdx > y.execIdx)
}

// ── Structural constraints ────────────────────────────────────────────

pred structuralConstraints {
  denseIndexes
  beforeOrderMatchesRegistration
  reverseOrderForPost

  // Every execution belongs to an applicable hook
  all x: Execution | x.hook in applicableHooks[x.eval]

  // The code runs before and finallyAfter for EVERY applicable hook
  // (simplification: partial-before on hook throw is not modelled here).
  // This is the completeness constraint that makes the ordering assertions
  // meaningful — without it, missing executions make them vacuously true.
  all e: Evaluation, h: applicableHooks[e] |
    one { x: Execution | x.eval = e and x.hook = h and x.stage = Before }
  all e: Evaluation, h: applicableHooks[e] |
    one { x: Execution | x.eval = e and x.hook = h and x.stage = FinallyStage }

  // Each (eval, hook) pair appears at most once per the remaining stages
  all e: Evaluation, h: Hook, s: Stage - (Before + FinallyStage) |
    lone { x: Execution | x.eval = e and x.hook = h and x.stage = s }

  // Integers are non-negative
  all x: Execution | x.execIdx >= 0
  all h: Hook       | h.regIndex >= 0
}

// ── Assertion 1: ReverseSymmetry ──────────────────────────────────────
//
// For every (eval, hook) pair, the before-execIdx + finally-execIdx
// = (#applicableHooks - 1).
// This encodes: finallyAfter runs in reverse Before order, so the hook
// with execIdx k in Before has execIdx (n-1-k) in FinallyStage.
assert ReverseSymmetry {
  structuralConstraints =>
    all e: Evaluation, h: applicableHooks[e] |
      let bx = { x: beforeExecs[e]  | x.hook = h } |
      let fx = { x: finallyExecs[e] | x.hook = h } |
        one bx and one fx =>
          add[bx.execIdx, fx.execIdx] = sub[#applicableHooks[e], 1]
}

// ── Assertion 2: FinallyTotality ──────────────────────────────────────
//
// For every evaluation, every applicable hook has EXACTLY ONE finallyAfter
// execution — regardless of whether the evaluation succeeded or failed.
//
// This is what the code does (ensuring over all applicableHooks.reverse).
// The open question: does this match spec §4.3.4 for hooks whose `before`
// never ran because a prior hook's `before` threw?
//
// Expected result: COUNTEREXAMPLE if we add the constraint that finallyAfter
// should only run for hooks whose before was actually invoked.
// As-written (no such constraint), this assertion HOLDS — the code runs
// finallyAfter for ALL hooks unconditionally.
assert FinallyTotality {
  structuralConstraints =>
    all e: Evaluation |
      all h: applicableHooks[e] |
        one { x: finallyExecs[e] | x.hook = h }
}

// ── Assertion 3: ProviderHookExactlyOnce ──────────────────────────────
//
// After commit e41ca60: allHooks does not include ProviderTier hooks.
// Provider hooks run inside the Java SDK's client.getXxxDetails call.
// Therefore no Execution should reference a ProviderTier hook via ZIO.
//
// This assertion checks the post-fix invariant.
assert ProviderHookExactlyOnce {
  structuralConstraints =>
    no x: Execution | x.hook.tier = ProviderTier
}

// ── Assertion 4: HookDataIdentityKey ──────────────────────────────────
//
// Hook.compose builds hookDataMap: Map[FeatureHook, HookData] keyed by
// object identity. If the same Hook instance appears twice in the list,
// both registrations share the same HookData slot.
//
// In the model: two Executions in the same (eval, stage) cannot reference
// the same Hook (dense unique hooks per stage per eval).
//
// If this assertion FAILS, it means a hook could appear twice with distinct
// data — i.e., identity-keying collapses duplicate registrations.
assert HookDataIdentityKey {
  structuralConstraints =>
    all e: Evaluation, s: Stage |
      all disj x, y: execsForStage[e, s] |
        x.hook != y.hook
}

// ── Assertion 5: ApiClientInvocationOrder ─────────────────────────────
//
// Before stage: all ApiTier hooks run before all ClientTier hooks,
// which run before all InvocationTier hooks.
// Matches FeatureFlagsLive.scala:186: allHooks = apiHooks ++ clientHooks ++ extraHooks
assert ApiClientInvocationOrder {
  structuralConstraints =>
    all e: Evaluation |
      all x: beforeExecs[e], y: beforeExecs[e] |
        (x.hook.tier = ApiTier and y.hook.tier = ClientTier) =>
          x.execIdx < y.execIdx
      and
        (x.hook.tier = ApiTier and y.hook.tier = InvocationTier) =>
          x.execIdx < y.execIdx
      and
        (x.hook.tier = ClientTier and y.hook.tier = InvocationTier) =>
          x.execIdx < y.execIdx
}

// ── Run commands ──────────────────────────────────────────────────────
//
// Add these as separate Run/Check entries in the Alloy Analyzer, or
// invoke them from the command-line runner.

// 6 Int gives signed range -32..31; max globalRegPos = 2*10+3 = 23, fits safely.
check ReverseSymmetry          for 4 but 6 Int
check FinallyTotality          for 4 but 6 Int
check ProviderHookExactlyOnce  for 4 but 6 Int
check HookDataIdentityKey      for 4 but 6 Int
check ApiClientInvocationOrder for 4 but 6 Int

// Show a valid instance (sanity check)
run showInstance {
  structuralConstraints
  #Hook >= 3
  some h: Hook | h.tier = ApiTier
  some h: Hook | h.tier = ClientTier
  some h: Hook | h.tier = ProviderTier
  #Evaluation >= 1
} for 4 but 6 Int

