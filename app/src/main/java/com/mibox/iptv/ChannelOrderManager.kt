package com.mibox.iptv

import android.content.Context
import org.json.JSONArray

object ChannelOrderManager {

    private const val PREFS_NAME = "mibox_iptv_prefs"
    private const val KEY_CHANNEL_ORDER = "custom_channel_order_ids"
    private const val KEY_DELETED_CHANNEL_IDS = "deleted_channel_ids"
    private const val KEY_LAST_CHANNEL_ID = "last_watched_channel_id"
    private const val KEY_RESET_PIN = "reset_pin_code"
    private const val KEY_UP_IS_NEXT = "dpad_up_is_next_channel"

    fun getDeletedChannelIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DELETED_CHANNEL_IDS, emptySet()) ?: emptySet()
    }

    fun markChannelDeleted(context: Context, channelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentDeleted = getDeletedChannelIds(context).toMutableSet()
        currentDeleted.add(channelId)
        prefs.edit().putStringSet(KEY_DELETED_CHANNEL_IDS, currentDeleted).apply()
    }

    /**
     * Restores all deleted channels back into the channel list while preserving custom order.
     */
    fun restoreDeletedChannels(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DELETED_CHANNEL_IDS).apply()
    }

    /**
     * Resets both deleted channels and custom channel ordering back to factory defaults.
     */
    fun resetAllToDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_DELETED_CHANNEL_IDS)
            .remove(KEY_CHANNEL_ORDER)
            .apply()
    }

    fun getResetPin(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RESET_PIN, null)?.takeIf { it.isNotEmpty() }
    }

    fun setResetPin(context: Context, pin: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (pin.isNullOrEmpty()) {
            prefs.edit().remove(KEY_RESET_PIN).apply()
        } else {
            prefs.edit().putString(KEY_RESET_PIN, pin).apply()
        }
    }

    /**
     * Returns true if pressing UP on the remote goes to the NEXT channel (e.g. 01 -> 02).
     * Defaults to true as requested by the user.
     */
    fun isUpForNextChannel(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_UP_IS_NEXT, true)
    }

    fun setUpForNextChannel(context: Context, upIsNext: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_UP_IS_NEXT, upIsNext).apply()
    }

    /**
     * Returns the channels ordered according to user preference,
     * excluding any user-deleted channels, and ensuring channel numbers remain sequential (1..N).
     */
    fun getOrderedChannels(context: Context, defaultChannels: List<Channel>): List<Channel> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deletedIds = getDeletedChannelIds(context)
        val activeDefaults = defaultChannels.filter { it.id !in deletedIds }.ifEmpty { defaultChannels }
        val savedJson = prefs.getString(KEY_CHANNEL_ORDER, null)
            ?: return activeDefaults.mapIndexed { index, ch -> ch.copy(number = index + 1) }

        return try {
            val jsonArray = JSONArray(savedJson)
            val savedIds = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val id = jsonArray.getString(i)
                if (id !in deletedIds) {
                    savedIds.add(id)
                }
            }

            val channelMap = activeDefaults.associateBy { it.id }
            val orderedList = mutableListOf<Channel>()

            // Add channels in the saved order
            for (id in savedIds) {
                channelMap[id]?.let { orderedList.add(it) }
            }

            // Append any channels that exist in active defaults but weren't in saved order
            for (channel in activeDefaults) {
                if (channel.id !in savedIds) {
                    orderedList.add(channel)
                }
            }

            // Re-assign sequential channel numbers (1..N)
            orderedList.mapIndexed { index, channel ->
                channel.copy(number = index + 1)
            }
        } catch (e: Exception) {
            activeDefaults.mapIndexed { index, ch -> ch.copy(number = index + 1) }
        }
    }

    /**
     * Persists the current list of channel IDs to SharedPreferences.
     */
    fun saveChannelOrder(context: Context, channels: List<Channel>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (channel in channels) {
            jsonArray.put(channel.id)
        }
        prefs.edit().putString(KEY_CHANNEL_ORDER, jsonArray.toString()).apply()
    }

    fun saveLastChannelId(context: Context, channelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_CHANNEL_ID, channelId).apply()
    }

    fun getLastChannelIndex(context: Context, channels: List<Channel>): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastId = prefs.getString(KEY_LAST_CHANNEL_ID, null) ?: return 0
        val idx = channels.indexOfFirst { it.id == lastId }
        return if (idx >= 0) idx else 0
    }
}
