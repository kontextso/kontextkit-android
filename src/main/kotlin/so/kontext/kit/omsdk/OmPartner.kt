package so.kontext.kit.omsdk

/**
 * IAB Tech Lab partner identity used to register the SDK with the OMID
 * native SDK at activation time.
 *
 * `name` is shared across all Kontext SDKs (it identifies the company);
 * `version` is per-SDK because each SDK has its own OMID-implementation
 * version that's externally registered with IAB Tech Lab. Bump `version`
 * only as part of a coordinated certification update for that SDK.
 */
public data class OmPartner(public val name: String, public val version: String)
