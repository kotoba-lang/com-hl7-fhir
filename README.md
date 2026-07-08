# Hl7_fhir Clean Room Actor

Clean-room API-compatible implementation of the hl7_fhir deep system protocol, backed by Datomic and Py Kotodama WASM.

## Provenance

Relocated 2026-07-04 from `etzhayyim/root/20-actors/hl7_fhir-compat` to
`kotoba-lang/com-hl7-fhir` per the org-taxonomy library-placement rule (any
library/substrate code belongs in `kotoba-lang`, ADR-2606302300), following
the same relocation pattern as `kami-nv-compat` (ADR-2607020130). See
ADR-2607041500 for the full ~1,027-repo migration plan and naming convention.

## Maturity note (2026-07-08, ADR-2607083000)

As relocated, this repo (and its `com-epic-fhir` / `com-eclinicalworks`
siblings) was a `deepen_actors.py`-generated generic CRUD scaffold: FHIR
resource *names* (Patient/Observation/Encounter/MedicationRequest/
AllergyIntolerance/Condition) with no HL7/FHIR-specific validation --
`:required`/`:coerce` only check presence and JSON-ish type coercion, not
domain format.

This pass adds one real increment: a `Claim` entity modeling the CMS-1500 /
UB-04 / X12 837 professional-claim minimum field set
(`billingProviderNpi`/`subscriberId`/`diagnosisCode`/`procedureCode`), with
actual format validation wired into the generic `handle-create`/
`handle-update` path via a new `:validate` entity-spec key:

- `billingProviderNpi` -- NPI check digit (ISO/IEC 7812-1 Luhn over the
  `"80840"`-prefixed 9-digit identifier, 45 CFR 162.410).
- `diagnosisCode` -- ICD-10-CM structural shape (letter + digit +
  digit-or-letter, optional 1-4 char subdivision after a decimal point).
- `procedureCode` -- CPT Category I/II/III or HCPCS Level II structural
  shape.

See `src/hl7_fhir/validation.cljc` (pure validators, with their own
docstring caveats about what "format valid" does and doesn't guarantee) and
`test/hl7_fhir/validation_test.cljc` / the `claim-domain-validation` deftest
in `test/hl7_fhir/main_test.cljc` for pass/fail coverage (bad NPI check
digit, wrong length, malformed ICD-10-CM, malformed CPT/HCPCS, and that
`handle-update` enforces the same checks). `bb test` runs both files
(9 deftests / 148 assertions as of this pass).

Not done in this pass (left for a follow-up increment, one repo/topic at a
time per this repo's working agreement): `com-epic-fhir` and
`com-eclinicalworks` still have the un-validated generic scaffold (they are
separate git repos, not a shared dependency, so this fix doesn't propagate
to them); `com-athenahealth` uses vendor-specific field names instead of
FHIR ones and wasn't touched; GDPR Art. 9 special-category consent flags /
EHDS patient-access metadata were considered (see the task that produced
this pass) but not implemented, to keep this increment to one well-tested
topic (US billing-identifier formats) rather than two half-done ones. The
`manifest.json` capability declaration was intentionally left unchanged
because it's paired with a specific built `wasmCid`; hand-editing its
`entities`/`routes`/`mcp.tools` lists without rebuilding that WASM artifact
would make the manifest *less* accurate, not more.
