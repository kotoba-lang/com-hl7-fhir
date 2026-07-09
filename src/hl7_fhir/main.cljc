(ns hl7-fhir.main
  "Kotodama WASM entrypoint for the hl7_fhir clean-room actor (L5) — Clojure port.

  L5 production surface: CRUD + pagination + filtering + relationship
  expansion + strict validation, over a Datomic-backed Kotoba schema.

  py→cljc port of src/main.py (ADR 260607 L5 cohort). Data-driven: every
  handler is a generic fold over `entity-specs`, so the actor's whole REST
  surface is derivable from the schema/manifest. No proprietary code or
  credentials; resource shapes only.

  State lives on the kotoba Datom log: `emit-facts` produces namespaced EAVT
  facts (`hl7_fhir.<Entity>/<field>`); `*store*` is the in-memory materialization
  used by the contract test and by the WASM runtime before a live engine binds.

  The `Claim`, `Consent` and `PatientAccessRequest` entities are the
  exceptions to \"resource shapes only\": their `:validate` (and, for
  PatientAccessRequest, `:validate-record`) keys wire real domain format
  checks into the generic create/update handlers -- `Claim` for US billing
  identifiers (NPI / ICD-10-CM / CPT-HCPCS), `Consent` for the EU GDPR
  Art. 9(2) special-category-data lawful-basis code, and
  `PatientAccessRequest` for the EU EHDS (Regulation (EU) 2025/327)
  Article 3 primary-use access-method enum plus its Article 3(3)
  restriction/reason cross-field rule (see `hl7-fhir.validation`) -- so a
  claim with a malformed billing NPI, a consent record citing a
  lawful-basis code that isn't one of the Regulation's ten, or an access
  request that flags a restriction without recording why, is rejected with
  400 instead of silently persisted."
  (:require [clojure.string :as str]
            [hl7-fhir.validation :as validation]))

(def ns-prefix "hl7_fhir")
(def tier "L5")
(def default-limit 20)
(def max-limit 100)

