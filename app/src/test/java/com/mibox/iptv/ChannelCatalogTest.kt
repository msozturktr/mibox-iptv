package com.mibox.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ChannelCatalogTest {

    @Test
    fun defaultChannels_haveUniqueAndSequentialNumbers() {
        val channels = ChannelRepository.getDefaultChannels()
        assertTrue("Channel list should not be empty", channels.isNotEmpty())

        val numbers = channels.map { it.number }
        val ids = channels.map { it.id }

        // Test uniqueness
        assertEquals("Channel numbers must be unique", numbers.size, numbers.toSet().size)
        assertEquals("Channel IDs must be unique", ids.size, ids.toSet().size)

        // Test sequential 1..N
        val expectedNumbers = (1..channels.size).toList()
        assertEquals("Channel numbers must be strictly sequential starting at 1", expectedNumbers, numbers)
    }

    @Test
    fun defaultChannels_doNotContainAmbiguousAileTv() {
        val channels = ChannelRepository.getDefaultChannels()
        for (channel in channels) {
            val lowerName = channel.name.lowercase()
            assertFalse(
                "Must not contain ambiguous Aile TV / Sinema Aile stream: ${channel.name}",
                lowerName.contains("aile tv") || lowerName.contains("sinema aile")
            )
        }
    }

    @Test
    fun channelRenumbering_maintainsSequentialOrderAfterSwap() {
        val channels = ChannelRepository.getDefaultChannels().toMutableList()
        // Simulate swapping channel 0 and 1
        Collections.swap(channels, 0, 1)

        // Renumber channels
        channels.forEachIndexed { index, channel ->
            channel.number = index + 1
        }

        val numbers = channels.map { it.number }
        val expected = (1..channels.size).toList()
        assertEquals("Channel numbers must remain sequential 1..N after swap", expected, numbers)
    }
}
