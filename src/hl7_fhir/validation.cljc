(ns hl7-fhir.validation
  "Structural (format-only) validators for identifiers that a US healthcare
  claim actually carries on the wire: the CMS-1500 / UB-04 paper forms and
  the X12 837 professional/institutional claim transaction set.

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
