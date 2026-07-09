(ns hl7-fhir.main-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [hl7-fhir.main :as m]))

(defn- dummy [field coerce] (case (get coerce field) :int 1 :float 1.0 :bool true (name field)))
(defn- full-record
  "A record satisfying an entity-spec's :required fields. Entities with
  format `:validate`rs (e.g. Claim's NPI/ICD-10-CM/CPT checks) can't be
  satisfied by the generic field-name-as-value heuristic, so they carry an
  explicit known-good `:sample` in the spec and this prefers it."
  [{:keys [required coerce sample]}]
  (or sample (into {} (map (fn [f] [f (dummy f coerce)]) required))))

(deftest route-surface
  (is (= (* 5 (count m/entity-specs)) (count m/routes)))
  (doseq [{:keys [plural]} m/entity-specs]
    (let [paths (set (map (juxt :method :path) m/routes)) base (str "/v1/" plural)]
      (is (contains? paths ["POST" base])) (is (contains? paths ["GET" base]))
      (is (contains? paths ["GET" (str base "/{id}")])) (is (contains? paths ["PATCH" (str base "/{id}")]))
      (is (contains? paths ["DELETE" (str base "/{id}")])))))

(deftest crud-roundtrip
  (doseq [{:keys [entity id-prefix] :as spec} m/entity-specs]
    (let [s (m/fresh-store) [rec status] (m/handle-create s entity (full-record spec))]
      (is (= 201 status) (str entity " create"))
      (is (str/starts-with? (:id rec) (str id-prefix "_")) (str entity " id-prefix"))
      (is (= [rec 200] (m/handle-get s entity (:id rec) {})) (str entity " get"))
      (is (= (:id rec) (:id (first (m/handle-update s entity (:id rec) {})))))
      (is (= 200 (second (m/handle-delete s entity (:id rec)))))
      (is (= 404 (second (m/handle-get s entity (:id rec) {})))))))

(deftest validation
  (doseq [{:keys [entity required] :as spec} m/entity-specs]
    (when (seq required)
      (let [s (m/fresh-store)]
        (is (= 400 (second (m/handle-create s entity {}))) (str entity " missing-required"))
        (is (= 400 (second (m/handle-create s entity (assoc (full-record spec) :__bogus__ 1)))) (str entity " unknown"))))))

;; ── FIXED (2026-07-08 health-check pass, modeled on the etzhayyim/iryo
;; capability-gate malformed-date fix) ── a request body that isn't a JSON
;; object at all (a bare string, number, or non-empty array) used to crash
;; handle-create/handle-update UNCAUGHT (ClassCastException via
;; reject-unknown's `(keys data)` on a string; IllegalArgumentException on a
;; number/`reduce-kv`), instead of degrading to a 400 like every other
;; malformed-input case in this namespace (missing/unknown/malformed-format
;; fields). `reject-non-map` now gates handle-create/handle-update on
;; `(map? data)` before any of that runs.
(deftest malformed-request-body-degrades-gracefully-not-a-crash
  (doseq [{:keys [entity] :as spec} m/entity-specs]
    (doseq [bad-body ["not-a-map" 123 [1 2 3] true]]
      (testing (str entity " create with body " (pr-str bad-body))
        (let [s (m/fresh-store)
              [body status] (m/handle-create s entity bad-body)]
          (is (= 400 status))
          (is (= "Request body must be a JSON object" (:message (:error body))))))
      (testing (str entity " update with body " (pr-str bad-body))
        (let [s (m/fresh-store)
              [rec _] (m/handle-create s entity (full-record spec))
              [body status] (m/handle-update s entity (:id rec) bad-body)]
          (is (= 400 status))
          (is (= "Request body must be a JSON object" (:message (:error body)))))))
    ;; regression guard: nil is exempted from this fix (it never crashed --
    ;; `(keys nil)`/`(get nil f)` are already nil-safe) and keeps degrading
    ;; via the pre-existing require-fields "missing required fields" path.
    (testing (str entity " create with nil body (pre-existing, unaffected)")
      (let [s (m/fresh-store)
            [body status] (m/handle-create s entity nil)]
        (is (= 400 status))
        (is (re-find #"Missing required fields" (:message (:error body))))))))

(deftest coercion
  (doseq [{:keys [entity coerce] :as spec} m/entity-specs]
    (when (seq coerce)
      (let [s (m/fresh-store) [rec _] (m/handle-create s entity (full-record spec))]
        (doseq [[f kind] coerce]
          (is (case kind :int (integer? (get rec f)) :float (float? (get rec f)) :bool (boolean? (get rec f)) true)
              (str entity "/" (name f))))))))

(deftest healthz
  (let [[body status] (m/healthz)]
    (is (= 200 status)) (is (= "hl7_fhir-compat" (:actor body))) (is (= (set m/entities) (set (:entities body))))))

;; --- Claim: US billing-identifier format validation (ADR-2607083000) -------
;; The generic `validation` deftest above only covers missing-required /
;; unknown-field rejection, which every entity shares. Claim is the one
;; entity whose fields carry real domain-format constraints (NPI check
;; digit, ICD-10-CM shape, CPT/HCPCS shape), so it gets its own deftest
;; that a naive string field would never reject.
(def ^:private claim-sample
  (:sample (first (filter #(= "Claim" (:entity %)) m/entity-specs))))

(deftest claim-domain-validation
  (testing "a fully valid claim is accepted"
    (let [s (m/fresh-store) [rec status] (m/handle-create s "Claim" claim-sample)]
      (is (= 201 status))
      (is (str/starts-with? (:id rec) "hl7fhir_cla_"))))
  (testing "an invalid billing provider NPI (bad Luhn check digit) is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "Claim" (assoc claim-sample :billingProviderNpi "1234567890"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "billingProviderNpi"))))
  (testing "an NPI that isn't 10 digits is rejected"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "Claim" (assoc claim-sample :billingProviderNpi "123"))]
      (is (= 400 status))))
  (testing "a malformed ICD-10-CM diagnosis code is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "Claim" (assoc claim-sample :diagnosisCode "9999"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "diagnosisCode"))))
  (testing "a well-formed ICD-10-CM code with an alpha third character is accepted"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "Claim" (assoc claim-sample :diagnosisCode "C7A.00"))]
      (is (= 201 status))))
  (testing "a malformed procedure code is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "Claim" (assoc claim-sample :procedureCode "ABCDE"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "procedureCode"))))
  (testing "a HCPCS Level II procedure code is accepted"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "Claim" (assoc claim-sample :procedureCode "J1200"))]
      (is (= 201 status))))
  (testing "update also enforces format validation on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "Claim" claim-sample)
          [body status] (m/handle-update s "Claim" (:id rec) {:billingProviderNpi "0000000000"})]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "billingProviderNpi")))))

;; --- Consent: EU GDPR Art. 9(2) lawful-basis validation (ADR-2607083100) ---
;; Same rationale as claim-domain-validation above: the generic `validation`
;; deftest only covers missing-required / unknown-field rejection, which
;; every entity shares. Consent is the second entity (after Claim) whose
;; field carries a real domain-format constraint (one of the ten Art.
;; 9(2)(a)-(j) point-letters), so it gets its own deftest that a naive
;; string field would never reject.
(def ^:private consent-sample
  (:sample (first (filter #(= "Consent" (:entity %)) m/entity-specs))))

(deftest consent-domain-validation
  (testing "a fully valid consent record is accepted"
    (let [s (m/fresh-store) [rec status] (m/handle-create s "Consent" consent-sample)]
      (is (= 201 status))
      (is (str/starts-with? (:id rec) "hl7fhir_cst_"))
      (is (true? (:specialCategoryData rec)))))
  (testing "each of the ten Art. 9(2) point-letters is independently accepted"
    (doseq [code ["a" "b" "c" "d" "e" "f" "g" "h" "i" "j"]]
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "Consent" (assoc consent-sample :lawfulBasisArt9 code))]
        (is (= 201 status) code))))
  (testing "a lawful-basis code outside the ten-item set is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "Consent" (assoc consent-sample :lawfulBasisArt9 "z"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "lawfulBasisArt9"))))
  (testing "the full exception label instead of the point-letter is rejected"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "Consent" (assoc consent-sample :lawfulBasisArt9 "explicit consent"))]
      (is (= 400 status))))
  (testing "specialCategoryData coerces truthy wire values to boolean"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "Consent" (assoc consent-sample :specialCategoryData "true"))]
      (is (true? (:specialCategoryData rec)))))
  (testing "update also enforces format validation on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "Consent" consent-sample)
          [body status] (m/handle-update s "Consent" (:id rec) {:lawfulBasisArt9 "zz"})]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "lawfulBasisArt9")))))

