package com.github.libretube.enums

/**
 * The sections a video can be dismissed from.
 *
 * The subscriptions feed and the featured section of the home tab share the same
 * underlying feed data and therefore share one dismissal scope.
 */
enum class DismissableSection {
    FEED,
    CONTINUE_WATCHING
}
