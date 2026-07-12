Feature: Optimizely datafile over the real default CDN URL via TLS-MITM

  The provider keeps its default datafile URL (datafileUrl = None), so the SDK constructs the production
  URL https://cdn.optimizely.com/datafiles/<key>.json and performs a real HTTPS fetch. Rift's intercept
  engine MITMs that request and serves the datafile fixture, proving the default-CDN transport path works.

  @flags(datafile=kill-switch-off)
  Scenario: Datafile fetched over the default CDN URL via TLS-MITM resolves flags
    Then the recommendation service returns kind "alpha"
