(ns marketplace.edge
  "The HOST half of a marketplace actor, written once.

  Every actor in this marketplace is the same shape: a synchronous
  advisor/governor/phase core over a `Store`, and a host that has to get
  documents in front of it and writes out of it. The core differs per
  actor. The host does not — and the first version of it was copied into
  `orderops.edge.worker` and would have been copied into five more.
  This repo has watched that go wrong before (`cacao.cljc` reached ~25
  repos with a \"keep in sync\" comment and then diverged anyway), so the
  host lives here and the actors call it.

  ## What a host is for

  `kotoba-lang/kotobase-client` is Promise-based; the actor is
  synchronous. Making every `Store` protocol in the fleet async to suit
  one transport would be the tail wagging the dog, so the host brackets
  one actor pass instead:

      prefetch (the documents the request NAMES)
        -> seed a recording db-api
          -> run the actor, synchronously, exactly as the tests run it
        -> flush every recorded write in ONE transact

  The actor cannot tell the difference between this and a local map,
  which is the point: the same code path the test suite exercises is the
  one that runs in production.

  ## What this namespace refuses to do

  It mints no CACAO — the client does that, and
  `kotoba-lang/org-chainagnostic-cacao` owns the primitives. It holds no
  key material beyond reading the seed the platform hands it. It does
  not adjudicate, price or decide anything; every judgement stays in the
  actor's own governor.

  Bracket access (`aget`) throughout, for `:advanced-optimization`
  safety."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kotobase.client :as kb]
            [marketplace.persist :as persist]))

(def default-endpoint "https://kotobase.net")

(def default-db
  "ONE ref for the whole marketplace.

  CLAUDE.md's kotobase rule is that a Datalog join reaches exactly one
  ref: `open` takes a single `:ref-name`, so anything split across refs
  can never be joined again. Everything here wants joining — an order
  against the credential that admitted its seller, a payout against the
  delivery that earned it, a return against the order it came from — so
  every marketplace actor writes into this one ref and none of them
  shards.

  The cost, stated rather than discovered: one ref means one writer
  lease per commit at the storage layer, so throughput is bounded by
  CAS contention rather than by capacity. The rule's own answer to that
  is a single writer that batches, not a shard."
  "marketplace")

;; ───────────────────────── responses ─────────────────────────

(defn json [body status]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"
                                   "cache-control" "no-store"}}))

