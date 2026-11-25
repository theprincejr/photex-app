package com.example.photx.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherProviderTest {
    @Test
    fun `given default dispatcher provider_when accessing io and default_then exposes standard dispatchers`() {
        val provider: DispatcherProvider = DefaultDispatcherProvider()

        assertSame(Dispatchers.IO, provider.io)
        assertSame(Dispatchers.Default, provider.default)
    }

    @Test
    fun `given main dispatcher installed_when accessing main_then resolves lazily without eager init crash`() {
        val provider: DispatcherProvider = DefaultDispatcherProvider()
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            assertSame(Dispatchers.Main, provider.main)
            assertSame(Dispatchers.Main.immediate, provider.mainImmediate)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
