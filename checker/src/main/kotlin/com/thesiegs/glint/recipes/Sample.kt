package com.thesiegs.glint.recipes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One notification as a recipe sees it (recipes/SPEC.md §3): the platform extras by their short
 * names, plus what the system says about it. Glint builds one from each posted notification; a
 * fixture file holds them as JSON, the same shape, so the checker on a PC runs exactly what the phone
 * runs. Times are epoch milliseconds.
 */
@Serializable
data class NotificationSample(
    /** The system's key; any unique string in a fixture. */
    val key: String = "",
    /** Left out in a fixture: the file's own package. */
    @SerialName("package") val packageName: String = "",
    val posted: Long = 0,
    /** `Notification.when`; 0 when unset. */
    @SerialName("when") val whenTime: Long = 0,
    val channel: String? = null,
    val category: String? = null,
    /** The system's group key, or the app's group name. */
    val group: String? = null,
    /** The notification's tag; often one order or flight (a recipe can key on it). */
    val tag: String? = null,
    val summary: Boolean = false,
    val ongoing: Boolean = false,
    /** Android 16's promoted ongoing (a Live Update). */
    val promoted: Boolean = false,
    /** The channel's importance, 0..5 (3 is default). */
    val importance: Int = 3,
    /** The old `Notification.priority`, -2..2. */
    val priority: Int = 0,
    /** The style, short: "BigTextStyle", "InboxStyle", "MessagingStyle", "BigPictureStyle", "ProgressStyle", "CallStyle", "MediaStyle". */
    val template: String? = null,
    /** `android.title` (or `android.title.big` when the app set one). */
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val infoText: String? = null,
    val summaryText: String? = null,
    val conversationTitle: String? = null,
    /** InboxStyle's lines (`android.textLines`). */
    val lines: List<String> = emptyList(),
    /** MessagingStyle's messages, oldest first. */
    val messages: List<SampleMessage> = emptyList(),
    val progress: Int? = null,
    val max: Int? = null,
    val indeterminate: Boolean = false,
    /** Android 16's status-chip text (`android.shortCriticalText`). */
    val shortCritical: String? = null,
    /** `android.showChronometer`: a running clock from `when` (up), or to it with [countDown]. */
    val chronometer: Boolean = false,
    /** `android.chronometerCountDown`. */
    val countDown: Boolean = false,
    /** ProgressStyle's segment lengths, in progress units (Android 16 Live Updates): the stops on the track. */
    val segments: List<Int> = emptyList(),
    /** ProgressStyle's points' positions, in progress units. */
    val points: List<Int> = emptyList(),
    /** ProgressStyle draws a tracker icon (a car, a plane) at the progress. */
    val tracker: Boolean = false,
    /** `Notification.number`: how many it stands for (a mail summary's unread count, no words); 0 when unset. */
    val number: Int = 0,
    /** A picture is there (BigPicture's, or its icon): only that it is, never the image. */
    val hasPicture: Boolean = false,
    /** A large icon is there: only that it is. */
    val hasLargeIcon: Boolean = false,
    /** The notification's own actions, in order: their words and semantics. */
    val actions: List<SampleAction> = emptyList(),
    /** Other string extras, by key: only their keys count (the shape watch); a recipe never reads them. */
    val extras: Map<String, String> = emptyMap(),
) {
    /** The last message with a sender, else the last message. */
    val lastMessage: SampleMessage? get() = messages.lastOrNull { it.sender != null } ?: messages.lastOrNull()
}

/** One action: its [title] (the app's word, display only) and its [semantic] ([Semantics] name), if any. */
@Serializable
data class SampleAction(val title: String = "", val semantic: String? = null)

/** One MessagingStyle message; [sender] null is the phone's owner. */
@Serializable
data class SampleMessage(val sender: String? = null, val text: String? = null, val time: Long = 0)

/**
 * A fixture file (recipes/fixtures/<package>/<name>.json): scrubbed samples of one app's
 * notifications, each with what a recipe should make of it. Each sample is read alone, as the only
 * notification up, unless [together] is set, when they are all up at once (counts, merges).
 */
@Serializable
data class Fixture(
    val fixture: Int = 1,
    @SerialName("package") val packageName: String = "",
    /** The app's name, optional: the shape learner keeps it as the app's own word and the tile's caption. */
    val label: String? = null,
    /** "synthetic" (written by hand or by an agent), "captured" (scrubbed from a phone by Glint) or "learned" (the shapes Glint keeps to learn from). */
    val source: String = "synthetic",
    val scrubbed: Boolean = true,
    val notes: String? = null,
    val together: Boolean = false,
    val samples: List<FixtureSample> = emptyList(),
)

/**
 * One sample of a [Fixture], with an optional [expect]. [now] is when it's read (ms), for relative
 * times and fading; by default a minute after the newest sample.
 */
@Serializable
data class FixtureSample(
    val name: String? = null,
    val notification: NotificationSample = NotificationSample(),
    val expect: Expect? = null,
    val now: Long? = null,
)

/**
 * What a recipe should draw from a sample: [match] false means the recipe must leave it alone;
 * otherwise the rest face's [kind] and texts, any given. [recipe] narrows it to one recipe id (a
 * package with several recipes); without it the expectation is for every recipe of that package.
 */
@Serializable
data class Expect(
    val recipe: String? = null,
    val match: Boolean = true,
    val kind: FaceKind? = null,
    val title: String? = null,
    val line: String? = null,
    val peek: String? = null,
    /** How many faces draw. */
    val faces: Int? = null,
    /** The rest face draws dimmed (its notification is past the recipe's `fade`). */
    val faded: Boolean? = null,
    /** The rest face draws the notification's picture full-bleed. */
    val picture: Boolean? = null,
    /** How many stops (segment ends) and points the rest face's track has. */
    val stops: Int? = null,
    val points: Int? = null,
    /** The faces' titles in order, rest face first, when the flips matter. */
    val titles: List<String>? = null,
    /** The rest face's corner number ([FaceRule.corner]); "" for none. */
    val corner: String? = null,
    /** The rest face's detail line ([FaceRule.detail]); "" for none. */
    val detail: String? = null,
)

object Fixtures {
    /** Reads a fixture file; the samples take the file's package where they name none. */
    fun parse(text: String): Fixture {
        val f = Recipes.json.decodeFromString(Fixture.serializer(), text)
        return f.copy(samples = f.samples.mapIndexed { i, s ->
            val n = s.notification
            s.copy(notification = n.copy(
                packageName = n.packageName.ifEmpty { f.packageName },
                key = n.key.ifEmpty { "${f.packageName}#$i" },
            ))
        })
    }

    fun write(f: Fixture): String = Recipes.json.encodeToString(Fixture.serializer(), f)
}
