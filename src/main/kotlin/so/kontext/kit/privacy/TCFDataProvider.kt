package so.kontext.kit.privacy

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Reads IAB Transparency and Consent Framework (TCF) v2 data from
 * `SharedPreferences`. Mirrors iOS `TCFDataProvider`, which reads from
 * `UserDefaults` — both are the spec-mandated storage locations the host
 * app's CMP (Consent Management Platform) writes to.
 *
 * The two keys (`IABTCF_gdprApplies`, `IABTCF_TCString`) are documented
 * in the IAB TCF v2 storage spec — every CMP that claims TCF compliance
 * writes these. This provider only *reads*; consent collection itself is
 * the host app's responsibility (CMPs handle the UI + storage).
 *
 * `gdpr` is nullable to distinguish "no CMP installed" (null) from "CMP
 * decided GDPR doesn't apply" (0) — the server's `regulatorySchema`
 * treats the two cases differently.
 *
 * Strict validation at the boundary (parity with iOS):
 * - `gdprConsent` is rejected if empty or whitespace-only (invalid per IAB).
 * - `gdpr` must be exactly 0 or 1 per IAB TCF v2.2; out-of-range values
 *   from misbehaving CMPs decay to null rather than being forwarded to
 *   the ad server as junk.
 * - The raw `IABTCF_gdprApplies` value is read defensively because
 *   different CMPs store it as Int / Boolean / String; `getInt` throws
 *   `ClassCastException` on type mismatch, so we go through `prefs.all`
 *   to inspect the actual stored type without crashing.
 */
public object TCFDataProvider {

    private const val KEY_GDPR_APPLIES = "IABTCF_gdprApplies"
    private const val KEY_TC_STRING = "IABTCF_TCString"

    public data class TCFData(
        val gdpr: Int?,
        val gdprConsent: String?,
    )

    public fun collect(context: Context): TCFData {
        val all = PreferenceManager.getDefaultSharedPreferences(context).all
        return TCFData(
            gdpr = normalizedGdprApplies(all[KEY_GDPR_APPLIES]),
            gdprConsent = normalizedTcString(all[KEY_TC_STRING] as? String),
        )
    }

    /**
     * Dictionary representation for bridge layers (RN, Flutter). Wire
     * keys (`gdpr`, `gdprConsent`) match the kontext ad-server's
     * `regulatorySchema` (openRTB-style) — keeps bridge consumers
     * aligned with the request shape Preload sends to the server,
     * rather than the IAB TCF storage spec's wire names. iOS's
     * `getTCFDataAsDict` uses the same keys.
     */
    public fun collectAsDict(context: Context): Map<String, Any?> {
        val tcf = collect(context)
        return mapOf(
            "gdprConsent" to tcf.gdprConsent,
            "gdpr" to tcf.gdpr,
        )
    }

    private fun normalizedTcString(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }

    /**
     * Normalises the raw `IABTCF_gdprApplies` value to exactly 0 or 1.
     * Accepts Int, Long, Boolean, and String wire shapes (different
     * CMPs store different types). Anything outside {0, 1} → null.
     */
    private fun normalizedGdprApplies(raw: Any?): Int? {
        val parsed = when (raw) {
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            is Boolean -> if (raw) 1 else 0
            is String -> raw.toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it == 0 || it == 1 }
    }
}
