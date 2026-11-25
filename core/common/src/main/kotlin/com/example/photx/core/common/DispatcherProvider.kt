package com.example.photx.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides coroutine dispatchers through dependency injection so production and test code
 * never hardcode [Dispatchers] directly.
 */
public interface DispatcherProvider {
    /** Main UI thread dispatcher. */
    public val main: CoroutineDispatcher

    /** Main UI thread dispatcher supporting immediate execution when already on main. */
    public val mainImmediate: CoroutineDispatcher

    /** Dispatcher optimized for blocking disk and network I/O operations. */
    public val io: CoroutineDispatcher

    /** Dispatcher optimized for CPU-intensive work such as hashing and thumbnail decoding. */
    public val default: CoroutineDispatcher
}

/**
 * Default production implementation backed by standard [Dispatchers].
 */
public class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher
        get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher
        get() = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher
        get() = Dispatchers.IO
    override val default: CoroutineDispatcher
        get() = Dispatchers.Default
}
