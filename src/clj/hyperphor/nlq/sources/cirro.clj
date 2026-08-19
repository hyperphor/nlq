(ns hyperphor.nlq.sources.cirro
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.web :as uw]
            [hyperphor.multitool.cljcore :as ju]
            [taoensso.timbre :as log]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as aws-creds]
            [hyperphor.nlq.sources.sql :as sql]
            [hyperphor.nlq.config :as nlqc]
            ))

;;; https://docs.cirro.bio/cli-sdk/api-usage/
;;; Swagger: https://app.cirro.bio/openapi/views/swagger-ui/index.html

;;; See https://openapi-generator.tech/ but I think I'll only be using
;;; a few API calls so not worth it.

;;; Cirro sheet metadata is in dynamodb, actual data is in Iceberg. SQL dialect doc:
;;; https://trino.io/docs/current/sql/select.html

;;; Auth options (checked in priority order):
;;;   1. Client credentials (machine auth): set CIRRO_CLIENT_ID + CIRRO_CLIENT_SECRET
;;;   2. User/password: set CIRRO_USERNAME + CIRRO_PASSWORD (Cognito USER_PASSWORD_AUTH)
;;;   3. Browser device-code flow: call (authenticate! db) interactively

;;; → Way or Multitool
(defn- coerce-json
  [body]
  (if (string? body)
    (try (json/read-str body :key-fn keyword)
         (catch Exception _ {:message body}))
    body))

(defn- check-response
  "Throws a clear ex-info if the response is an error, otherwise returns the parsed body."
  [{:keys [status body] :as resp} url]
  (let [body (coerce-json body)
        {:keys [errorCode errorDetail]} body]
    (if (< status 400)
      body
      (let [msg (u/tx "Error {{errorCode}} {{errorDetail}}" )]
        (throw (ex-info (str "Cirro API error: " msg)
                        {:url url :status status :body body}))))))

(declare get-access-token)

(defn api-get
  [{:keys [host] :as db} url & [params]]
  (let [full-url (str "https://" host url)]
    (check-response
     (client/get full-url
                 {:query-params     params
                  :headers          (if (:no-auth? params)
                                      {}
                                      {"Authorization" (str "Bearer " (get-access-token db))})
                  :as               :json
                  :throw-exceptions false})
     full-url)))

(defn api-post-unchecked
  [{:keys [host] :as db} url params]
  (let [full-url (str "https://" host url)]
    (client/post full-url
                 {:body             (json/write-str params)
                  :content-type     :application/json
                  :headers          {"Authorization" (str "Bearer " (get-access-token db))}
                  :as               :json ;Doesn't seem to work so we coerce in check-response
                  :throw-exceptions false})))


(defn api-post
  [{:keys [host] :as db} url params]
  (let [full-url (str "https://" host url)]
    (check-response
     (api-post-unchecked db url params)
     full-url)))

;;; Cognito app client id, auth domain, and region are all tenant-specific —
;;; fetch them together from /api/info/system
(u/defn-memoized cirro-system-info
  [{:keys [host]}]
  (api-get {:host host} "/api/info/system" {:no-auth? true}))

;;; Note: these is :auth from cirro system info, distinct from okc config! Confusing.
(defn- cirro-auth-info [db] (:auth (cirro-system-info db)))
(defn cognito-client-id [db] (:sdkAppId (cirro-auth-info db)))
(defn- cognito-auth-endpoint [db] (str "https://" (:endpoint (cirro-auth-info db))))
(defn- cognito-region [db] (or (:region (cirro-system-info db)) "us-west-2"))

;;; SDK uses /api/auth as the device-code/token endpoint prefix
(defn- sdk-auth-endpoint [{:keys [host]}] (u/tx "https://{{host}}/api/auth"))

;;; Token cache, per Cirro host — a token minted for one tenant's Cognito pool
;;; is meaningless (or wrong) against another.
(def ^:private token-cache (atom {}))

(defn- cognito-initiate-auth [db auth-flow auth-params]
  (-> (client/post (str "https://cognito-idp." (cognito-region db) ".amazonaws.com/")
                   {:headers {"Content-Type" "application/x-amz-json-1.1"
                              "X-Amz-Target" "AWSCognitoIdentityProviderService.InitiateAuth"}
                    :body (json/write-str {"AuthFlow" auth-flow
                                          "ClientId" (cognito-client-id db)
                                          "AuthParameters" auth-params})
                    :as :json})
      (get-in [:body :AuthenticationResult])))

