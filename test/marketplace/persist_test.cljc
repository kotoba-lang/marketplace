(ns marketplace.persist-test
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.persist :as p]))

(defn- st [] (p/store {:db-api (p/mem-db-api) :actor "orderops"}))

;; ───────────────────────── fail closed ─────────────────────────

(deftest a-missing-database-fails-loudly-at-construction
  (testing "the policy says :policy/fail-closed-without-host-injection true —
            an actor whose host wiring is missing must not come up quietly
            writing to a map that vanishes on restart"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (p/store {:actor "orderops"})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (p/store {:db-api nil :actor "orderops"})))))

(deftest a-partial-database-api-is-an-error-not-a-fallback
  (let [errs (p/db-api-errors {:transact! identity :q identity})]
    (is (= #{:pull :datoms} (set (map :persist.error/fn errs))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (p/store {:db-api {:transact! identity :q identity} :actor "x"})))))

(deftest an-actor-name-is-required-for-stream-scoping
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (p/store {:db-api (p/mem-db-api)}))))

(deftest the-memory-backend-is-labelled-so-a-readiness-check-can-refuse-it
  (is (true? (:persist/memory? (st))))
  (testing "a host-injected api that does not claim to be memory is not"
    (let [real (assoc (p/mem-db-api) :memory? false)]
      (is (false? (:persist/memory? (p/store {:db-api real :actor "x"})))))))

;; ───────────────────────────── codec ─────────────────────────────

(deftest numbers-stay-native-and-strings-are-tagged
  (testing "numbers and booleans stay native so a backend can index them"
    (is (= 42 (p/enc 42)))
    (is (= true (p/enc true)))
    (is (nil? (p/enc nil))))
  (testing "strings and compounds are TAGGED — without the tag a stored
            \"an order\" is indistinguishable from an EDN blob and decodes
            to the symbol `an`, which is silent corruption"
    (is (= "s:abc" (p/enc "abc")))
    (is (= "e:{:a [1 2]}" (p/enc {:a [1 2]}))))
  (testing "round trips"
    (doseq [v ["merchant.alpha" "an order" "not [ edn" "e:looks-tagged"
               {:a [1 2]} [1 2 3] #{:x} :kw 42 true nil]]
      (is (= v (p/dec* (p/enc v))) (pr-str v))))
  (testing "an untagged value written by something other than enc is passed
            through rather than mangled"
    (is (= "raw" (p/dec* "raw")))))

;; ───────────────────────────── documents ─────────────────────────────

(def ^:private order
  {:order-id "ord-1" :buyer "buyer-1" :currency "JPY"
   :sellers ["merchant.alpha" "merchant.beta"]
   :totals {:merchant.alpha 1200 :merchant.beta 1100}})

(deftest a-document-round-trips
  (let [s (st)
        c (p/ctx s :order :order-id)]
    (p/put-doc! c order)
    (let [got (p/get-doc c "ord-1")]
      (is (= "buyer-1" (:buyer got)))
      (is (= ["merchant.alpha" "merchant.beta"] (:sellers got))
          "a nested vector survives as a vector, not as sub-entities")
      (is (= {:merchant.alpha 1200 :merchant.beta 1100} (:totals got))))))

(deftest attributes-are-namespaced-per-kind-so-actors-cannot-collide
  (testing "a real risk once several actors point at the same D1 binding"
    (let [api (p/mem-db-api)
          s (p/store {:db-api api :actor "shared"})
          orders (p/ctx s :order :order-id)
          rmas   (p/ctx s :rma :rma-id)]
      (p/put-doc! orders {:order-id "x1" :note "an order"})
      (p/put-doc! rmas   {:rma-id "x1" :note "a return"})
      (is (= "an order" (:note (p/get-doc orders "x1"))))
      (is (= "a return" (:note (p/get-doc rmas "x1")))
          "same id, different kind, no collision"))))

(deftest writing-the-same-id-replaces-rather-than-duplicates
  (let [s (st) c (p/ctx s :order :order-id)]
    (p/put-doc! c order)
    (p/put-doc! c (assoc order :currency "USD"))
    (is (= 1 (count (p/all-docs c))))
    (is (= "USD" (:currency (p/get-doc c "ord-1"))))))

(deftest an-unknown-document-is-nil
  (is (nil? (p/get-doc (p/ctx (st) :order :order-id) "nope"))))

(deftest all-docs-is-id-sorted-for-determinism
  (let [s (st) c (p/ctx s :order :order-id)]
    (doseq [id ["ord-3" "ord-1" "ord-2"]]
      (p/put-doc! c {:order-id id :buyer "b"}))
    (is (= 3 (count (p/all-docs c))))
    (is (= (p/all-docs c) (p/all-docs c)) "stable across reads")))

;; ───────────────────────── the append-only ledger ─────────────────────────

(deftest events-append-and-read-back-in-order
  (let [s (st)
        sc (p/stream-ctx s :ledger)
        n (atom 0)
        next! #(swap! n inc)]
    (p/append-event! sc next! {:t :governor-hold :op :place-order})
    (p/append-event! sc next! {:t :committed :op :place-order})
    (is (= [:governor-hold :committed] (mapv :t (p/read-events sc))))))

(deftest ordering-is-imposed-on-read-not-trusted-from-the-store
  (testing "datom order is not a guarantee any backend makes, and an audit
            ledger read out of order is worse than no ledger"
    (let [s (st)
          sc (p/stream-ctx s :ledger)
          seqs (atom [3 1 2])
          next! #(let [v (first @seqs)] (swap! seqs rest) v)]
      (p/append-event! sc next! {:t :third})
      (p/append-event! sc next! {:t :first})
      (p/append-event! sc next! {:t :second})
      (is (= [:first :second :third] (mapv :t (p/read-events sc)))))))

(deftest two-actors-sharing-a-database-keep-separate-ledgers
  (let [api (p/mem-db-api)
        a (p/stream-ctx (p/store {:db-api api :actor "orderops"}) :ledger)
        b (p/stream-ctx (p/store {:db-api api :actor "settleops"}) :ledger)
        n (atom 0) next! #(swap! n inc)]
    (p/append-event! a next! {:t :order-fact})
    (p/append-event! b next! {:t :money-fact})
    (is (= [:order-fact] (mapv :t (p/read-events a))))
    (is (= [:money-fact] (mapv :t (p/read-events b))))))

(deftest the-sequence-is-injected-not-derived-from-a-count
  (testing "a count is a read-modify-write; two concurrent appends would
            collide on it"
    (let [s (st) sc (p/stream-ctx s :ledger)
          calls (atom 0)
          next! #(do (swap! calls inc) (* 10 @calls))]
      (p/append-event! sc next! {:t :a})
      (p/append-event! sc next! {:t :b})
      (is (= 2 @calls) "exactly one sequence call per append"))))

(deftest an-empty-stream-reads-empty
  (is (= [] (p/read-events (p/stream-ctx (st) :ledger)))))

;; ───────────────────── the host's async bridge ─────────────────────

(deftest a-recording-api-answers-reads-and-accumulates-writes
  (testing "how a synchronous actor runs on an asynchronous store:
            load -> compute -> flush"
    (let [api (p/recording-db-api)
          s (p/store {:db-api api :actor "orderops"})
          c (p/ctx s :order :order-id)]
      (p/put-doc! c {:order-id "ord-1" :buyer "b1"})
      (testing "the write is visible to the actor immediately"
        (is (= "b1" (:buyer (p/get-doc c "ord-1")))))
      (testing "and is queued for the host to flush in ONE transact"
        (is (= 3 (count (p/recorded api)))
            "one document is three triples: kind, id, doc blob")
        (is (every? vector? (p/recorded api))
            "triples, not entity maps — what kotobase-peer actually accepts")))))

(deftest a-recording-api-starts-from-a-loaded-snapshot
  (let [pre (p/recording-db-api)
        s0 (p/store {:db-api pre :actor "orderops"})]
    (p/put-doc! (p/ctx s0 :order :order-id) {:order-id "ord-1" :buyer "b1"})
    (testing "a later request seeded with what D1 held sees the earlier write"
      (let [api (p/recording-db-api (p/recorded pre))
            s (p/store {:db-api api :actor "orderops"})]
        (is (= "b1" (:buyer (p/get-doc (p/ctx s :order :order-id) "ord-1"))))
        (testing "and records nothing until this request writes something"
          (is (empty? (p/recorded api))))))))

(deftest a-recording-api-is-not-labelled-memory
  (testing "the host WILL flush it, so a readiness check must not refuse it
            the way it refuses the test-only backend"
    (is (false? (:persist/memory? (p/store {:db-api (p/recording-db-api)
                                            :actor "x"}))))
    (is (true? (:persist/memory? (p/store {:db-api (p/mem-db-api) :actor "x"}))))))
