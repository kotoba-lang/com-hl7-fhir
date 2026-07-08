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