(defn- client-credentials-token [db]
  (let [client-id     (get-in db [:auth :cirro-client-id])
        client-secret (get-in db [:auth :cirro-client-secret])
        
        basic         (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                     (.getBytes (str client-id ":" client-secret) "UTF-8")))
        result        (-> (client/post (str (cognito-auth-endpoint db) "/oauth2/token")
                                       {:headers {"Authorization" basic
                                                  "Content-Type"  "application/x-www-form-urlencoded"}
                                        :body   "grant_type=client_credentials"
                                        :as      :json})
                          :body)]
    {:access-token  (:access_token result)
     :refresh-token nil
     :expires-at    (+ (System/currentTimeMillis)
                       (* (- (:expires_in result 3600) 60) 1000))}))

(defn- user-password-token [db cache]
  (let [username (get-in db [:auth :cirro-username])
        password (get-in db [:auth :cirro-password])
        result   (if (and cache (:refresh-token cache))
                   (try (cognito-initiate-auth db
                                               "REFRESH_TOKEN_AUTH"
                                               {"REFRESH_TOKEN" (:refresh-token cache)})
                        (catch Exception e
                          (log/warn "Cirro token refresh failed, re-authenticating:" (ex-message e))
                          (cognito-initiate-auth db "USER_PASSWORD_AUTH"
                                                 {"USERNAME" username "PASSWORD" password})))
                   (cognito-initiate-auth db "USER_PASSWORD_AUTH"
                                          {"USERNAME" username "PASSWORD" password}))]
    {:access-token  (:AccessToken result)
     :refresh-token (or (:RefreshToken result) (:refresh-token cache))
     :expires-at    (+ (System/currentTimeMillis)
                       (* (- (:ExpiresIn result) 60) 1000))}))

;;; → ju
(defn maybe-open-browser
  [text]
  (when-let [url (first (uw/find-urls text))]
    (ju/open-url url)))

(defn- device-code-flow [db]
  (let [flow (-> (client/post (str (sdk-auth-endpoint db) "/device-code")
                              {:query-params {:client_id (cognito-client-id db)}})
                 :body
                 (json/read-str :key-fn keyword))
        _ (println (:message flow))
        _ (maybe-open-browser (:message flow))

        expiry (java.time.Instant/parse (:expiry flow))]
    (loop []
      (Thread/sleep (* (:interval flow 5) 1000))
      (when (.isBefore expiry (java.time.Instant/now))
        (throw (ex-info "Cirro device-code authentication timed out" {})))
      (let [resp   (client/post (str (sdk-auth-endpoint db) "/token")
                                {:query-params     {:client_id   (cognito-client-id db)
                                                    :device_code (:device_code flow)
                                                    :grant_type  "urn:ietf:params:oauth:grant-type:device_code"}
                                 ;; :as :json
                                 :throw-exceptions false})
            result (json/read-str (:body resp) :key-fn keyword)
            _  (prn :get-token-result result)
            status (:message result)]
        (cond
          (:access_token result)
          {:access-token  (:access_token result)
           :refresh-token (:refresh_token result)
           :expires-at    (+ (System/currentTimeMillis)
                             (* (- (:expires_in result 3600) 60) 1000))}

          (= status "authorization_pending")
          (recur)

          :else
          (throw (ex-info (str "Cirro authentication failed: " status) {:response result})))))))

(defn authenticate!
  "Interactively authenticate via browser against db's Cirro tenant. Prints a
   URL to visit, then polls until the user completes login. Stores the token
   for subsequent API calls to that tenant."
  [db]
  (device-code-flow db))

(defn get-access-token
  "Returns a valid Cirro access token for db's tenant. Checks in order:
   client credentials (CIRRO_CLIENT_ID + CIRRO_CLIENT_SECRET),
   user/password (CIRRO_USERNAME + CIRRO_PASSWORD),
   or a token previously obtained via (authenticate! db)."
  [{:keys [host auth] :as db}]
  (let [cache (get @token-cache host)]
    (when (or (nil? cache)
              (>= (System/currentTimeMillis) (:expires-at cache)))
      (swap! token-cache assoc host
             (case (:type auth)
               :app  (client-credentials-token db) ;TODO fall back to desktop if creds not present
               :user  (user-password-token db cache)
               :desktop (if (java.awt.Desktop/isDesktopSupported)
                          (authenticate! db)
                          (throw (ex-info "desktop login nnot supported" auth))
                         ) 
               :else (throw (ex-info "Authenticate failed" {})))))
    (:access-token (get @token-cache host))))

