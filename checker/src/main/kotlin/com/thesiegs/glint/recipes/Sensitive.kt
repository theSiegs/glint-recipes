package com.thesiegs.glint.recipes

/**
 * Health and payment apps (recipes/SPEC.md §10; tile exchange: "never health, payment or chat apps"):
 * Glint never learns their notifications' shapes, never shares them, and the sample export leaves
 * them out unless the owner turns them on. Exact packages, package prefixes, and words a package's
 * parts carry ("bank", "health"). Chat apps are [Recipes.PeopleApps]. Pure, so the phone and CI share it.
 */
object Sensitive {
    /** Known health, fitness, wallet, bank and payment apps. */
    val Packages: Set<String> = setOf(
        "com.sec.android.app.shealth", "com.google.android.apps.fitness", "com.google.android.apps.healthdata",
        "com.samsung.android.spay", "com.samsung.android.samsungpay.gear", "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user", "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash",
        "com.zellepay.zelle", "com.chase.sig.android", "com.wf.wellsfargomobile", "com.infonow.bofa",
        "com.konylabs.capitalone", "com.americanexpress.android.acctsvcs.us", "com.discoverfinancial.mobile",
        "com.usaa.mobile.android.usaa", "com.citi.citimobile", "com.coinbase.android", "com.robinhood.android",
        "com.myfitnesspal.android", "com.ouraring.oura", "com.whoop.android", "com.garmin.android.apps.connectmobile",
        "com.strava", "com.mint", "com.intuit.mint", "com.klarna.android", "com.affirm.central",
    )

    /** Whole families: every package that starts with one of these. */
    val Prefixes: List<String> = listOf(
        "com.fitbit.", "com.paypal.", "com.chase.", "com.wellsfargo.", "com.bankofamerica.", "com.capitalone.",
        "com.schwab.", "com.fidelity.", "com.vanguard.", "com.etrade.", "com.revolut.", "com.transferwise.",
        "com.samsung.android.health", "com.google.android.apps.health",
    )

    /** Words a package part may hold that mark it as health or money ("com.example.mybank", "org.x.healthsync"). */
    private val Words = listOf("bank", "health", "wallet", "fitness", "medical", "pharmacy", "insurance")

    fun isSensitive(packageName: String): Boolean {
        val p = packageName.lowercase()
        if (p in Packages || Prefixes.any { p.startsWith(it) || p == it.trimEnd('.') }) return true
        // The first part ("com", "org") says nothing; any later part with a marked word does.
        return p.split('.').drop(1).any { part -> Words.any { part.contains(it) } }
    }
}
