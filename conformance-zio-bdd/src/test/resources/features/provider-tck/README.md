# Provider conformance suite (vendored)

The OpenFeature provider TCK, vendored unchanged from
[`specification/assets/provider-tck`](https://github.com/open-feature/spec/tree/specification/assets/provider-tck/v0.1.0/specification/assets/provider-tck)
in open-feature/spec.

Pinned: `specification/assets/provider-tck/v0.1.0` (`10b1a610ed0afe6bf0e38b441d7362f5a76cb47d`)

The six `*.feature` files and `canonical-flags.json` must stay byte-identical to the pin; the
`Provider TCK drift` workflow compares them with upstream. Upstream marks the suite experimental.
Step definitions, the providers it runs against, their declared capabilities and the reviewed
known failures live in `zio.openfeature.conformance.bdd.tck`.
