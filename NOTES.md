Schema notes
=====

Why this file exists: the schema, the producers and the SDK models had drifted
apart, and every reviewer who noticed re-derived the same conclusions from
scratch. This records the decisions so the next one does not have to.

Conventions
-----

**No XML namespace, anywhere.** The AMQP messages and the REST responses are both
served without a namespace, and none of the three SDKs looks for one. The REST
schemas used to declare `targetNamespace="http://schemas.oddin.gg/v1"` in 18 of
26 files, which meant no real response validated against them; the declaration
was removed rather than added to the remaining eight.

**One definition per wire element.** `<scoreboard>` and `<period_score>` appear in
both the AMQP feed and the REST API and the producer emits the same shape in
both, so their attribute lists live once in `schema/common/` and are referenced
from `schema/feed/types.xsd` and `schema/rest/types/sport_event_status.xsd`.

`<sport_event_status>` is deliberately still two types. It is not the same
element on the two wires: the feed sends `status` as a numeric enumeration plus
`match_status`, the REST API spells the status out (`live`, `closed`) and sends
`match_status_code`. Only its two child elements are shared.

**Every added attribute is `use="optional"`,** because the producer emits a
sport-specific subset - a CS2 scoreboard carries rounds, a cricket scoreboard
carries overs and wickets.

**Where the two copies of a shared element disagreed, the producer decided.** That
meant three type corrections: the per-period sub-scores are `xs:int` and not
`xs:decimal` (the producer sends integers and every SDK decodes integers), the
scores are `xs:double` and not `xs:decimal` (Go's float formatting can emit
exponent notation, which `xs:decimal` rejects), and `period_score/@number` is
required (the producer always sends it).

**Every message type and endpoint has at least one captured payload** under
`test/fixtures/`, and `make check` validates them. No type declares
`xs:anyAttribute`, so an attribute a producer starts sending without declaring it
here fails the check. See [README.md](README.md#validating-the-schema).

Attributes that exist in an SDK but on no wire
-----

Legacy fields, carried over from the original code generation of the SDK models.
No Oddin producer emits them and nothing consumes them.

**Verdict: do not add them to the schema.** They should be dropped from the SDK
models instead, one SDK at a time; that is out of scope here. The "Where in the
SDKs" column lists the SDKs that still carry a field for the attribute, checked
2026-11-18 against the current sources: gosdk and netcoresdk on `main`, javasdk
1.0 on `main` and javasdk 0.x on `release/0.x`. javasdk 1.0 generates its XML
bindings from this schema, so it carries a legacy attribute only if a
hand-written class binds it, and none does; the deprecated getters it keeps for
source compatibility (`getRefId()`, `getCertainty()` and so on) return a
constant, are bound to nothing, and are not counted. A mention in a comment is
not counted either.

| Attribute | Where in the SDKs | Verdict |
| --- | --- | --- |
| `event_ref_id` | gosdk, netcoresdk, javasdk 0.x (as `eventRefId`) | Legacy event alias. Not sent. |
| `ref_id` | gosdk, netcoresdk, javasdk 0.x, on most elements | Legacy id alias. Not sent. |
| `superceded_by` | netcoresdk (on the raw `bet_cancel` model), javasdk 0.x; commented out in the producer as "not used" | Not sent. |
| `certainty` | netcoresdk (raw `bet_settlement` model), javasdk 0.x; deprecated in both ([netcoresdk#72](https://github.com/oddin-gg/netcoresdk/pull/72), [javasdk#269](https://github.com/oddin-gg/javasdk/pull/269)). javasdk 1.0 deprecates `getCertainty()` and has no field; gosdk has none | Not sent. |
| `betstop_reason`, `betting_status` | netcoresdk, javasdk 0.x | Not sent. `bet_stop` carries `groups` and `market_status` instead - see `schema/feed/bet_stop.xsd`. |
| `odds_change_reason` | netcoresdk (javasdk 0.x only names it in a generated comment) | Not sent. |
| `next_live_time`, `start_time_confirmed` | netcoresdk, javasdk 0.x: `next_live_time` on the `fixture_change` message and on the REST fixture, `start_time_confirmed` on the REST fixture | Not sent. |
| `cashout_status`, `dead_heat_factor` | netcoresdk, javasdk 0.x: `cashout_status` on an odds_change market, `dead_heat_factor` on a bet_settlement outcome | Not sent. |
| `extended_specifiers` | gosdk, netcoresdk, javasdk 0.x; commented out in the producer as "not used" | Not sent. `specifiers` is the only one. |
| `win_probabilities`, `lose_probabilities`, `half_win_probabilities`, `half_lose_probabilities`, `refund_probabilities` | netcoresdk, javasdk 0.x | Not sent. The outcome carries a single `probabilities`. |
| `groups` on an odds_change market | netcoresdk (also on its bet_settlement and bet_cancel markets) | Not sent at market level. `groups` is real on `bet_stop` and on a REST `market_description`, which is what makes this one easy to misread. |
| `team` on an outcome | netcoresdk, javasdk 0.x | Not sent. |
| `decided_by_fed`, `winning_reason`, `period` on the status | netcoresdk (all three) and javasdk 0.x (`period` only), on the REST sport_event_status only | Not sent. |
| `void_reason_id`, `void_reason_params` on an odds_change or bet_settlement market | gosdk (both market types); netcoresdk and javasdk 0.x (bet_settlement only) | Sent on `bet_cancel` markets only, where the schema declares them. |
| `match_status_code` on the **feed** `sport_event_status` | nowhere | The audit put it here by mistake. On the feed the attribute lives on `period_score` (declared, required) and on the SDK-only `results` element; the status itself sends `match_status`. Only `winner_id` was genuinely missing from the feed status. |

Child elements in the same category: `results` (netcoresdk and javasdk 0.x, on
the feed `sport_event_status`; a legacy per-period result list), `clock`
(netcoresdk: a generated `clockType` that no field references), `statistics`
(gosdk, on the feed `sport_event_status`; netcoresdk and javasdk 0.x only keep
an unreferenced generated type), `reference_ids` (gosdk, on the REST
sport_event and tournament), `market_metadata` (netcoresdk, javasdk 0.x),
`odds_generation_properties` (netcoresdk) and `delayed_info` (netcoresdk,
javasdk 0.x, on the REST fixture). None is emitted; none is declared.

Questions the audit left open, now answered
-----

* **`result` / `void_factor` on an odds_change outcome.** Not sent. gosdk reuses one
  `Outcome` struct for `odds_change` and `bet_settlement`, which is where the
  ambiguity came from; the producer sets them only when building a settlement.
  They stay declared on the bet_settlement outcome only.
* **`icon_path` on `sport`.** Belongs to `sportExtended`, which only the `sports`
  endpoint returns. The plain `sport` nested inside a tournament never carries
  it. The schema was already right; gosdk merges the two types, which is what
  made it look wrong.
* **`start_time` / `next_live_time` on `fixture_change`.** Not sent. The AMQP
  `fixture_change` carries `change_type` and the standard message attributes,
  nothing else.

Known-latent, deliberately left alone
-----

Places where the producer's model permits something the schema forbids, but which
has never been observed on the wire. Loosening them would turn a single object
into a list in every generated binding, so they are recorded rather than changed:

* `teamExtended/sport` and `tournament/tournament_length` are slices in the
  producer but `maxOccurs="1"` here.
* The producer can attach `<category>` elements to `teamExtended` and
  `tournament`. The schema declares no `category` element at all, and no
  response has ever contained one.

If any of these ever ships, `make check` will fail on the captured payload, which
is the point.
