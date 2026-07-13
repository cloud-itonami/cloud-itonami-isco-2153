# cloud-itonami-isco-2153

Open Business Blueprint for **ISCO-08 2153**: Telecommunications Engineers — an ISCO
**Wave 1 (design & governance)** occupation per ADR-2607121000. This
is the SECOND wave-1 blueprint batch (21xx engineering design
professions): the design/analysis work is cognitive; physical
execution remains robotics-gated and out of the actor's scope.

**Maturity: `:implemented`** — TelecommunicationsEngineersAdvisor ⊣
TelecommunicationsEngineersGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 30 assertions green.

The RF link HARD invariants — arithmetic and interval containment,
not a suggestion:

1. **Link-margin floor** — the proposed link margin (dB) must be ≥
   the link's registered minimum margin.
2. **Spectrum containment** — the proposed carrier frequency must fall
   within the link's registered allocated band — spectrum allocation
   is a licensed boundary.

Also HARD: unregistered/foreign link, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:activate-link` (RF transmission activation), low confidence
(< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