;; --- schema-derived entity specs (the single source the handlers fold over) ---
(def entity-specs
  [{:entity "Patient"   :plural "patients"   :id-prefix "hl7fhir_pat"
    :fields [:resourceType :gender :birthDate :active :deceasedBoolean] :required [:resourceType]
    :coerce {:active :bool :deceasedBoolean :bool} :refs {}}
   {:entity "Observation" :plural "observations" :id-prefix "hl7fhir_obs"
    :fields [:resourceType :status :effectiveDateTime :valueString :issued] :required [:resourceType]
    :coerce {} :refs {}}
   {:entity "Encounter" :plural "encounters" :id-prefix "hl7fhir_enc"
    :fields [:resourceType :status :serviceType :period] :required [:resourceType]
    :coerce {} :refs {}}
   {:entity "MedicationRequest" :plural "medicationrequests" :id-prefix "hl7fhir_med"
    :fields [:resourceType :status :intent :authoredOn] :required [:resourceType]
    :coerce {} :refs {}}
   {:entity "AllergyIntolerance" :plural "allergyintolerances" :id-prefix "hl7fhir_all"
    :fields [:resourceType :clinicalStatus :criticality :recordedDate] :required [:resourceType]
    :coerce {} :refs {}}
   {:entity "Condition" :plural "conditions" :id-prefix "hl7fhir_con"
    :fields [:resourceType :clinicalStatus :recordedDate] :required [:resourceType]
    :coerce {} :refs {}}
   ;; CMS-1500 / UB-04 / X12 837 professional-claim minimum field set. Unlike
   ;; the resource-shape-only entities above, this one enforces real US
   ;; billing-identifier formats via :validate (ADR-2607083000 EHR-compat
   ;; L5->L6 increment): billing provider NPI (Luhn check digit per 45 CFR
   ;; 162.410), primary diagnosis code (ICD-10-CM), procedure code
   ;; (CPT Category I/II/III or HCPCS Level II).
   {:entity "Claim" :plural "claims" :id-prefix "hl7fhir_cla"
    :fields [:resourceType :billingProviderNpi :subscriberId :diagnosisCode :procedureCode :status]
    :required [:resourceType :billingProviderNpi :subscriberId :diagnosisCode :procedureCode]
    :coerce {}
    :validate {:billingProviderNpi validation/valid-npi?
               :diagnosisCode validation/valid-icd10-cm?
               :procedureCode validation/valid-procedure-code?}
    :sample {:resourceType "Claim" :billingProviderNpi "1234567893"
             :subscriberId "SUB123456" :diagnosisCode "E11.9" :procedureCode "99213"}
    :refs {}}
   ;; EU GDPR Art. 9(2) special-category-data lawful-basis record (ADR-2607083100
   ;; EHR-compat EU increment). Every clinical resource above carries "data
   ;; concerning health" per GDPR Art. 9(1) (Regulation (EU) 2016/679), which
   ;; is prohibited unless one of the ten Art. 9(2)(a)-(j) exceptions applies.
   ;; This entity records, per patient, which exception is being relied on.
   ;; `lawfulBasisArt9` is always required (not conditional on
   ;; `specialCategoryData`): in this actor every underlying resource is
   ;; clinical/health data, so a Consent record's whole purpose is to
   ;; document the Art. 9(2) basis for it -- `specialCategoryData` is an
   ;; explicit machine-readable flag for downstream consumers (e.g. a
   ;; ROPA/DPIA tool), not a condition this actor branches on.
   {:entity "Consent" :plural "consents" :id-prefix "hl7fhir_cst"
    :fields [:resourceType :patientId :specialCategoryData :lawfulBasisArt9 :status]
    :required [:resourceType :patientId :specialCategoryData :lawfulBasisArt9]
    :coerce {:specialCategoryData :bool}
    :validate {:lawfulBasisArt9 validation/valid-gdpr-art9-lawful-basis?}
    :sample {:resourceType "Consent" :patientId "hl7fhir_pat_sample0000000000"
             :specialCategoryData true :lawfulBasisArt9 "h" :status "active"}
    :refs {}}
   ;; EU EHDS (Regulation (EU) 2025/327) Article 3 primary-use access
   ;; request (ADR-2607083200 EHDS Article 3 increment; priorityCategory
   ;; enumerated 2026-07-09 once Article 14 was retrieved). Article 3(1)-(3)
   ;; and Article 14(1) are the portions of EHDS with a verified
   ;; primary-source reading to hand -- see
   ;; orgs/kotoba-lang/emr-claims-primary-sources/eu-ehds/
   ;; ehds-article3-excerpt.md and ehds-article14-15-excerpt.md (verbatim
   ;; excerpts, retrieved via a real-browser EUR-Lex session, CELEX:32025R0327).
   ;; `priorityCategory` is now the closed 6-value enum Article 14(1)(a)-(f)
   ;; lists verbatim. Article 15 (the European electronic health record
   ;; exchange format) is cross-referenced by Article 3(2) but its technical
   ;; schema is NOT modeled here: Article 15 itself delegates the concrete
   ;; format to future European Commission implementing acts, so
   ;; `accessMethod` only cites Article 15 by name -- see README for the
   ;; scope note.
   {:entity "PatientAccessRequest" :plural "patientaccessrequests" :id-prefix "hl7fhir_par"
    :fields [:resourceType :patientId :priorityCategory :accessMethod
             :restrictionApplied :restrictionReason :status]
    :required [:resourceType :patientId :accessMethod]
    :coerce {:restrictionApplied :bool}
    :validate {:accessMethod validation/valid-ehds-access-method?
               :priorityCategory validation/valid-ehds-priority-category?}
    :validate-record {:pred validation/valid-ehds-restriction?
                       :message "restrictionApplied requires a non-blank restrictionReason (EHDS Art. 3(3))"}
    :sample {:resourceType "PatientAccessRequest" :patientId "hl7fhir_pat_sample0000000000"
             :priorityCategory "patient-summary" :accessMethod "view" :restrictionApplied false :status "active"}
    :refs {}}])

(def entities (mapv :entity entity-specs))

(def routes
  (vec (mapcat (fn [{:keys [plural entity]}]
                 [{:method "POST"   :path (str "/v1/" plural)        :op (str "create " entity) :entity entity}
                  {:method "GET"    :path (str "/v1/" plural)        :op (str "list " entity)   :entity entity}
                  {:method "GET"    :path (str "/v1/" plural "/{id}") :op (str "get " entity)    :entity entity}
                  {:method "PATCH"  :path (str "/v1/" plural "/{id}") :op (str "update " entity) :entity entity}
                  {:method "DELETE" :path (str "/v1/" plural "/{id}") :op (str "delete " entity) :entity entity}])
               entity-specs)))

;; --- platform primitives ---
(defn now []
  #?(:clj (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn- rand-hex16 []
  #?(:clj (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16)
     :cljs (subs (str/replace (str (random-uuid)) "-" "") 0 16)))