(defn authorised?
  "Bearer check against a shared secret. Write routes only: reads of
  published marketplace data are open by design."
  [request env]
  (let [want (aget env "ACTOR_WRITE_TOKEN")
        got (some-> (.get (aget request "headers") "authorization")
                    (str/replace #"^Bearer " ""))]
    (and want (not (str/blank? (str got))) (= want got))))

;; ───────────────────────── identity ─────────────────────────

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        n (.-length bin)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(defn client-for
  "Build the kotobase client from the actor's Ed25519 seed.

  Returns nil when the seed is absent, and the host then fails closed
  rather than falling back to anything local — the same discipline
  `marketplace.persist/store` applies to its database API.

  All marketplace actors are handed the SAME seed, because `:apex`
  requires the graph scope to equal the issuer DID and they share one
  graph (see `default-db`). Per-actor attribution does not depend on the
  key: `persist/stream-ctx` scopes every ledger by actor name, so the
  audit trails stay separate inside the shared ref."
  [env]
  (when-let [seed (aget env "KOTOBASE_SECRET_KEY")]
    (kb/make-client {:endpoint (or (aget env "KOTOBASE_ENDPOINT") default-endpoint)
                     :secret-key (b64->bytes seed)
                     :operator-did (or (aget env "KOTOBASE_OPERATOR_DID")
                                       "did:web:kotobase.net")
                     :auth-profile :apex})))

;; ───────────────────────── ledger ordinals ─────────────────────────

(defn- pad [width n]
  (let [s (str n)]
    (str (subs "0000000000000000" 0 (max 0 (- width (count s)))) s)))

(defn ordinal-fn
  "A ledger ordinal a Worker may actually use.

  The obvious implementation — a counter starting at 1 — is WRONG here,
  and shipped wrong once. `persist/append-event!` derives the entity id
  from the ordinal, so two isolates that each count from 1 write the
  same id and the second append silently replaces the first. The
  append-only audit ledger is the whole basis of the actor pattern's
  traceability claim; one that drops facts under concurrency is worse
  than none, because it still looks complete.

  So the ordinal carries its own uniqueness:

      <15-digit ms> - <4-digit within-request counter> - <isolate nonce>

  Sorting it lexically gives clock order across writers and exact order
  within a request. Two isolates writing in the same millisecond
  interleave arbitrarily — that is the honest limit of a multi-writer
  host, and it is a limit on ORDER, not on retention: nothing is lost.

  A globally monotonic sequence needs a single writer. The right one
  here is a Durable Object used purely as a serializer (CLAUDE.md: a DO
  is globally unique and single-threaded, so \"exactly one writer\" comes
  for free rather than being implemented with leases and fencing). That
  is a deliberate later step, not an oversight."
  []
  (let [n (atom 0)
        nonce (.toString (js/Math.floor (* (js/Math.random) 0xffffff)) 16)]
    (fn [] (str (pad 15 (js/Date.now)) "-" (pad 4 (swap! n inc)) "-" nonce))))

;; ───────────────────────── reads ─────────────────────────

(defn- datoms->docs
  "Fold a `datomic.datoms` response into `{kind {id doc}}`.

  Entity ids are deterministic (`persist/doc-eid` = `mp.<kind>/<id>`),
  so the kind and the id come straight off `:e` and the document comes
  off the one `:mp.<kind>/doc` cell."
  [res]
  (reduce
   (fn [acc d]
     (let [e (str (aget d "e"))
           a (str (aget d "a"))
           [_ kind id] (re-matches #"^mp\.([^/]+)/(.+)$" e)]
       (if (and kind (= a (str ":mp." kind "/doc")))
         (assoc-in acc [kind id]
                   (persist/dec* (reader/read-string (str (aget d "v_edn")))))
         acc)))
   {}
   (array-seq (or (aget res "datoms") #js []))))

(defn load-graph
  "One `:eavt` scan, folded into every document in the shared ref.

  This is a whole-graph read and says so. The bounded alternatives —
  `datomic.q` with a pattern, or `datomic.fold` — do not work on the
  apex today: `q` answers `rows: []` and `fold` answers
  `MethodNotImplemented` (measured 2026-07-27 against kotobase.net).
  When one of them works, only this function changes; `select` below
  already narrows to what the request named, so nothing downstream
  assumes the whole graph was ever in hand."
  ([client] (load-graph client default-db))
  ([client db] (-> (kb/datoms client db ":eavt") (.then datoms->docs))))

(defn select
  "Narrow a loaded graph to the documents a request names.

  `wants` is `{kind ids}` where ids is a seq of ids or `:all`. Returns
  `[kind id doc]` triples — the id comes along because it is what
  `seed-tx` needs, and it is already known from the entity id rather
  than needing a per-kind lookup table in the host.

  Missing documents are simply absent. The actor's governor is what
  decides that an absent seller or offer is a refusal, and it must keep
  deciding that rather than having the host pre-empt it with an error."
  [docs wants]
  (vec
   (for [[kind ids] wants
         :let [by-id (get docs (name kind) {})]
         [id doc] (if (= :all ids)
                    by-id
                    (keep (fn [i] (when-let [d (get by-id (str i))] [(str i) d]))
                          (distinct ids)))]
     [kind id doc])))

(defn seed-tx
  "Turn selected documents into the tx-data `persist`'s recording api
  seeds from — the same triples it writes, so the round trip is
  symmetric by construction."
  [picked]
  (vec (mapcat (fn [[kind id doc]] (persist/doc->tx* kind id doc)) picked)))

;; ───────────────────────── writes ─────────────────────────

(defn flush-tx!
  "One transact of everything the request wrote.

  Nothing when it wrote nothing: an empty transact would still advance
  the head and make a read look like a write in the audit trail.
  Retries are OFF — a re-applied append with a fresh ordinal would
  duplicate a ledger entry, exactly the hazard the client's own
  `transact` docstring warns about."
  ([client tx-data] (flush-tx! client default-db tx-data))
  ([client db tx-data]
   (if (seq tx-data)
     (kb/transact client db (pr-str (vec tx-data)))
     (js/Promise.resolve nil))))

;; ───────────────────────── the bracket ─────────────────────────

(defn with-store
  "prefetch -> run -> flush, for any actor.

  `store-fn` receives `{:db-api .. :seq-fn ..}` and returns that actor's
  own durable store. `f` receives the store and runs the actor
  synchronously. Returns a Promise of `(f st)` plus `:written`, the
  number of triples that reached the ref."
  [{:keys [client db wants store-fn]} f]
  (let [db (or db default-db)]
    (-> (load-graph client db)
        (.then (fn [docs]
                 (let [api (persist/recording-db-api (seed-tx (select docs wants)))
                       st (store-fn {:db-api api :seq-fn (ordinal-fn)})
                       result (f st)
                       written (persist/recorded api)]
                   (-> (flush-tx! client db written)
                       (.then (fn [_] (assoc result :written (count written)))))))))))

(defn read-doc
  "One document straight out of the ref, without running an actor."
  ([client kind id] (read-doc client default-db kind id))
  ([client db kind id]
   (-> (load-graph client db) (.then #(get-in % [(name kind) (str id)])))))

(defn read-all
  "Every document of a kind, id-sorted for determinism."
  ([client kind] (read-all client default-db kind))
  ([client db kind]
   (-> (load-graph client db)
       (.then (fn [docs] (->> (get docs (name kind) {}) (sort-by key) (mapv val)))))))


;; ───────────────────────── running an actor ─────────────────────────

(defn run
  "One synchronous actor pass, the shape every marketplace actor shares.

  advise -> govern -> phase-gate -> commit, escalate or hold. The
  functions come from the actor because the JUDGEMENT is the actor's;
  what is shared is only the order the four steps happen in and the
  rule that a held or escalated proposal still writes a ledger fact.
  That last part is why this is here rather than copied: an actor that
  forgets to record its own refusal has an audit trail that only
  contains successes.

  `ops` keys:
    :advise      (fn [st request]        ) -> proposal
    :check       (fn [request ctx p st]  ) -> verdict
    :disposition (fn [verdict]           ) -> base disposition
    :gate        (fn [phase request base]) -> {:disposition :reason}
    :commit!     (fn [st proposal request])
    :ledger!     (fn [st fact]           )
    :hold-fact   (fn [request ctx verdict]) -> fact"
  [{:keys [advise check disposition gate commit! ledger! hold-fact]} st context request]
  (let [proposal (advise st request)
        verdict (check request context proposal st)
        base (disposition verdict)
        {d :disposition reason :reason} (gate (:phase context) request base)]
    (case d
      :commit
      (do (commit! st proposal request)
          (ledger! st {:t :committed :op (:op request) :ref (:ref request)})
          {:disposition :commit :verdict verdict})

      :escalate
      (do (ledger! st {:t :approval-requested :op (:op request) :ref (:ref request)
                       :reason (or reason :high-stakes)})
          {:disposition :escalate :verdict verdict :reason reason})

      (do (ledger! st (hold-fact request context verdict))
          {:disposition :hold :verdict verdict :reason reason}))))

(defn outcome
  "The JSON body every actor answers a write with: what happened and,
  when it did not happen, the RULES that stopped it — never a prose
  apology a caller has to parse."
  [ref out]
  (cond-> {:ref ref
           :disposition (name (:disposition out))
           :violations (mapv (comp name :rule) (get-in out [:verdict :violations]))}
    (:reason out) (assoc :reason (str (:reason out)))))

;; ───────────────────────── the fetch handler ─────────────────────────

(defn serve
  "The shape every marketplace Worker's `fetch` has.

  `routes` is a function of `[client request env method path url]`
  returning a Promise of a Response, or nil to fall through to 404.
  Handles the two things all of them get wrong otherwise: failing closed
  when the seed is missing, and turning a store error into a 502 with
  its message instead of an opaque 500."
  [service request env routes]
  (let [url (js/URL. (aget request "url"))
        path (aget url "pathname")
        method (aget request "method")
        client (client-for env)]
    (cond
      (= path "/health")
      (js/Promise.resolve
       (json {:ok (some? client)
              :service service
              :store "kotobase.net via kotoba-lang/kotobase-client"
              :db default-db
              :auth "CACAO minted per request by the client (:apex profile)"
              :did (:did client)}
             (if client 200 503)))

      (nil? client)
      (js/Promise.resolve
       (json {:error "no KOTOBASE_SECRET_KEY -- refusing to serve without a store"
              :service service}
             503))

      :else
      (or (some-> (routes client request env method path url)
                  (.catch (fn [e]
                            (json {:store-error (str (or (aget e "message") e))} 502))))
          (js/Promise.resolve (json {:error "not found"} 404))))))
