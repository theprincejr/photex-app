package com.example.photx.core.common

/**
 * Represents the explicit outcome of an operation that can either succeed with [T]
 * or fail with a typed error [E], avoiding silent exception swallowing.
 */
public sealed interface Outcome<out T, out E> {
    /** Completed successfully with [value]. */
    public data class Success<out T>(public val value: T) : Outcome<T, Nothing>

    /** Failed with explicit typed [error]. */
    public data class Failure<out E>(public val error: E) : Outcome<Nothing, E>

    /** Returns `true` when this outcome represents [Success]. */
    public val isSuccess: Boolean
        get() = this is Success

    /** Returns `true` when this outcome represents [Failure]. */
    public val isFailure: Boolean
        get() = this is Failure

    /** Returns the success value or `null` on failure. */
    public fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /** Returns the failure error or `null` on success. */
    public fun errorOrNull(): E? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

/**
 * Transforms the success value using [transform] while preserving any [Outcome.Failure].
 */
public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

/**
 * Transforms the failure error using [transform] while preserving any [Outcome.Success].
 */
public inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Success -> this
    is Outcome.Failure -> Outcome.Failure(transform(error))
}

/**
 * Collapses both [Outcome.Success] and [Outcome.Failure] into a single result [R].
 */
public inline fun <T, E, R> Outcome<T, E>.fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}