(defn new-id [prefix] (str prefix "_" (rand-hex16)))

;; --- coercion ---
(defn as-int [v]
  (cond (number? v) (long v)
        (string? v) (try #?(:clj (Long/parseLong (str/trim v)) :cljs (let [n (js/parseInt v 10)] (if (js/isNaN n) 0 n)))
                         (catch #?(:clj Exception :cljs :default) _ 0))
        :else 0))

(defn as-float [v]
  (cond (number? v) (double v)
        (string? v) (try #?(:clj (Double/parseDouble (str/trim v)) :cljs (let [n (js/parseFloat v)] (if (js/isNaN n) 0.0 n)))
                         (catch #?(:clj Exception :cljs :default) _ 0.0))
        :else 0.0))

(defn as-bool [v]
  (if (nil? v) false (contains? #{"1" "true" "yes" "on" true} (if (string? v) (str/lower-case v) v))))

(defn coerce-field [kind v]
  (case kind :int (as-int v) :float (as-float v) :bool (as-bool v) v))

;; --- in-memory store (materializes the Datom log; live engine binds in prod) ---
(defn fresh-store [] (atom {}))
(def ^:dynamic *store* (fresh-store))

(defn emit-facts
  "EAVT facts for one record: {\"hl7_fhir.<Entity>/<field>\" v ...}. The datomic
  binding transacts these; the in-memory store keeps the record by id."
  [entity rec]
  (into {} (map (fn [[k v]] [(str ns-prefix "." entity "/" (name k)) v]) rec)))

(defn persist! [store entity rec]
  (swap! store assoc-in [entity (:id rec)] rec)
  rec)

(defn query
  ([store entity] (vec (vals (get @store entity))))
  ([store entity id] (if-let [r (get-in @store [entity id])] [r] [])))

(defn retract! [store entity id] (swap! store update entity dissoc id) {:id id :deleted true})

;; --- validation ---
(defn require-fields [data fields]
  (let [missing (remove #(let [v (get data %)] (and (some? v) (not= v ""))) fields)]
    (when (seq missing)
      {:error {:message (str "Missing required fields: " (str/join ", " (map name missing)))
               :type "invalid_request_error"}})))

(defn reject-unknown [data allowed]
  (let [allowed-set (set allowed)
        extra (remove allowed-set (keys data))]
    (when (seq extra)
      {:error {:message (str "Unknown fields: " (str/join ", " (map name extra)))
               :type "invalid_request_error"}})))

(defn validate-fields
  "Run per-field format predicates from an entity-spec's :validate map
  (field -> pred-fn) against the fields present in data. Absent fields are
  skipped here -- presence of required fields is `require-fields`'s job;
  this only rejects a *present* value that fails its format check (e.g. a
  billingProviderNpi that isn't a Luhn-valid 10-digit NPI)."
  [data validators]
  (let [failed (remove (fn [[f pred]]
                          (let [v (get data f)]
                            (or (nil? v) (pred v))))
                        validators)]
    (when (seq failed)
      {:error {:message (str "Invalid field format: " (str/join ", " (map (comp name first) failed)))
               :type "invalid_request_error"}})))

(defn validate-record
  "Run a whole-record cross-field predicate from an entity-spec's
  :validate-record key ({:pred (fn [record]) :message str}), complementing
  validate-fields (which only ever sees one field's own value) for
  constraints that span more than one field -- e.g.
  PatientAccessRequest's restrictionApplied=true requiring a non-blank
  restrictionReason (EHDS Art. 3(3)). `record` must be the fully merged
  record (create: the coerced new record; update: the existing record with
  the incoming patch applied), not just the raw incoming patch, so the
  predicate can see fields the caller didn't touch in this call. No-op
  (returns nil) when the entity-spec has no :validate-record."
  [record spec]
  (when (and spec (not ((:pred spec) record)))
    {:error {:message (:message spec) :type "invalid_request_error"}}))

(defn reject-non-map
  "Guards handle-create/handle-update against a request body that isn't a
  JSON object at all (a bare string, number, boolean, or array) -- every
  downstream step assumes `data` is associative: reject-unknown's
  `(keys data)` throws ClassCastException on a string (chars aren't
  MapEntry) or IllegalArgumentException on a number (no ISeq), and
  handle-update's `reduce-kv` over a non-map `data` fails the same way.
  Found + fixed during a 2026-07-08 health-check pass modeled on the
  etzhayyim/iryo capability-gate date-parse fix: like a malformed date
  string there, a malformed request-body *shape* here broke the otherwise
  -uniform discipline that every malformed input degrades to a 400
  {:error ...} response rather than crashing uncaught. `nil` is exempted
  (passes through to require-fields's own \"missing required fields\"
  message, its pre-existing and never-crashing behavior) -- only a
  concretely wrong-shaped non-nil body is rejected here. Returns nil (pass)
  for maps and nil, an error map otherwise."
  [data]
  (when (and (some? data) (not (map? data)))
    {:error {:message "Request body must be a JSON object"
             :type "invalid_request_error"}}))

;; --- list helpers ---
(defn apply-filters [rows params fields]
  (reduce (fn [out f]
            (let [want (get params f)]
              (if (and (some? want) (not= want ""))
                (filterv #(= (str (get % f)) (str want)) out)
                out)))
          rows fields))

(defn paginate [rows params]
  (let [limit (min (max (or (let [l (as-int (get params :limit))] (when (pos? l) l)) default-limit) 1) max-limit)
        start (get params :starting_after)
        rows (if (some? start)
               (let [ids (mapv :id rows) idx (.indexOf ^java.util.List ids start)]
                 #?(:clj (if (>= idx 0) (vec (drop (inc idx) rows)) rows)
                    :cljs (let [i (.indexOf (to-array (mapv :id rows)) start)] (if (>= i 0) (vec (drop (inc i) rows)) rows))))
               rows)
        page (vec (take limit rows))]
    [page (> (count rows) limit)]))

(defn expand [store rec params refs]
  (let [want (set (str/split (or (get params :expand) "") #","))]
    (reduce (fn [r [field ent]]
              (if (and (contains? want (name field)) (get r field))
                (assoc r (keyword (str (name field) "_obj")) (first (query store ent (get r field))))
                r))
            rec refs)))

;; --- generic handlers (return [body status]) ---
(defn- spec-for [entity] (first (filter #(= (:entity %) entity) entity-specs)))
(defn- not-found [] [{:error {:message "Not found" :type "not_found"}} 404])

(defn handle-create [store entity data]
  (let [{:keys [fields required coerce id-prefix validate]
         record-spec :validate-record} (spec-for entity)]
    (or (some-> (reject-non-map data) (vector 400))
        (some-> (reject-unknown data fields) (vector 400))
        (some-> (require-fields data required) (vector 400))
        (some-> (validate-fields data validate) (vector 400))
        (let [base {:id (new-id id-prefix)}
              rec (reduce (fn [m f] (assoc m f (coerce-field (get coerce f) (get data f)))) base fields)]
          (or (some-> (validate-record rec record-spec) (vector 400))
              (let [rec (assoc rec :createdAt (now) :updatedAt (now))]
                (persist! store entity rec)
                [rec 201]))))))

(defn handle-list [store entity params]
  (let [{:keys [fields]} (spec-for entity)
        rows (apply-filters (query store entity) params fields)
        [page has-more] (paginate rows params)]
    [{:object "list" :data page :has_more has-more :count (count page) :total (count rows)} 200]))

(defn handle-get [store entity id params]
  (let [{:keys [refs]} (spec-for entity) rows (query store entity id)]
    (if (empty? rows) (not-found) [(expand store (first rows) params refs) 200])))

(defn handle-update [store entity id data]
  (let [{:keys [fields validate] record-spec :validate-record} (spec-for entity)
        rows (query store entity id)]
    (if (empty? rows)
      (not-found)
      (or (some-> (reject-non-map data) (vector 400))
          (some-> (reject-unknown data fields) (vector 400))
          (some-> (validate-fields data validate) (vector 400))
          (let [merged (reduce-kv (fn [m k v] (if (#{:id :createdAt} k) m (assoc m k v)))
                                  (first rows) data)]
            (or (some-> (validate-record merged record-spec) (vector 400))
                (let [rec (assoc merged :updatedAt (now))]
                  (persist! store entity rec)
                  [rec 200])))))))

(defn handle-delete [store entity id]
  (if (empty? (query store entity id)) (not-found) [(retract! store entity id) 200]))

(defn healthz [] [{:status "ok" :actor "hl7_fhir-compat" :tier tier :entities entities} 200])

;; --- WASM runtime registration (kotodama). The runtime host owns the live
;;     Datom log; handlers stay pure folds over a store, so this is G5-clean. ---
(defn start! [] :hl7_fhir-compat/ready)
