------------------------------ MODULE Targets ------------------------------
(***************************************************************************)
(* The rare shapes the registry replay must reach every run, each stated  *)
(* as "this step never happens" on GSpec.  TLC's counterexample to one is *)
(* a labelled behaviour of Generate.tla that ends in that step, which      *)
(* targets.py writes for RegistryReplaySpec.  Each is a leak a fix in the  *)
(* library closed; codemutants.py re-plants each fix and requires the      *)
(* replay to turn red on these behaviours.                                 *)
(***************************************************************************)
EXTENDS Generate

\* Registry topology: a retry with another provider replaced the provider a failed build left in its slot, and the
\* registry's close releases that wrapper, which nothing else shuts down (FeatureFlagRegistry's buildClient leaves it
\* behind; ApiInstances.releaseLeftBehind). Checked with timeouts off, so only a retry leaves a wrapper behind.
RegistryNeverReleasesARetrysWrapper ==
    [][~(/\ act'.name = "RegistryClose"
         /\ \E q \in leftBehind : q \in registered /\ q \notin ApiShuts)]_GVars

\* Shared topology: the owner's swap off a wrapper a sibling's slot still serves timed out, and the swap's provider's
\* late initialize() fails. The owner's orphan list must leave that wrapper to the sibling (OrphanedProviders'
\* heldByVisibleSlot): it is in no orphan or left-behind list, and an open sibling's named slot holds it
\* (the default slot is checked apart, so only a named one exercises the sibling check).
OwnerNeverOrphansASiblingsWrapper ==
    [][~(/\ act'.name = "InitSettle" /\ ~act'.ok
         /\ \E q \in Providers :
              /\ replaced[q] = act'.p
              /\ q \notin userOwned \cup leftBehind \cup orphans
              /\ \E j \in Instances :
                   j # owner /\ phase[j] = "open" /\ domainOf[j] \notin {None, Default} /\ bound[domainOf[j]] = q)]_GVars

\* Shared topology: an instance not owning the API swapped off its wrapper, the swap timed out, its provider's late
\* initialize() failed, and the owner's close releases the wrapper left behind (ApiInstances.releaseLeftBehind).
OwnerCloseNeverReleasesLeftBehind ==
    [][~(/\ act'.name = "OwnerClose"
         /\ \E q \in leftBehind : q \in registered /\ q \notin ApiShuts)]_GVars
=============================================================================
