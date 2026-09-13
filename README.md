Odds feed schema
-----

The Oddin odds feed is separated into two parts: a high performance messaging feed through AMQP (Advanced Message
Queuing Protocol) and a classic REST based XML API.

### Layout

```
schema/feed/     one file per AMQP message, plus the shared types.xsd
schema/rest/     one file per REST endpoint response
schema/common/   attribute groups for the elements both wires share
test/fixtures/   captured payloads, one directory per schema
```

Neither wire uses an XML namespace, so no schema declares a `targetNamespace`.

### Validating the schema

`make check` compiles every schema and validates every captured payload in
`test/fixtures/<feed|rest>/<schema name>/` against
`schema/<feed|rest>/<schema name>.xsd`. It needs a JDK and nothing else, and it
is what CI runs on every pull request.

It fails when

* a schema does not compile - the SDKs generate their bindings from these files,
  so a schema that does not compile is a schema nobody can consume;
* a captured payload does not validate;
* a payload carries an attribute the schema does not declare (no type here
  declares `xs:anyAttribute`, so this is how silent producer drift is caught);
* a message type or endpoint has no captured payload at all.

When a producer starts sending something new, add the attribute to the schema and
a payload that exercises it in the same change.

[NOTES.md](NOTES.md) records the conventions, which attributes exist in an SDK but
on no wire, and why `<sport_event_status>` is defined twice on purpose.

### Versioning

Releases are tagged `vMAJOR.MINOR.PATCH`. The major tracks the generation of the
contract, not this repository's own history:

* **MAJOR** - the contract generation. `v1.x.y` describes wire v1, the one behind
  the `/v1/` REST paths and the `feed/v1` / `rest/v1` SDK packages. A `v2.0.0`
  would only ever accompany a `/v2/`, so a major bump is something clients are
  told about rather than something they discover.
* **MINOR** - additive: a new optional attribute, a new message or endpoint.
  Nothing that was valid before stops being valid, so it is safe to ignore until
  you want the data.
* **PATCH** - a correction that does not change what the producer sends: a schema
  that did not compile, a wrong type or URN pattern, documentation, CI.

Pin a tag where you want reproducibility. gosdk's schema conformance test takes
one through `ODDSFEEDSCHEMA_REF` and otherwise follows `main`, so an untagged
change on `main` still has to pass that test.

Releases before `v1.0.0` used a single `vYYYY.NN` tag (`v2025.01`). It is left in
place and not continued.