;;; /sheets/raw-query is paginated whether or not the caller asks for it
;;; (limit 1-10000, default 1000; page, default 1 — see SheetQueryRequest in
;;; resources/cirro/cirro-data-latest.yml) and its response carries the true
;;; :totalRowCount of the full result set. The old version of this method
;;; ignored both, so every query silently got page 1 of (at most) 1000 rows —
;;; eg a 4000-row PRINCE sample table came back as 1000 with no indication
;;; anything was cut off. Page through it instead.
(def ^:private raw-query-page-size
  "Cirro's own max for SheetQueryRequest's :limit."
  10000)

(def ^:private raw-query-max-rows
  "Safety ceiling on total rows paged in for one query, so a pathological
   query can't page forever / blow up memory. Loud (a log warning) rather
   than the silent 1000-row cap this replaces."
  100000)

(defn- raw-query-page
  [{:keys [project] :as db} sql page]
  (api-post db (u/tx "/api/projects/{{project}}/sheets/raw-query")
            {:query sql
             :namespaceName "default"
             :limit raw-query-page-size
             :page page}))

(defmethod sql/query :cirro
  [db sql]
  (loop [page 1, acc []]
    (let [{:keys [rows columns totalRowCount]} (raw-query-page db sql page)
          columns (map (comp keyword :name) columns)
          acc     (into acc (map #(zipmap columns %) rows))]
      (cond
        (empty? rows)
        acc

        (>= (count acc) (or totalRowCount 0))
        acc

        (>= (count acc) raw-query-max-rows)
        (do (log/warn "Cirro query truncated at" raw-query-max-rows "rows (totalRowCount was" totalRowCount ")" {:sql sql})
            acc)

        :else
        (recur (inc page) acc)))))

(defn project-sheets
  [{:keys [project subproject] :as db}]
  (->> (api-get db (u/tx "/api/projects/{{project}}/sheets") {})
       (filter #(or (nil? subproject)
                    (str/includes? (:name %) subproject)))
       (filter #(pos? (:totalRowCount %)))))

;;; Project management

;;; Just plain projects gets different results, but these are the actual ones we are using
(defn list-projects
  [db]
  (api-get db (u/tx "/api/projects/discover")))

#_ (api-get db "/api/projects/867dcd5f-74ff-4067-aabf-52018e9e6fdb")

(defn get-project
  [db id]
  (api-get db (u/tx "/api/projects/{{id}}")))



(defn create-project
  [db name desc]
  (let [p0 (get-project db (-> (list-projects db)
                               (u/select-by :name "PRINCE")
                               :id))]
    (api-post db  "/api/projects/"
              (-> p0
                (assoc :name name)
                (assoc :description desc)))))

;; AFAICT projects can't be deleted (by me at least) so don't create too many bogus ones. Also they consume AWS resources

#_ (create-project "my-project" "Comprehensive pan-cancer standard-of-care analysis of 1,070 patients")

#_ (api-get db "/api/projects/options")

;;; ── Datasets — read-only, for discovering the real S3 bucket/prefix
;;; convention (see sheet-upload-location's guess and design/cirro-file-upload.md's
;;; live AccessDenied: the vended SHEET_UPLOAD token's session policy didn't
;;; authorize our guessed key, and AccessDenied doesn't say whether the bucket
;;; part was even right — better to read it off a real object than guess
;;; again). Every dataset already in the tenant has files at a real
;;; s3://<bucket>/<prefix>/... path (DatasetAssetsManifest's :domain) —
;;; whatever that turns out to be is ground truth for this project's bucket
;;; naming, even though it's DATASET_UPLOAD's convention rather than
;;; SHEET_UPLOAD's specifically.

(defn list-datasets
  [{:keys [project] :as db}]
  (:data (api-get db (u/tx "/api/projects/{{project}}/datasets") {})))

(defn dataset-manifest
  "{:domain :files ...} for dataset-id — :domain is the real
   s3://<bucket>/<prefix> base every file path in :files is relative to."
  [{:keys [project] :as db} dataset-id]
  (api-get db (u/tx "/api/projects/{{project}}/datasets/{{dataset-id}}/files") {}))

(comment
  ;; Run this live to read off the real bucket/prefix convention before
  ;; guessing at sheet-upload-location again.
  (def db (:db (nlqc/project-named "my-project")))
  (map (juxt :id :name) (list-datasets db))
  (:domain (dataset-manifest db (:id (first (list-datasets db))))))

;;; ── Table/column listing, for hyperphor.nlq.sources.sql ─────────────────
;;; DDL assembly is shared across backends and lives in sql.clj; this surfaces
;;; the actual table/column names and types as reported by the Cirro sheet itself.

(defn sheet-columns
  [{:keys [project] :as db} sheet-id]
  (let [{:keys [tableName columns]}
        (api-get db (u/tx "/api/projects/{{project}}/sheets/{{sheet-id}}") {})]
    {:table-name tableName
     :columns    (map (fn [{:keys [name dataType]}] {:name name :type dataType}) columns)}))

(defmethod sql/project-tables :cirro
  [{:keys [subproject] :as db}]
  (map (partial sheet-columns db)
       (map :id (project-sheets db))))

;;; ── Sheet upload ──────────────────────────────────────────────────────────
;;; Programmatic equivalent of design/umbra.md's manual "upload file to Cirro"
;;; step — create a sheet, then push rows into it, no CSV/UI round-trip.
;;; Worked out from the Swagger doc at
;;; https://app.cirro.bio/openapi/cirro-data-latest.yml (the swagger-ui page
;;; itself is a JS SPA shell with no useful static content; that .yml is
;;; what it actually loads) — the earlier create-sheet was calling api-post
;;; with the db argument missing entirely, using :id instead of the required
;;; :name on each column, and never using its own sheet-name/columns args.

(defn- sheet-name->table-name
  "A default Cirro tableName: lowercased, non-identifier runs collapsed to
   underscores. Cirro requires tableName/namespaceName to match
   ^[a-zA-Z_][a-zA-Z0-9_]{0,127}$ — real display names (eg \"pici0002 KRAS
   measurement set\") don't."
  [s]
  (-> s str/lower-case (str/replace #"[^a-zA-Z0-9_]+" "_")))

(defn create-sheet
  "Create a new TABLE sheet in db's Cirro project, ready for rows via
   insert-sheet-rows. `columns` is a seq of {:name :type}, :type one of
   Cirro's ColumnDataType values (:string :integer :bigint :float :double
   :boolean :date :timestamp, case-insensitive) — the same {:name :type}
   shape sql/project-tables already returns for existing sheets. `table-name`
   defaults to a slugged `sheet-name` (see sheet-name->table-name).
   sheetCreationMode is SCRATCH, since rows arrive over the API rather than
   a file upload — worth noting every real sheet in this tenant so far
   (checked live via project-sheets) was created FILE-mode instead, so this
   is less-trodden ground on Cirro's side.
   Returns {:id :message} (the new sheet's id, per CreateResponse)."
  [{:keys [project] :as db} sheet-name columns & [table-name description]]
  (api-post db (u/tx "/api/projects/{{project}}/sheets")
            {:sheetType "TABLE"
             :name sheet-name
             :description description
             :namespaceName "default" ;; matches every real sheet in this tenant, per project-sheets
             :tableName (or table-name (sheet-name->table-name sheet-name))
             :sheetCreationMode "SCRATCH"
             :columns (mapv (fn [{:keys [name type]}]
                               {:name name :dataType (str/upper-case (clojure.core/name type))})
                             columns)}))

(defn list-sheet-jobs
  "The SheetJob history for `sheet-id` — includes status/failedAtStep/
   errorMessage for ingest jobs, which insert-sheet-rows' own response
   doesn't surface (it just gets a bare 500 InternalServerException back).
   Diagnostic for insert failures: read-only, no new sheets/rows created."
  [{:keys [project] :as db} sheet-id]
  (api-get db (u/tx "/api/projects/{{project}}/sheets/{{sheet-id}}/jobs") {}))

(defn- insert-batch
  "POST one batch, retrying on failure — checked live against Cirro's dev
   tenant: the exact same batch (same sheet, same rows) fails an
   InternalServerException 500 on one attempt and succeeds outright on the
   next, with no correlation found to batch size (1 row through 1000),
   content, or whether the sheet was brand-new or already had thousands of
   rows in it — bisected all three dimensions live and ruled each out.
   Genuine intermittent server-side flakiness. The bad window can last
   minutes (checked live: 3 immediate retries at 2s spacing all failed on
   one batch, then the identical batch succeeded unprompted minutes later),
   so this retry only reduces the odds of hitting it, it doesn't eliminate
   them — a 170k-row/171-batch upload still failed after all 3 retries on
   one batch in testing. Not worth chasing further: nothing in this export
   actually needs more than ~24 batches (see cirro_import.clj), well under
   where every observed failure has landed (54-61 batches in)."
  [{:keys [project] :as db} sheet-id batch]
  (loop [attempt 1]
    (let [result (try
                   {:ok (:rowsAffected
                         (api-post db (u/tx "/api/projects/{{project}}/sheets/{{sheet-id}}/data")
                                   {:inserts (mapv (fn [row] {:values row}) batch)}))}
                   (catch Exception e
                     (if (< attempt 3)
                       {:retry true}
                       {:error e})))]
      (cond
        (:ok result)    (:ok result)
        (:retry result) (do (Thread/sleep (* attempt 3000))
                            (recur (inc attempt)))
        :else           (throw (:error result))))))

(defn insert-sheet-rows
  "Insert `rows` (a seq of maps, column name -> value) into `sheet-id` in
   db's project. Batches into groups of 1000 — Cirro's insertSheetData caps
   at 1000 inserts per call — retrying each batch up to 3 times (see
   insert-batch) before giving up, and returns the total rowsAffected.
   If a batch still fails after retries, rethrows with :rows-inserted added
   to ex-data so the caller knows how much already landed and can resume
   from there, instead of a bare exception that discards that count and
   forces re-querying the sheet's totalRowCount."
  [{:keys [project] :as db} sheet-id rows]
  (loop [batches (partition-all 1000 rows), inserted 0]
    (if (empty? batches)
      inserted
      (let [batch (first batches)
            n (try
                (insert-batch db sheet-id batch)
                (catch Exception e
                  (throw (ex-info (ex-message e)
                                  (assoc (ex-data e) :rows-inserted inserted)))))]
        (recur (rest batches) (+ inserted n))))))

;;; ── File-based sheet ingest (bulk alternative to insert-sheet-rows) ────────
;;; For sheets too large for the 1000-row batch-insert loop above to survive —
;;; insert-batch's own docstring notes Cirro's intermittent batch-insert
;;; failures have always landed at 54-61 batches in on live testing, and a
;;; sheet needing thousands of batches (eg a 9.5M-row CANDEL measurement-set
;;; export) is close to guaranteed to hit that repeatedly with no resume path.
;;; Instead: PUT the file straight to S3 with short-lived per-sheet
;;; credentials, then have Cirro bulk-ingest it server-side in one job. Worked
;;; out from resources/cirro/cirro-data-latest.yml (s3-token/ingest endpoints)
;;; plus CirroBio/Cirro-SDK-Python's real upload code (which doesn't cover
;;; sheets specifically, but uses the identical s3-token dance for datasets/
;;; references) — see design/cirro-file-upload.md for the full trace.
;;;
;;; UNVERIFIED against the live API (written with no Cirro credentials
;;; available) — specifically sheet-upload-location's bucket/key guess, and
;;; whether a second ingest into an already-ingested sheet appends or expects
;;; an empty sheet (ingest-sheet-file sidesteps the latter by always ingesting
;;; one file per sheet — see cirro_import.clj). Try against a throwaway
;;; scratch sheet before trusting this on real data; a wrong bucket/key just
;;; 403s against the vended token's own S3 policy rather than doing anything
;;; unsafe.

(defn s3-token
  "Short-lived AWS credentials ({:accessKeyId :secretAccessKey :sessionToken
   :expiration}) scoped to uploading a file destined for sheet-id, via the
   SHEET_UPLOAD access type (ProjectFileAccessRequest/ProjectAccessType in
   the Cirro OpenAPI spec)."
  [{:keys [project] :as db} sheet-id]
  (api-post db (u/tx "/api/projects/{{project}}/s3-token")
            {:accessType "SHEET_UPLOAD"
             :sheetId sheet-id}))

(defn- sheet-upload-location
  "{:bucket :key} to PUT a sheet-upload file at. The bucket half is confirmed
   live (checked against a real dataset-manifest :domain in the PRINCE-PICI
   tenant: project-<projectId>, exact match). The /data/ segment in the key
   is still inferred rather than confirmed — by analogy with the Python SDK's
   FileAccessContext/upload_dataset, which explicitly writes datasets under
   <domain>/data/... (distinct from the bare domain
   s3://project-<id>/datasets/<id> that read/manifest contexts use) — a first
   guess without it (bare sheets/<sheetId>/<filename>) got a live AccessDenied
   despite the bucket being right, consistent with the write scope actually
   being sheets/<sheetId>/data/*. See design/cirro-file-upload.md."
  [{:keys [project]} sheet-id filename]
  {:bucket (str "project-" project)
   :key    (u/tx "sheets/{{sheet-id}}/data/{{filename}}")})

(defn- s3-client
  "A fresh aws-api S3 client for one sheet-upload token — s3-token's creds
   are short-lived (see its :expiration), so unlike get-access-token's cached
   Cirro bearer token, this isn't meant to be reused across calls.
   aws-creds/basic-credentials-provider won't do here: checked live (and
   against aws-api 0.8.838's own source), it only ever forwards
   :access-key-id/:secret-access-key to the signer, silently dropping
   :session-token even when given one. That's fine for permanent IAM keys,
   but s3-token vends short-lived STS creds (ASIA-prefixed access keys) which
   AWS rejects outright as an unrecognized access key id — not a permissions
   error, InvalidAccessKeyId — on every signed request missing their paired
   session token. Reifying CredentialsProvider directly instead, per its own
   docstring listing :aws/session-token as an explicit (optional) fetch key."
  [{:keys [accessKeyId secretAccessKey sessionToken]} region]
  (aws/client {:api :s3
               :region region
               :credentials-provider
               (reify aws-creds/CredentialsProvider
                 (fetch [_]
                   {:aws/access-key-id     accessKeyId
                    :aws/secret-access-key secretAccessKey
                    :aws/session-token     sessionToken}))}))

(defn- put-s3-file!
  "PUTs local `file` to bucket/key via aws-api client s3. Single PUT, no
   multipart — fine up to S3's 5GB limit, comfortably above anything this
   pipeline produces (largest measurement-set format so far is ~190MB, see
   design/cirro-file-upload.md). Throws on any :cognitect.anomalies/category
   response (aws-api's own error signal)."
  [s3 bucket key file]
  (let [result (aws/invoke s3 {:op :PutObject
                               :request {:Bucket bucket
                                         :Key    key
                                         :Body   (io/input-stream file)}})]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info (str "S3 PutObject failed: " (:cognitect.anomalies/message result "unknown error"))
                      {:bucket bucket :key key :result result})))
    result))

(defn trigger-ingest
  "Kicks off Cirro's async bulk ingest of an already-uploaded S3 file into
   sheet-id. file-type is one of \"CSV\" \"PARQUET\" \"JSON\" \"XLSX\" — file
   column headers are matched against the sheet's own column names (see
   SheetIngestRequest's :sourceColumns for an explicit mapping, not used
   here since sheet-payload's cirro-identifier sanitization already produces
   matching names). Returns immediately (202) — see ingest-jobs/await-ingest."
  [{:keys [project] :as db} sheet-id file-type storage-uri]
  (api-post db (u/tx "/api/projects/{{project}}/sheets/{{sheet-id}}/ingest")
            {:fileDef {:fileType file-type :storageUri storage-uri}}))

(defn ingest-jobs
  "sheet-id's INGEST jobs (as opposed to CREATE_TABLE/DROP_TABLE/etc — see
   list-sheet-jobs), most recently created first."
  [db sheet-id]
  (->> (list-sheet-jobs db sheet-id)
       (filter #(= (:jobType %) "INGEST"))
       (sort-by :createdAt)             ;ISO-8601 strings sort chronologically
       reverse))

(defn await-ingest
  "Blocks until sheet-id's most recent INGEST job leaves PENDING/RUNNING/
   STARTING, polling every poll-ms (default 5s) up to timeout-ms (default
   10min). Returns the finished SheetJob; throws if it lands on anything but
   COMPLETED, or if timeout-ms elapses first."
  [db sheet-id & {:keys [poll-ms timeout-ms] :or {poll-ms 5000 timeout-ms 600000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [job (first (ingest-jobs db sheet-id))]
        (cond
          (nil? job)
          (throw (ex-info "No ingest job found" {:sheet-id sheet-id}))

          (contains? #{"PENDING" "RUNNING" "STARTING"} (:status job))
          (if (> (System/currentTimeMillis) deadline)
            (throw (ex-info "Timed out waiting for ingest" {:job job}))
            (do (Thread/sleep poll-ms) (recur)))

          (= (:status job) "COMPLETED")
          job

          :else
          (throw (ex-info (str "Ingest failed: " (:status job)) {:job job})))))))

(defn ingest-sheet-file
  "Bulk-loads local CSV `file` into sheet-id: PUTs it to S3 with a freshly
   vended SHEET_UPLOAD token, triggers ingest, and blocks until it completes.
   The bulk alternative to insert-sheet-rows for sheets past a few thousand
   rows — see this section's header comment and design/cirro-file-upload.md.
   Returns the completed SheetJob."
  [{:keys [project] :as db} sheet-id file]
  (let [creds  (s3-token db sheet-id)
        ;; Cirro's own /api/info/system doesn't surface an S3 region
        ;; separately from Cognito's — reusing get-access-token's own
        ;; fallback (cognito-region) rather than inventing a second one.
        region (cognito-region db)
        {:keys [bucket key]} (sheet-upload-location db sheet-id (.getName (io/file file)))
        s3     (s3-client creds region)]
    (put-s3-file! s3 bucket key file)
    (trigger-ingest db sheet-id "CSV" (str "s3://" bucket "/" key))
    (await-ingest db sheet-id)))

(comment
  (create-sheet (:db (nlqc/project-named "my-project"))
                "weights"
                [{:name :person_name :type :string}
                 {:name :person_weight :type :float} ] )

  (insert-sheet-rows (:db (nlqc/project-named "my-project"))
                     (:id *1)
                     [{:person_name "fred" :person_weight 210}
                      {:person_name "bob" :person_weight 180.5}])

  ;; File-ingest path — try against a throwaway scratch sheet first (see
  ;; ns header comment on what's unverified).
  (ingest-sheet-file (:db (nlqc/project-named "my-project"))
                     (:id *1)
                     "/tmp/weights.csv"))



(defn api-delete
  [{:keys [host] :as db} url]
  (let [full-url (str "https://" host url)]
    (check-response
     (client/delete full-url
                    {:headers          {"Authorization" (str "Bearer " (get-access-token db))}
                     :as               :json
                     :throw-exceptions false})
     full-url)))

(defn delete-sheet
  [{:keys [project] :as db} sheet-id]
  (api-delete db (u/tx "/api/projects/{{project}}/sheets/{{sheet-id}}")))

;;; Danger!
(defn delete-all-sheets
  [project]
  (doseq [sheet (project-sheets project)]
    (delete-sheet project (:id sheet))))


;;; TODO Dashboards aren't yet enabled
(comment
  (api-get (:db (nlqc/project-named "my-project")) "/api/dashboards" {}))


(def ocra-db
    {:type :cirro
     :host "ocra.cirro.bio"
     :auth {:type :desktop}                 ;I'm not allowed OAuth presently
     :project "16da32ba-59a8-4700-aba4-be36e28dd5fe"
     })

(defn project-datasets
  [{:keys [project subproject] :as db}]
  (->> (api-get db (u/tx "/api/projects/{{project}}/datasets") {})
       ;; TODO look at :nextToken for paging
       :data))

;;; Has more including s3: location
(u/defn-memoized get-dataset
  [{:keys [project subproject] :as db} dataset]
  (->> (api-get db (u/tx "/api/projects/{{project}}/datasets/{{dataset}}") {})
       ))

(defn get-dataset-files
  [{:keys [project subproject] :as db} dataset]
  (->> (api-get db (u/tx "/api/projects/{{project}}/datasets/{{dataset}}/files") {})
       ))

;;; Having to guess at access type. See
;;; https://github.com/CirroBio/Cirro-components/blob/nlq-demo/node_modules/%40cirrobio/api-client/dist/models/ProjectAccessType.js#L22

(defn s3-token-ds
  "Short-lived AWS credentials ({:accessKeyId :secretAccessKey :sessionToken
   :expiration}) scoped to reading dataset-id's files, via the
   PROJECT_DOWNLOAD access type (ProjectFileAccessRequest/ProjectAccessType
   in the Cirro OpenAPI spec -- unverified against the live API, it's the
   current best guess after DATASET_UPLOAD and an sftp token both failed,
   see comments above; if this 403s, that's the first thing to re-check)."
  [{:keys [project] :as db} dataset-id]
  (api-post db (u/tx "/api/projects/{{project}}/s3-token")
                  {:accessType "PROJECT_DOWNLOAD"
                   :datasetId dataset-id}))

;;; Cirro's /api/info/system doesn't surface an S3 region separately from
;;; Cognito's -- same fallback cirro.clj's own ingest-sheet-file uses
;;; (cognito-region there, private to that ns, so re-derived here rather
;;; than exposed).
(defn s3-region
  [db]
  ;; At least with the project I am testing with it, files are not in the system region (us-east-1) but in us-west-2
  (or #_ (:region (cirro-system-info db))
      "us-west-2"))

;;; Same shape as cirro.clj's private s3-client -- reified rather than
;;; aws-creds/basic-credentials-provider because that silently drops
;;; :session-token, and s3-token-ds vends short-lived STS creds (ASIA-
;;; prefixed access keys) that AWS rejects outright without one.
(defn- s3-client
  [{:keys [accessKeyId secretAccessKey sessionToken]} region]
  (aws/client {:api :s3
               :region region
               :credentials-provider
               (reify aws-creds/CredentialsProvider
                 (fetch [_]
                   {:aws/access-key-id     accessKeyId
                    :aws/secret-access-key secretAccessKey
                    :aws/session-token     sessionToken}))}))

(defn- domain->bucket+prefix
  "\"s3://bucket/some/prefix\" -> [\"bucket\" \"some/prefix\"]. get-dataset-files'
   :domain is the s3:// base every :files entry's :path is relative to."
  [domain]
  (let [[bucket & prefix-parts] (-> domain (str/replace #"^s3://" "") (str/split #"/"))]
    [bucket (str/join "/" prefix-parts)]))

(defn download-file
  "Downloads dataset-id's file at `path` (one of get-dataset-files' :files
   entries' own :path, eg \"data/clinical_data.csv\") to local file `dest`.
   Fetches a fresh PROJECT_DOWNLOAD-scoped S3 token per call (s3-token-ds's
   creds are short-lived, not meant to be cached/reused across calls -- same
   as cirro.clj's upload-side s3-token). Returns dest."
  [db dataset-id path dest]
  (let [{:keys [domain]} (get-dataset-files db dataset-id)
        [bucket prefix]  (domain->bucket+prefix domain)
        key              (str prefix "/" path)
        creds            (s3-token-ds db dataset-id)
        s3               (s3-client creds (s3-region db))
        result           (aws/invoke s3 {:op :GetObject
                                          :request {:Bucket bucket :Key key}})]
    (if (:cognitect.anomalies/category result)
      (throw (ex-info (str "S3 GetObject failed: " (:cognitect.anomalies/message result "unknown error"))
                      {:bucket bucket :key key :result result}))
      (do (io/make-parents dest)
          (with-open [in ^java.io.InputStream (:Body result)]
            (io/copy in (io/file dest)))
          dest))))

(defn download-dataset
  "Downloads every file in dataset-id into local directory `dest-dir`,
   preserving each file's own relative :path underneath it. Returns the seq
   of local dest paths."
  [db dataset-id dest-dir]
  (let [{:keys [files]} (get-dataset-files db dataset-id)]
    (mapv (fn [{:keys [path]}]
            (download-file db dataset-id path (str dest-dir "/" path)))
          files)))

(comment
  (for [p (project-datasets ocra-db)] [(:id p) (:files (get-dataset-files ocra-db (:id p) ))])

  (download-file ocra-db "5e2264e3-2700-4a01-8e96-69d37b6546f4"
                 "data/patients.csv" "/tmp/ocra/patients.csv")

  (download-dataset ocra-db "5e2264e3-2700-4a01-8e96-69d37b6546f4" "/tmp/ocra"))