;; --- PatientAccessRequest: EU EHDS Article 3 validation (ADR-2607083200) ---
;; Same rationale as claim-domain-validation / consent-domain-validation
;; above, plus a new dimension: PatientAccessRequest is the first entity
;; whose :validate-record is a *cross-field* rule (restrictionApplied=true
;; requires a non-blank restrictionReason), not a single-field format
;; check, so this deftest also exercises that the cross-field rule is
;; enforced on create and on update -- including when update only patches
;; one of the two fields and the merged (not the raw patch) record must be
;; checked.
(def ^:private access-request-sample
  (:sample (first (filter #(= "PatientAccessRequest" (:entity %)) m/entity-specs))))

(deftest patient-access-request-domain-validation
  (testing "a fully valid view request is accepted"
    (let [s (m/fresh-store) [rec status] (m/handle-create s "PatientAccessRequest" access-request-sample)]
      (is (= 201 status))
      (is (str/starts-with? (:id rec) "hl7fhir_par_"))
      (is (= "patient-summary" (:priorityCategory rec)))
      (is (false? (:restrictionApplied rec)))))
  (testing "a download request is accepted"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessMethod "download"))]
      (is (= 201 status))))
  (testing "accessMethod is case-insensitive"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessMethod "VIEW"))]
      (is (= 201 status))))
  (testing "an accessMethod outside view/download is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessMethod "print"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "accessMethod"))))
  (testing "restrictionApplied=true without restrictionReason is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest"
                                          (assoc access-request-sample :restrictionApplied true))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "restrictionReason"))))
  (testing "restrictionApplied=true with a non-blank restrictionReason is accepted"
    (let [s (m/fresh-store)
          [rec status] (m/handle-create s "PatientAccessRequest"
                                         (assoc access-request-sample
                                                :restrictionApplied true
                                                :restrictionReason "Art. 23 GDPR patient-safety delay"))]
      (is (= 201 status))
      (is (true? (:restrictionApplied rec)))))
  (testing "priorityCategory is case-insensitive"
    (let [s (m/fresh-store)
          [rec status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :priorityCategory "DISCHARGE-REPORT"))]
      (is (= 201 status))
      (is (= "DISCHARGE-REPORT" (:priorityCategory rec)))))
  (testing "a priorityCategory outside the Article 14(1) six-value enum is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :priorityCategory "vital-signs"))]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "priorityCategory"))))
  (testing "update enforces the priorityCategory format check on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:priorityCategory "vital-signs"})]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "priorityCategory"))))
  (testing "update enforces the accessMethod format check on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:accessMethod "print"})]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "accessMethod"))))
  (testing "update enforces the restriction cross-field rule against the merged record"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          ;; patch only restrictionApplied -- restrictionReason isn't in this
          ;; patch, but the *merged* record (existing reason-less record +
          ;; this patch) must still be checked, not just the raw patch.
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:restrictionApplied true})]
      (is (= 400 status))
      (is (str/includes? (get-in body [:error :message]) "restrictionReason"))))
  (testing "update accepts restrictionApplied=true when the patch also supplies a reason"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [updated status] (m/handle-update s "PatientAccessRequest" (:id rec)
                                             {:restrictionApplied true
                                              :restrictionReason "Art. 23 GDPR patient-safety delay"})]
      (is (= 200 status))
      (is (true? (:restrictionApplied updated))))))
