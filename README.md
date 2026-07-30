# marketplace

**Federated marketplace protocol primitives — pure `.cljc`, no network
I/O, no clock, no key custody.**

**Maturity**: :implemented — 12 namespaces with a green suite (169 tests
/ 719 assertions), consumed by 13 sibling repos. Implemented means the
contracts exist, are tested and are depended on; it does **not** mean a
production transaction has settled through them. Nothing here reaches a
network, and the `acceptance` rail is connected to no PSP. Stated
explicitly because a maturity scan reading prose would otherwise take
*landed cost* (an incoterms phrase in `crossborder`) for a shipping
status.

A [kotoba-lang](https://github.com/kotoba-lang) capability library that
composes the workspace's existing commerce primitives into the five
contracts a *multi-seller* marketplace needs and a single-shop back
office does not. Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn)
in the `com-junkawasaki/root` superproject.

## Why this exists

`cloud-itonami` had ~1,334 open-business blueprints and no marketplace.
ISIC 4791 (mail-order/Internet retail) is a **single-seller** back-office
actor; `gtin-catalog` was design-only; `partners` recruits franchisees,
not sellers. Nothing joined seller identity, a cross-seller catalog,
listings, split settlement and cross-border into one governed whole.

Almost every *primitive* already existed, though — so this library
composes rather than reinvents:

| Concern | Delegated to |
|---|---|
| Identity verification | [`ekyc`](https://github.com/kotoba-lang/ekyc) |
| Sanctions / AML | [`aml`](https://github.com/kotoba-lang/aml) |
| Integer split allocation | [`pay`](https://github.com/kotoba-lang/pay) |
| GTIN-14 + GS1 check digit, product↔party ids | [`product-party`](https://github.com/kotoba-lang/product-party) |
| Index / tokenize / score | [`search`](https://github.com/kotoba-lang/search) |

## Protocol, not platform

Three invariants define this as a protocol an operator runs an instance
of, rather than a platform that owns its sellers:

1. **Seller identity survives leaving.** A `marketplace.seller`
   credential carries the evidence outcomes the issuer relied on, so a
   *receiving* instance can decide admission itself. It re-derives the
   required check set from the seller's kind rather than trusting the
   issuer's `complete?` flag — an issuer with a laxer floor produces a
   credential that fails at the receiver, which is the whole point.
2. **No custody.** `marketplace.settlement` computes allocations. It
   never moves money, holds a key, or reaches the network.
3. **No adjudication.** `crossborder` proposes HS headings and records
   disputes; it contains no function that decides one. There is
   deliberately no `resolve-dispute` that reads the evidence and
   returns an outcome — only one that records what a **named human**
   decided.

## Namespaces

```clojure
marketplace.seller       ; portable seller credential + admission + portability envelope
marketplace.catalog      ; cross-seller offers on one canonical product + buy box
marketplace.listing      ; listing admission, restricted goods, search projection
marketplace.settlement   ; multi-seller split, payout destinations, escrow
marketplace.acceptance   ; コード決済 — PSP-attested capture, refunds, custody flag
marketplace.crossborder  ; landed cost, HS proposals, dispute intake
```

## The buy box is auditable

On a closed platform, "which seller wins the buy box" is the most opaque
and most rent-extracting function there is. Here it is a pure function
whose ranking key is entirely observable by sellers — landed price,
condition, lead time, then offer id as a total tie-break. No hidden
boost, no paid placement. `buy-box` returns the full ranking *and* the
exclusions with reasons, so a seller who lost can reproduce the result.

```clojure
(catalog/buy-box cat "gtin.05449000000996"
                 {:shipping {"offer.…a" 0 "offer.…b" 500}
                  :eligible? #(seller/sellable? (sellers (:offer/seller %)) now home)})
;; => {:buy-box/winner {…} :buy-box/ranked [… …] :buy-box/excluded [{:offer/id … :reason :not-in-stock}]}
```

Mixed currencies are **refused, not converted** — this library has no FX
rate and will not invent one.

## Money

Every amount is an integer count of the currency's smallest unit — the
[`reji`](https://github.com/kotoba-lang/reji) discipline, *money never
touches a float*. `settlement/->pay-micros` is a separate, explicit
bridge to `pay`'s 6-decimal micros, because silently passing cents where
micros are expected would under-pay by 10,000×.

Conservation is carried on every plan and asserted over a matrix of
commission rates and basket shapes:

```clojure
(:plan/conserved? plan)  ; seller payouts + commission == gross, exactly
```

Rounding dust goes to the **seller**, never the operator.

## Code payment (コード決済) is acceptance, not a payout rail

`settlement` describes money going OUT to sellers. A QR / barcode code
payment comes IN from the buyer, and it is not a fourth
`settlement/payout-rails` entry: a code-payment PSP settles to ONE
merchant's bank account, so it can never be a per-seller destination.
`acceptance/payout-leg-rail` states the consequence — the seller's share
of a code payment travels on `:bank-transfer`.

Two facts this namespace refuses to let a caller forget:

- **A buyer's completion screen is not evidence of payment.** `capture`
  accepts only a webhook the PSP sent or the PSP's answer to a query, and
  refuses a buyer-presented claim by name (`:buyer-presented-evidence`).
- **This rail is custodial and `:x402` is not.** A PSP cannot split at
  pay time, so the seller's money passes through the operator —
  `:accept/operator-custodial? true` on every request. That is 収納代行
  territory under 資金決済法; this library states the flag, it does not
  decide whether a deployment may proceed.

A `:mpm-static` code carries no amount — the buyer types it — so a
shortfall is *captured and reported* (`:short`), never released to
settlement. Overpayment is reported too, rather than pocketed. There is
no PSP client here and there will not be one: a vendor-specific API
client belongs in a vendor repo, so `:accept/psp` is a name the
deployment supplies.

## What this library refuses to know

- **Duty rates.** No tariff table ships here. Rates are operator-supplied
  and must carry a `:rate/source` and `:rate/as-of` or they are refused.
  A missing rate yields `:landed/computable? false` naming the missing
  input — never a plausible-looking guess.
- **What is lawful.** `restricted-baseline` is a floor of categories
  essentially every consumer marketplace restricts. It is not legal
  advice and not exhaustive; operators supply their jurisdiction's list.
  A restricted category produces a *refusal to display*, never a finding
  that a seller broke the law.

## Known limitation: Japanese search recall

`search.model/tokenize` matches runs of `[a-z0-9]` and CJK ranges with no
morphological segmentation, so `"ワイヤレスイヤホン"` is **one token** and a
query for `"イヤホン"` does not match it. The mitigation is explicit
`:listing/keywords`, which land in `:search/tags`. This is asserted in
the test suite (`japanese-recall-limitation-is-real-and-mitigated-by-keywords`)
so the limitation stays visible rather than being discovered in
production. Do not read the presence of a search projection as a claim
of good Japanese recall.

## Test

```bash
clojure -M:test    # 56 tests, 323 assertions
clojure -M:lint
```

## Consumers

The governed actors that wrap these contracts live in `cloud-itonami`:

| Gap | Actor repo |
|---|---|
| Seller identity / KYC | `cloud-itonami-marketplace-onboarding` |
| Canonical catalog | `cloud-itonami-gtin-catalog` |
| Listing / search / buyer surface | `cloud-itonami-marketplace-listing` |
| Split settlement / payout | `cloud-itonami-marketplace-settlement` |
| Cross-border / disputes | `cloud-itonami-marketplace-crossborder` |

This library holds contracts and arithmetic; those actors hold the
`Advisor ⊣ Governor` gates, the human approval workflow and the
append-only audit ledger.
