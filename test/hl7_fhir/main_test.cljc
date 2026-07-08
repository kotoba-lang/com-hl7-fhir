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
