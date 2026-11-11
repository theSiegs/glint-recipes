package com.thesiegs.glint.recipes

/**
 * Which kind of app a package is, one list for every reader (review 2026-11-10 finding 8): the
 * catalog's category first where the tile exchange classifies the app ([catalog] "messaging",
 * "audio"/"music"), then these seed lists for apps it doesn't (yet), or with the exchange off. Was
 * four chat lists (recipes' people apps, Arrivals' chat apps, stack names, Arrivals deals' known
 * messaging) and two music lists (stack names, trigger presets), each a little different. Pure.
 */
object AppKinds {
    /** Messengers and SMS apps: a person's messages, never a recipe; a chat stack; dealt as their people. */
    val Chat: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "org.telegram.messenger",
        "com.facebook.orca", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.android.messaging", "com.textra", "com.verizon.messaging.vzmsgs",
        "com.google.android.apps.googlevoice", "com.discord", "com.Slack", "com.microsoft.teams",
        "com.google.android.apps.dynamite", "com.snapchat.android", "com.viber.voip", "jp.naver.line.android",
        "com.kakao.talk", "com.tencent.mm", "im.vector.app", "chat.simplex.app", "com.beeper.android",
    )

    /** Music, podcasts and audiobooks. */
    val Audio: Set<String> = setOf(
        "com.google.android.apps.youtube.music", "com.spotify.music", "com.apple.android.music",
        "com.amazon.mp3", "deezer.android.app", "com.aspiro.tidal", "com.soundcloud.android",
        "com.pandora.android", "com.audible.application", "com.google.android.apps.podcasts",
        "au.com.shiftyjelly.pocketcasts", "fm.player", "com.bambuna.podcastaddict", "com.sirius",
        "tunein.player", "app.tunein.player", "com.iheartradio.android", "com.iheartradio.android.mobile",
        "com.qobuz.music", "com.plexapp.plexamp", "com.overdrive.mobile.android.libby",
        "fm.castbox.audiobook.radio.podcast", "com.storytel",
    )

    /**
     * AI assistants and chat bots: their replies come as chat-style notifications, but no person writes
     * in them, so they're never a chat app (owner 2026-11-11: "size when someone writes" on Claude).
     */
    val Assistant: Set<String> = setOf(
        "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.microsoft.copilot",
        "ai.perplexity.app.android", "ai.x.grok", "com.deepseek.chat", "ai.character.app", "ai.inflection.pi",
    )

    /** The catalog's kind that means an AI assistant ([com.thesiegs.glint.recipes.exchange.AppCategories.KINDS]). */
    const val ASSISTANT_KIND = "assistant"

    /** The catalog's categories that mean a chat app. */
    val ChatCategories = setOf("messaging")

    /** The catalog's categories that mean an audio app. */
    val AudioCategories = setOf("audio", "music")

    /**
     * Whether [packageName] is a chat app: the catalog's [catalog] category says so, or the seed list
     * does (the catalog adds apps; a seed app never drops out on a catalog's other word for it).
     */
    fun isChat(packageName: String, catalog: String? = null): Boolean =
        catalog in ChatCategories || packageName in Chat

    /** Whether [packageName] is an AI assistant or bot: the catalog's [kind] says so, or the seed list does. */
    fun isAssistant(packageName: String, kind: String? = null): Boolean =
        kind == ASSISTANT_KIND || packageName in Assistant

    /** Whether [packageName] is a music, podcast or audiobook app, as [isChat] decides. */
    fun isAudio(packageName: String, catalog: String? = null): Boolean =
        catalog in AudioCategories || packageName in Audio
}
