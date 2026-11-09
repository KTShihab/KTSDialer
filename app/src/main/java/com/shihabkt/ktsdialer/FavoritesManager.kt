package com.shihabkt.ktsdialer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model representing a favorite contact.
 */
data class FavoriteContact(
    val name: String,
    val number: String,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Storage manager for favorite contacts backed by SharedPreferences and JSON.
 * Usable by DialFragment and FavoriteFragment.
 */
class FavoritesManager(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Adds a contact to favorites.
     * @return true if added, false if already in favorites.
     */
    fun addFavorite(name: String, number: String): Boolean {
        val currentList = getFavorites().toMutableList()
        val cleanedNumber = number.replace(" ", "").trim()

        if (currentList.any { it.number.replace(" ", "").trim() == cleanedNumber }) {
            return false
        }

        currentList.add(FavoriteContact(name = name, number = number))
        saveFavorites(currentList)
        return true
    }

    /**
     * Removes a contact by number from favorites.
     */
    fun removeFavorite(number: String): Boolean {
        val currentList = getFavorites().toMutableList()
        val cleanedNumber = number.replace(" ", "").trim()
        val removed = currentList.removeAll { it.number.replace(" ", "").trim() == cleanedNumber }
        if (removed) {
            saveFavorites(currentList)
        }
        return removed
    }

    /**
     * Checks if a number is already in favorites.
     */
    fun isFavorite(number: String): Boolean {
        val cleanedNumber = number.replace(" ", "").trim()
        return getFavorites().any { it.number.replace(" ", "").trim() == cleanedNumber }
    }

    /**
     * Retrieves all saved favorite contacts.
     */
    fun getFavorites(): List<FavoriteContact> {
        val jsonString = preferences.getString(KEY_FAVORITES, null) ?: return emptyList()
        val list = mutableListOf<FavoriteContact>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    FavoriteContact(
                        name = obj.optString("name", ""),
                        number = obj.optString("number", ""),
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (ignored: Exception) {
            // Return empty or partial list on parse error
        }
        return list
    }

    private fun saveFavorites(list: List<FavoriteContact>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("name", item.name)
                put("number", item.number)
                put("addedAt", item.addedAt)
            }
            jsonArray.put(obj)
        }
        preferences.edit().putString(KEY_FAVORITES, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kts_dialer_favorites_prefs"
        private const val KEY_FAVORITES = "key_favorite_contacts"
    }
}
