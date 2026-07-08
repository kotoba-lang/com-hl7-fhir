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

## Maturity note (2026-07-08, ADR-2607083200) -- EU: EHDS Article 3 (Regulation (EU) 2025/327)

Follow-up closing the item the pass above explicitly deferred: EHDS
(European Health Data Space) was "considered but not implemented" because
that pass "did not have a verified primary-source reading of its operative
articles to hand". This pass does: EUR-Lex blocks automated `curl`/headless
fetches with an AWS WAF JS challenge, so the operative text was retrieved by
navigating EUR-Lex in a real (non-automated) browser session on 2026-07-08
and extracting Article 3's rendered text verbatim. The excerpt is archived,
with full retrieval-method provenance, at
[`kotoba-lang/emr-claims-primary-sources`](https://github.com/kotoba-lang/emr-claims-primary-sources)'s
`eu-ehds/ehds-article3-excerpt.md` (CELEX:32025R0327, Regulation (EU)
2025/327 of 11 February 2025).

Adds a `PatientAccessRequest` entity modeling **Article 3 ("Right of natural
persons to access their personal electronic health data"), paragraphs
(1)-(3) only** -- the only EHDS text with a verified primary-source reading
to hand:

- `priorityCategory` (boolean) -- whether the underlying record belongs to
  the "priority categories" Article 3(1)/(2) refer to. **This is a bare
  flag, not an enumerated category list**: the priority-categories list
  itself is defined in **Article 14**, which has not yet been retrieved
  from a primary source -- inventing that list here would be exactly the
  kind of unverified-legal-content guess this codebase's working agreement
  forbids.
- `accessMethod` (enum `"view"` / `"download"`, case-insensitive, required)
  -- `"view"` is Art. 3(1) (immediate, free, easily-readable/consolidated
  access once data is registered in an EHR system); `"download"` is
  Art. 3(2) (a free electronic copy in the European electronic health
  record exchange format). Validated by
  `hl7-fhir.validation/valid-ehds-access-method?`; anything else is
  rejected with 400.
- `restrictionApplied` (boolean) + `restrictionReason` (optional string) --
  Art. 3(3): a Member State may restrict/delay both rights "in accordance
  with Article 23" of GDPR (Regulation (EU) 2016/679), e.g. for
  patient-safety/ethical reasons. Enforced by a new **cross-field**
  validator, `hl7-fhir.validation/valid-ehds-restriction?`, wired through a
  new entity-spec key `:validate-record` (complementing the existing
  single-field `:validate`): `restrictionApplied=true` with a blank/absent
  `restrictionReason` is rejected with 400 on both create and update (update
  checks the *merged* record, so patching only `restrictionApplied` against
  an existing reason-less record is still caught).

**Explicitly out of scope / not implemented, and why**: Article 3
cross-references three other articles that were confirmed to exist but
whose text has **not** been retrieved yet -- Article 14 (the
priority-categories list itself), Article 4 (the "electronic health data
access services" definition) and Article 15 (the European electronic health
record exchange format's technical schema). None of the three is modeled:
`priorityCategory` stays a boolean flag (no category enum), `accessMethod`
only names Article 15 as an external format citation (no exchange-format
data structure), and no `electronic health data access service` entity is
added. This is the same "verified primary source or explicit not-yet-done
note, never a guess" discipline the GDPR Art. 9(2) pass above follows.
Fetching Article 14/15 (ideally via the same real-browser EUR-Lex
navigation this pass used, since automated fetches are WAF-blocked) is left
for a follow-up increment.

See `src/hl7_fhir/validation.cljc` (`valid-ehds-access-method?` /
`valid-ehds-restriction?`, with the scope caveats inline) and
`test/hl7_fhir/validation_test.cljc`'s `ehds-access-method-format` /
`ehds-restriction-cross-field` deftests / `test/hl7_fhir/main_test.cljc`'s
`patient-access-request-domain-validation` deftest for pass/fail coverage
(both access methods and case-insensitivity accepted, an out-of-set method
rejected, a restriction without a reason rejected on both create and merged
update, a restriction with a reason accepted). `bb test`: 14 deftests / 256
assertions as of this pass (up from 11/203).
