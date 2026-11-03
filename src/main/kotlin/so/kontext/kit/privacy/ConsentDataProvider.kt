package so.kontext.kit.privacy

import android.content.Context
import android.content.SharedPreferences

/**
 * Reads the consent signals a CMP (Consent Management Platform) writes to the app's default
 * `SharedPreferences` under the IAB's shared keys: TCF v2 (`IABTCF_`), GPP (`IABGPP_`) and the legacy US
 * Privacy string (`IABUSPrivacy_String`). Mirrors iOS `ConsentDataProvider`, which reads the same keys from
 * `UserDefaults`. This provider only reads; collecting consent is the host app's CMP's job.
 *
 * Strict validation at the boundary (parity with iOS):
 * - Strings must be non-empty (whitespace-only is treated as empty).
 * - `gdpr` must be exactly 0 or 1 (IAB TCF v2.2), whatever type the CMP stored it as; out-of-range values
 *   decay to null rather than being forwarded to the ad server as junk.
 * - `gppSid` is the section-id list as the GPP in-app spec writes it, `2_7`; a CMP writing `2,7` or a single
 *   number is read too; parts that are not numbers are skipped, as on iOS.
 * - Every value is read through `SharedPreferences.all`, never a typed getter, so a CMP that stored another
 *   type than expected cannot make the read throw.
 *
 * Field names match openRTB / the ad servers' `regulatorySchema` (`gdpr`, `gdprConsent`, `gpp`, `gppSid`,
 * `usPrivacy`) rather than the IAB storage keys. What the publisher set by hand, and `coppa`, are the
 * consumer's to merge.
 */
public object ConsentDataProvider {

    private const val KEY_GDPR_APPLIES = "IABTCF_gdprApplies"
    private const val KEY_TC_STRING = "IABTCF_TCString"
    private const val KEY_GPP_STRING = "IABGPP_HDR_GppString"
    private const val KEY_GPP_SID = "IABGPP_GppSID"
    private const val KEY_US_PRIVACY = "IABUSPrivacy_String"

    public data class ConsentData(
        /** Whether GDPR applies: 1 yes, 0 no, null unknown (absent, or not 0 or 1). */
        val gdpr: Int?,
        /** The TC (Transparency & Consent) string. */
        val gdprConsent: String?,
        /** The GPP header string. */
        val gpp: String?,
        /** The GPP section ids in [gpp]. */
        val gppSid: List<Int>?,
        /** The US Privacy (CCPA) string. */
        val usPrivacy: String?,
    )

    public fun collect(context: Context): ConsentData {
        val all = runCatching { defaultSharedPreferences(context).all }.getOrNull().orEmpty()
        return ConsentData(
            gdpr = gdprApplies(all[KEY_GDPR_APPLIES]),
            gdprConsent = nonEmpty(all[KEY_TC_STRING]),
            gpp = nonEmpty(all[KEY_GPP_STRING]),
            gppSid = gppSections(all[KEY_GPP_SID]),
            usPrivacy = nonEmpty(all[KEY_US_PRIVACY]),
        )
    }

    /** Map form of [collect] for bridge layers (React Native, Flutter); iOS's `collectAsDict` uses the same keys. */
    public fun collectAsDict(context: Context): Map<String, Any?> {
        val consent = collect(context)
        return mapOf(
            "gdpr" to consent.gdpr,
            "gdprConsent" to consent.gdprConsent,
            "gpp" to consent.gpp,
            "gppSid" to consent.gppSid,
            "usPrivacy" to consent.usPrivacy,
        )
    }

    // The app's default prefs file, as `PreferenceManager.getDefaultSharedPreferences` names it.
    private fun defaultSharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    private fun nonEmpty(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotBlank() }

    /**
     * `IABTCF_gdprApplies` as CMPs write it: Int, Long, Boolean or String.
     * Exactly 0 or 1; anything else is unknown.
     */
    private fun gdprApplies(raw: Any?): Int? {
        val parsed = when (raw) {
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            is Boolean -> if (raw) 1 else 0
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it == 0 || it == 1 }
    }

    /** `IABGPP_GppSID`: `2_7` or `2,7`, or one section stored as a number. */
    private fun gppSections(raw: Any?): List<Int>? = when (raw) {
        is Int -> listOf(raw)
        is Long -> raw.toInt().takeIf { it.toLong() == raw }?.let(::listOf)
        is String -> raw.split('_', ',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.isNotEmpty() }
        else -> null
    }
}
