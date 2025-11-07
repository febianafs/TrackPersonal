package com.example.trackpersonal.storage

import android.content.Context
import android.content.SharedPreferences

class SessionStore(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("session_store", Context.MODE_PRIVATE)

    fun saveUser(id: String?, username: String?) {
        sp.edit()
            .putString("uid", id)
            .putString("uname", username)
            .apply()
    }

    fun userId(): String? = sp.getString("uid", null)
    fun username(): String? = sp.getString("uname", null)

    fun clear() {
        sp.edit().clear().apply()
    }
}
