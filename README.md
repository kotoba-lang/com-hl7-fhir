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
FHIR ones and wasn't touched. The `manifest.json` capability declaration was
intentionally left unchanged because it's paired with a specific built
`wasmCid`; hand-editing its `entities`/`routes`/`mcp.tools` lists without
rebuilding that WASM artifact would make the manifest *less* accurate, not
more.

## Maturity note (2026-07-08, ADR-2607083100) -- EU: GDPR Art. 9(2) lawful basis

Follow-up to the pass above, closing the item it explicitly deferred: "GDPR
Art. 9 special-category consent flags ... were considered ... but not
implemented". This is a EU/US/JP rotation increment (the JP leg landed in
`etzhayyim/root` PR #2982 -- karute/iryo acceptance boundary -- and the US
leg was the `Claim` pass above; EU had zero coverage across all three
domains before this pass).

Adds a `Consent` entity recording, per patient, which of the ten exceptions
in **Art. 9(2)(a)-(j) of Regulation (EU) 2016/679 (GDPR)** is relied on to
lift the Art. 9(1) prohibition on processing "data concerning health" --
every clinical resource in this actor (`Patient`/`Observation`/`Condition`/
...) is exactly that category of data:

- `specialCategoryData` (boolean) -- explicit machine-readable flag that the
  associated resource(s) are Art. 9(1) special-category data.
- `lawfulBasisArt9` (string, required) -- one of `"a"`..`"j"`
  (case-insensitive), validated against
  `hl7-fhir.validation/valid-gdpr-art9-lawful-basis?`. An unrecognized code
  (e.g. `"z"`, or the exception's full label instead of its point-letter) is
  rejected with 400 by `handle-create`/`handle-update`, the same wiring
  pattern the `Claim` entity uses for its US identifiers.

**Accuracy note**: the Art. 9(2)(a)-(j) point-letter structure and the
paraphrased label for each point in
`hl7-fhir.validation/gdpr-art9-2-lawful-bases` were checked against the
consolidated Regulation text via EUR-Lex (CELEX:32016R0679) and
gdpr-info.eu on 2026-07-08 -- this is treated as authoritative (not
"representative only"), since Art. 9's (a)-(j) list is a long-stable,
heavily-cited provision, unlike e.g. a newer instrument's implementing
detail. Explicitly out of scope for this pass, left for a follow-up:
**EHDS** (Regulation (EU) 2025/327, European Health Data Space -- patient
primary-use access rights / cross-border exchange-format metadata) was
considered but not implemented, because this pass did not have a verified
primary-source reading of its operative articles to hand and did not want
to guess at EHDS-specific field names/codes; and no EU Member State's
national implementing law (e.g. German BDSG Sec. 22) is modeled -- this
stays at GDPR-regulation level, per the task's explicit scope guardrail.

See `src/hl7_fhir/validation.cljc` (`gdpr-art9-2-lawful-bases` /
`valid-gdpr-art9-lawful-basis?`, with the accuracy/scope caveats inline) and
`test/hl7_fhir/validation_test.cljc`'s `gdpr-art9-lawful-basis-format`
deftest / `test/hl7_fhir/main_test.cljc`'s `consent-domain-validation`
deftest for pass/fail coverage (all ten point-letters accepted, an
out-of-set code and the full exception label both rejected, and that
`handle-update` enforces the same check). `bb test`: 11 deftests / 203
assertions as of this pass (up from 9/148).
