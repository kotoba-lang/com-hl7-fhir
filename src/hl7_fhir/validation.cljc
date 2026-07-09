(ns hl7-fhir.validation
  "Structural (format-only) validators for identifiers that a US healthcare
  claim actually carries on the wire: the CMS-1500 / UB-04 paper forms and
  the X12 837 professional/institutional claim transaction set. Also holds
  the EU GDPR Art. 9(2) special-category-data lawful-basis code validator,
  and the EU EHDS (Regulation (EU) 2025/327) Article 3 primary-use
  access-request validators (see the sections below for scope/caveats
  specific to each).

  These are *format* checks, not *authority* checks: a structurally valid
  NPI is not guaranteed to be an NPI actually assigned by NPPES, and a
  structurally valid ICD-10-CM / CPT / HCPCS code is not guaranteed to be on
  the current CMS code table. Calling out to NPPES / CMS code tables is out
  of scope for a clean-room, network-isolated actor -- these functions only
  reject values that could never be valid, which is exactly the class of bug
  (typo'd NPI, malformed diagnosis code, transposed digits) that a naive
  string field silently accepts today.

  - NPI (National Provider Identifier, 45 CFR 162.410 / CMS NPI Final Rule):
    10 digits, last digit is the ISO/IEC 7812-1 Luhn check digit computed
    over the fixed issuer prefix \"80840\" followed by the first 9 digits.
  - ICD-10-CM (diagnosis code): 3-character category (letter + digit +
    digit-or-letter, e.g. neoplasm codes like C7A use an alpha 3rd char)
    plus an optional 1-4 character subdivision after a decimal point.
    Structural approximation only -- not validated against the maintained
    CMS ICD-10-CM code list.
  - CPT / HCPCS Level II (procedure code): CPT Category I (5 digits,
    00100-99499), CPT Category II (4 digits + F), CPT Category III (4
    digits + T), or HCPCS Level II (1 letter A-V + 4 digits)."
  (:require [clojure.string :as str]))

;; --- shared helpers -------------------------------------------------------

(defn- parse-long* [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn- digit-char? [c] (contains? #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9} c))

(defn- char->digit [c] (- (int c) (int \0)))

;; --- NPI -------------------------------------------------------------------

(defn- luhn-check-digit
  "ISO/IEC 7812-1 Luhn check digit for a numeric-string payload (the payload
  does not include the check digit itself)."
  [numeric-str]
  (let [ds (reverse (map char->digit numeric-str))
        total (reduce + (map-indexed
                          (fn [i d]
                            (if (even? i)
                              (let [d2 (* d 2)] (if (> d2 9) (- d2 9) d2))
                              d))
                          ds))]
    (mod (- 10 (mod total 10)) 10)))

(defn valid-npi?
  "true if s is exactly 10 digits whose final digit is the correct Luhn
  check digit over (\"80840\" ++ first 9 digits). 1234567893 is the
  canonical structurally-valid NPI used across the industry for tests."
  [s]
  (boolean
   (when (and (string? s) (= 10 (count s)) (every? digit-char? s))
     (let [first9 (subs s 0 9)
           check-digit (char->digit (nth s 9))]
       (= check-digit (luhn-check-digit (str "80840" first9)))))))

;; --- ICD-10-CM diagnosis code -----------------------------------------------

(def ^:private icd10-cm-pattern
  #"^[A-Z][0-9][0-9A-Z](\.[0-9A-Z]{1,4})?$")

(defn valid-icd10-cm?
  "true if s structurally matches an ICD-10-CM code (case-insensitive):
  letter + digit + (digit-or-letter) [+ '.' + 1-4 alnum]. Examples: I10,
  E11.9, Z00.00, S72.001A, C7A.00 (neuroendocrine tumor category), U07.1
  (COVID-19, added to the US CM list in 2020)."
  [s]
  (boolean (and (string? s) (re-matches icd10-cm-pattern (str/upper-case s)))))

;; --- CPT / HCPCS Level II procedure code ------------------------------------

(defn valid-procedure-code?
  "true if s structurally matches a CPT Category I (5 digits, 00100-99499),
  CPT Category II (4 digits + F), CPT Category III (4 digits + T), or
  HCPCS Level II (letter A-V + 4 digits) procedure code."
  [s]
  (boolean
   (when (string? s)
     (let [u (str/upper-case s)]
       (or (and (re-matches #"^[0-9]{5}$" u)
                (<= 100 (parse-long* u) 99499))
           (re-matches #"^[0-9]{4}F$" u)
           (re-matches #"^[0-9]{4}T$" u)
           (re-matches #"^[A-V][0-9]{4}$" u))))))

;; --- GDPR Art. 9(2) special-category-data lawful basis ----------------------
;;
;; Every clinical resource this actor models (Patient, Observation,
;; Condition, ...) carries "data concerning health" within the meaning of
;; Art. 9(1) of Regulation (EU) 2016/679 (GDPR, OJ L 119, 4.5.2016, p. 1),
;; which prohibits processing such data outright unless one of the ten
;; exceptions enumerated in Art. 9(2)(a)-(j) applies. This section validates
;; only that a *code* naming one of those ten exceptions is well-formed --
;; it cannot and does not verify that the cited exception is factually true
;; for a given record (e.g. that explicit consent under (a) was actually
;; obtained is a business-process fact outside a structural validator's
;; reach), which mirrors the NPI/ICD-10-CM/CPT validators above only
;; checking format, not authority.
;;
;; The point-letter labels and their paraphrased (non-verbatim) meanings
;; below were checked against the consolidated Art. 9(2) text via EUR-Lex
;; (CELEX:32016R0679) and gdpr-info.eu on 2026-07-08. Article 9 is one of
;; the most litigated/cited GDPR provisions and its (a)-(j) structure has
;; been stable since the Regulation's 2016 adoption, so -- unlike a newer or
;; still-evolving instrument -- this list is treated as authoritative, not
;; "representative only". It intentionally does not model any EU Member
;; State's national implementing law (e.g. German BDSG Sec. 22, which adds
;; conditions on top of (h)/(i)/(j) for health data specifically) -- that is
;; explicitly out of scope for this pass.

(def gdpr-art9-2-lawful-bases
  "The ten Art. 9(2) exceptions that lift the Art. 9(1) prohibition on
  processing special categories of personal data, keyed by the same
  point-letter the Regulation itself uses. Map values are short paraphrased
  labels for lookup/display, not the operative legal text -- see Article 9
  of Regulation (EU) 2016/679 for the authoritative wording."
  {"a" "explicit consent to processing for one or more specified purposes"
   "b" "obligations/rights in employment, social security or social protection law"
   "c" "vital interests, where the data subject is physically or legally incapable of consenting"
   "d" "not-for-profit body (political/philosophical/religious/trade-union aim), members/regular contacts only, with safeguards"
   "e" "personal data manifestly made public by the data subject"
   "f" "establishment, exercise or defence of legal claims, or courts acting in a judicial capacity"
   "g" "substantial public interest under proportionate Union or Member State law"
   "h" "preventive or occupational medicine, medical diagnosis, health/social care or treatment, or management of health/social-care systems, under law or a contract with a health professional"
   "i" "public health (e.g. serious cross-border health threats, quality/safety of health care, medicinal products or medical devices)"
   "j" "archiving in the public interest, scientific/historical research, or statistical purposes per Art. 89(1)"})

(defn valid-gdpr-art9-lawful-basis?
  "true if s (case-insensitive) is one of the ten Art. 9(2) point-letters
  (\"a\".. \"j\") that can lift the Art. 9(1) special-category-data
  prohibition. Only the code's membership in that fixed ten-item set is
  checked -- see the namespace-level caveat above about what this does and
  doesn't guarantee."
  [s]
  (boolean (and (string? s) (contains? gdpr-art9-2-lawful-bases (str/lower-case s)))))

;; --- EHDS Art. 3 primary-use access request (Regulation (EU) 2025/327) -----
;;
;; EHDS = the European Health Data Space Regulation (EU) 2025/327 of the
;; European Parliament and of the Council of 11 February 2025, CELEX
;; 32025R0327, in force 2025-03-26. Article 3 ("Right of natural persons to
;; access their personal electronic health data") gives natural persons (1)
;; a right to immediate, free, easily-readable *view* access to their
;; priority-category electronic health data once it is registered in an EHR
;; system, and (2) a right to *download* a free electronic copy of that same
;; data in the European electronic health record exchange format. Article
;; 3(3) lets a Member State restrict (delay) both rights, in accordance with
;; Art. 23 GDPR, e.g. for patient-safety/ethical reasons.
;;
;; The verbatim Article 3(1)-(3) text this validator is based on was
;; retrieved via a real-browser EUR-Lex session on 2026-07-08 (EUR-Lex blocks
;; automated fetches with an AWS WAF JS challenge) and is archived at
;; orgs/kotoba-lang/emr-claims-primary-sources/eu-ehds/ehds-article3-excerpt.md.
;; Article 3 cross-references three other articles: Article 14 (the
;; priority-categories list) and Article 4 (the "electronic health data
;; access services" definition) and Article 15 (the European electronic
;; health record exchange format). Article 14(1) was retrieved the same way
;; on 2026-07-09 and is archived at eu-ehds/ehds-article14-15-excerpt.md, so
;; the priority categories ARE now enumerated below (`ehds-priority-categories`
;; / `valid-ehds-priority-category?`). Article 4 remains unretrieved. Article
;; 15 was retrieved too, but it does NOT itself define a concrete exchange
;; format schema -- it delegates that to future European Commission
;; implementing acts (secondary legislation, not yet published) -- so,
;; consistent with this codebase's rule against inventing unverified legal
;; content, this namespace still does NOT model the Article 15 exchange
;; format's internal schema (only the fact that a download was requested "in
;; the Art. 15 format" is modeled, as an external citation, not a data
;; structure).

(def ehds-access-methods
  "The two ways Article 3 lets a natural person exercise primary-use access
  to their personal electronic health data: \"view\" (Art. 3(1) -- immediate,
  free, easily-readable/consolidated access after EHR registration) and
  \"download\" (Art. 3(2) -- free-of-charge electronic copy in the Article 15
  exchange format). Article 3 defines no other access method."
  #{"view" "download"})

(defn valid-ehds-access-method?
  "true if s (case-insensitive) is \"view\" or \"download\" -- the two
  Article 3 primary-use access methods. Anything else (including the empty
  string, nil, or a non-string) is rejected."
  [s]
  (boolean (and (string? s) (contains? ehds-access-methods (str/lower-case s)))))

;; --- EHDS Art. 14(1) priority categories (Regulation (EU) 2025/327) --------
;;
;; Article 14 ("Priority categories of personal electronic health data for
;; primary use") is the article Article 3 cross-references for what counts
;; as a "priority category" record. Its verbatim text was retrieved via a
;; real-browser EUR-Lex session on 2026-07-09 (same WAF-workaround method as
;; Article 3) and is archived at
;; orgs/kotoba-lang/emr-claims-primary-sources/eu-ehds/ehds-article14-15-excerpt.md.
;; Article 14(1) lists exactly six priority categories, (a)-(f); Annex I's
;; per-category "main characteristics" were not retrieved and are not
;; modeled here (future work if a more granular model is ever needed).
;; Article 14(1) also lets a Member State add national categories on top of
;; these six via its own national law -- that per-Member-State extension is
;; explicitly out of scope for this pass (same discipline as the GDPR Art.
;; 9(2) validator above not modeling national implementing law).

(def ehds-priority-categories
  "The six Article 14(1)(a)-(f) priority categories of personal electronic
  health data for primary use, kebab-cased from the Regulation's own
  wording: \"patient-summary\" (a), \"electronic-prescription\" (b),
  \"electronic-dispensation\" (c), \"medical-imaging\" (d, \"medical imaging
  studies and related imaging reports\"), \"medical-test-results\" (e,
  \"medical test results, including laboratory and other diagnostic results
  and related reports\"), \"discharge-report\" (f)."
  #{"patient-summary" "electronic-prescription" "electronic-dispensation"
    "medical-imaging" "medical-test-results" "discharge-report"})

(defn valid-ehds-priority-category?
  "true if s (case-insensitive) is one of the six Article 14(1) priority
  categories. Anything else (including the empty string, nil, or a
  non-string) is rejected -- this deliberately does not accept a
  Member-State-added national category (Article 14(1) final paragraph),
  which is out of scope for this pass."
  [s]
  (boolean (and (string? s) (contains? ehds-priority-categories (str/lower-case s)))))

(defn valid-ehds-restriction?
  "Cross-field check for Article 3(3): a Member-State restriction (a GDPR
  Art. 23-aligned delay of Art. 3(1)/(2) access) must carry a reason --
  an undocumented restriction would silently withhold a patient's access
  right with no audit trail of why it was withheld. Takes the whole record
  map (not a single field value), unlike this namespace's other validators:
  returns true (pass) when `:restrictionApplied` is falsy/absent, or when it
  is truthy and `:restrictionReason` is a non-blank string; false otherwise."
  [data]
  (let [applied (get data :restrictionApplied)
        reason (get data :restrictionReason)]
    (if applied
      (boolean (and (string? reason) (not (str/blank? reason))))
      true)))
