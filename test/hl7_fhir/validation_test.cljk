(ns hl7-fhir.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [hl7-fhir.validation :as v]))

(deftest npi-format
  (testing "canonical structurally-valid test NPI is accepted"
    (is (true? (v/valid-npi? "1234567893"))))
  (testing "wrong check digit is rejected"
    (is (false? (v/valid-npi? "1234567890")))
    (is (false? (v/valid-npi? "1234567891"))))
  (testing "wrong length is rejected"
    (is (false? (v/valid-npi? "123456789")))
    (is (false? (v/valid-npi? "12345678930"))))
  (testing "non-numeric input is rejected"
    (is (false? (v/valid-npi? "123456789A")))
    (is (false? (v/valid-npi? "abcdefghij"))))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-npi? nil)))
    (is (false? (v/valid-npi? 1234567893)))))

(deftest icd10-cm-format
  (testing "valid codes are accepted"
    (doseq [code ["I10" "E11.9" "Z00.00" "S72.001A" "C7A.00" "U07.1"]]
      (is (true? (v/valid-icd10-cm? code)) code)))
  (testing "case-insensitive"
    (is (true? (v/valid-icd10-cm? "e11.9"))))
  (testing "malformed codes are rejected"
    (doseq [code ["9999" "E1.9" "E11..9" "E11.99999" "" "E1"]]
      (is (false? (v/valid-icd10-cm? code)) code)))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-icd10-cm? nil)))))

(deftest procedure-code-format
  (testing "CPT Category I in range is accepted"
    (is (true? (v/valid-procedure-code? "99213")))
    (is (true? (v/valid-procedure-code? "00100")))
    (is (true? (v/valid-procedure-code? "99499"))))
  (testing "CPT Category I out of range is rejected"
    (is (false? (v/valid-procedure-code? "00099")))
    (is (false? (v/valid-procedure-code? "99500"))))
  (testing "CPT Category II / III suffix codes are accepted"
    (is (true? (v/valid-procedure-code? "1234F")))
    (is (true? (v/valid-procedure-code? "1234T"))))
  (testing "HCPCS Level II is accepted"
    (is (true? (v/valid-procedure-code? "J1200")))
    (is (true? (v/valid-procedure-code? "A0428"))))
  (testing "malformed codes are rejected"
    (doseq [code ["ABCDE" "1234" "123456" "W1234" "12345X"]]
      (is (false? (v/valid-procedure-code? code)) code)))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-procedure-code? nil)))))

(deftest gdpr-art9-lawful-basis-format
  (testing "all ten Art. 9(2) point-letters are accepted"
    (doseq [code ["a" "b" "c" "d" "e" "f" "g" "h" "i" "j"]]
      (is (true? (v/valid-gdpr-art9-lawful-basis? code)) code)))
  (testing "case-insensitive"
    (is (true? (v/valid-gdpr-art9-lawful-basis? "H")))
    (is (true? (v/valid-gdpr-art9-lawful-basis? "A"))))
  (testing "codes outside the ten-item set are rejected"
    (doseq [code ["k" "z" "aa" "9(2)(a)" "art9a" "" "1"]]
      (is (false? (v/valid-gdpr-art9-lawful-basis? code)) code)))
  (testing "the full label instead of the point-letter is rejected"
    (is (false? (v/valid-gdpr-art9-lawful-basis? "explicit consent"))))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-gdpr-art9-lawful-basis? nil)))
    (is (false? (v/valid-gdpr-art9-lawful-basis? :a)))))

(deftest ehds-access-method-format
  (testing "the two Article 3 access methods are accepted"
    (is (true? (v/valid-ehds-access-method? "view")))
    (is (true? (v/valid-ehds-access-method? "download"))))
  (testing "case-insensitive"
    (is (true? (v/valid-ehds-access-method? "VIEW")))
    (is (true? (v/valid-ehds-access-method? "Download"))))
  (testing "any other value is rejected"
    (doseq [v ["print" "share" "" "view download" "viewer"]]
      (is (false? (v/valid-ehds-access-method? v)) v)))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-ehds-access-method? nil)))
    (is (false? (v/valid-ehds-access-method? :view)))))

(deftest ehds-priority-category-format
  (testing "all six Article 14(1) priority categories are accepted"
    (doseq [code ["patient-summary" "electronic-prescription" "electronic-dispensation"
                  "medical-imaging" "medical-test-results" "discharge-report"]]
      (is (true? (v/valid-ehds-priority-category? code)) code)))
  (testing "case-insensitive"
    (is (true? (v/valid-ehds-priority-category? "PATIENT-SUMMARY")))
    (is (true? (v/valid-ehds-priority-category? "Discharge-Report"))))
  (testing "any other value is rejected, including a Member-State-added national category"
    (doseq [v ["vital-signs" "immunization-record" "" "patientsummary" "patient_summary"]]
      (is (false? (v/valid-ehds-priority-category? v)) v)))
  (testing "non-string input is rejected, not thrown"
    (is (false? (v/valid-ehds-priority-category? nil)))
    (is (false? (v/valid-ehds-priority-category? true)))
    (is (false? (v/valid-ehds-priority-category? :patient-summary)))))

(deftest ehds-restriction-cross-field
  (testing "no restriction applied passes regardless of reason"
    (is (true? (v/valid-ehds-restriction? {:restrictionApplied false})))
    (is (true? (v/valid-ehds-restriction? {})))
    (is (true? (v/valid-ehds-restriction? {:restrictionApplied false :restrictionReason nil}))))
  (testing "restriction applied with a non-blank reason passes"
    (is (true? (v/valid-ehds-restriction?
                {:restrictionApplied true
                 :restrictionReason "Art. 23 GDPR patient-safety delay pending clinician review"}))))
  (testing "restriction applied without a reason fails"
    (is (false? (v/valid-ehds-restriction? {:restrictionApplied true})))
    (is (false? (v/valid-ehds-restriction? {:restrictionApplied true :restrictionReason nil})))
    (is (false? (v/valid-ehds-restriction? {:restrictionApplied true :restrictionReason ""})))
    (is (false? (v/valid-ehds-restriction? {:restrictionApplied true :restrictionReason "   "})))))
