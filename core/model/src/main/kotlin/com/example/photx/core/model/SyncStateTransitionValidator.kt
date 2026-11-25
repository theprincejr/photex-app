package com.example.photx.core.model

/**
 * Typed outcome of validating a requested [SyncState] transition.
 */
public sealed interface SyncStateTransitionResult {
    /**
     * Transition from [from] to [to] is permitted by the PhotX synchronization state machine.
     *
     * @property from State prior to the transition.
     * @property to Validated target state.
     */
    public data class Allowed(public val from: SyncState, public val to: SyncState) : SyncStateTransitionResult

    /**
     * Transition from [from] to [to] is not permitted by the PhotX synchronization state machine.
     *
     * @property from Current persisted state.
     * @property to Rejected target state requested by the caller.
     * @property allowedStates Exact set of states directly reachable from [from].
     */
    public data class Rejected(
        public val from: SyncState,
        public val to: SyncState,
        public val allowedStates: Set<SyncState>
    ) : SyncStateTransitionResult {
        /** Human-readable diagnostic summary describing why the transition was rejected. */
        public val reason: String
            get() = "Invalid SyncState transition from $from to $to. Allowed next states: $allowedStates"
    }
}

/**
 * Centralized validator governing all [SyncState] transitions in PhotX.
 *
 * Future Domain (`:core:domain`) and Data (`:core:database`, `:core:data`) components must route
 * every state change through this validator inside an atomic Room transaction instead of writing
 * arbitrary [SyncState] values directly.
 *
 * **Boundary note:** This pure in-memory validator enforces state-machine transition rules only.
 * It does not by itself enforce database atomicity or verify remote Telegram backups or SHA-256
 * digests; destructive operations such as Free Up Space require additional repository-level and
 * remote verification guards (`SPEC.md` Section 10).
 */
public object SyncStateTransitionValidator {
    /**
     * Validates whether moving from [from] to [to] is permitted and returns a typed
     * [SyncStateTransitionResult] without throwing exceptions for expected rejections.
     */
    public fun validate(from: SyncState, to: SyncState): SyncStateTransitionResult = if (from.canTransitionTo(to)) {
        SyncStateTransitionResult.Allowed(from = from, to = to)
    } else {
        SyncStateTransitionResult.Rejected(
            from = from,
            to = to,
            allowedStates = from.allowedNextStates()
        )
    }

    /**
     * Strict assertion helper for invariant boundaries where an illegal transition indicates a
     * programming error rather than a recoverable runtime condition.
     *
     * Prefer [validate] in normal business and repository workflows so rejections remain typed.
     *
     * @throws IllegalStateException if [from] cannot transition directly to [to].
     */
    public fun requireTransition(from: SyncState, to: SyncState): SyncState =
        when (val result = validate(from = from, to = to)) {
            is SyncStateTransitionResult.Allowed -> result.to
            is SyncStateTransitionResult.Rejected -> error(result.reason)
        }
}
