package com.example.photx.core.domain.gallery

/**
 * Pure domain representation of the runtime device media permission state across Android 8–16+.
 */
public sealed interface MediaPermissionState {
    /** Full access to both device images and videos. */
    public data object Full : MediaPermissionState

    /**
     * Partial media visibility (for example Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, or only
     * one of image/video permissions granted on Android 13+).
     *
     * Absent items from a scan under [Partial] access must never be interpreted as deleted.
     */
    public data class Partial(
        public val imagesGranted: Boolean,
        public val videosGranted: Boolean,
        public val userSelectedOnly: Boolean
    ) : MediaPermissionState

    /** No runtime media permission granted; local offline Room index remains readable. */
    public data object Denied : MediaPermissionState

    /** True when MediaStore queries are permitted to inspect at least some device media. */
    public val canScanDeviceMedia: Boolean
        get() = this is Full || this is Partial

    /**
     * True only when full media visibility is granted so disappearance from MediaStore can be
     * verified safely with a follow-up existence probe.
     */
    public val allowsExternalDeletionReconciliation: Boolean
        get() = this is Full
}
