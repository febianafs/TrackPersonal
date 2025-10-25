package com.example.trackpersonal.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.trackpersonal.data.model.AboutData
import java.util.UUID

class SecurePref(context: Context) {

    companion object {
        private const val PREFS_NAME = "secure_user_prefs"

        // Login/User
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "name"
        private const val KEY_LOGIN_RESPONSE = "login_response"

        // Device
        private const val KEY_ANDROID_ID = "android_id"

        // User Meta
        private const val KEY_USER_ID = "user_id"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_NRP = "user_nrp"

        // MQTT per-install instance (optional, kalau nanti mau dipakai)
        private const val KEY_CLIENT_INSTANCE_ID = "client_instance_id"

        // Unit Info
        private const val KEY_SATUAN = "unit_satuan"
        private const val KEY_BATALYON = "unit_batalyon"
        private const val KEY_RANK = "unit_rank"
        private const val KEY_REGU = "unit_regu"

        // About
        private const val KEY_ABOUT_CONTENT = "about_content"
        private const val KEY_ABOUT_IMAGE = "about_image"
        private const val KEY_ABOUT_VERSION = "about_version"
        private const val KEY_ABOUT_DEV = "about_dev"
        private const val KEY_ABOUT_VIDEO = "about_video"

        // Settings
        private const val KEY_LOGO_URL = "logo_url"
        private const val KEY_SETTING_TITLE = "setting_title"
        private const val KEY_SETTING_LOGO = "setting_logo"
        private const val KEY_SETTING_DESC = "setting_desc"

        // Heart rate (Activity -> Service)
        private const val KEY_HEART_BPM = "heart_bpm"
        private const val KEY_HEART_TS  = "heart_ts"
    }

    private val sharedPref: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---------- GENERIC ----------
    private fun putString(key: String, value: String?) {
        sharedPref.edit().putString(key, value).apply()
    }
    private fun getString(key: String): String? = sharedPref.getString(key, null)

    private fun putLong(key: String, value: Long?) {
        sharedPref.edit().putLong(key, value ?: -1L).apply()
    }
    private fun getLongOrNull(key: String): Long? {
        val v = sharedPref.getLong(key, -1L)
        return if (v == -1L) null else v
    }

    // ---------- LOGIN DATA ----------
    fun saveUser(token: String, name: String) {
        sharedPref.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_NAME, name)
            .apply()
    }
    fun getToken(): String? = getString(KEY_TOKEN)
    fun getName(): String? = getString(KEY_NAME)

    // ---------- LOGIN RESPONSE JSON ----------
    fun saveLoginResponse(json: String) = putString(KEY_LOGIN_RESPONSE, json)
    fun getLoginResponse(): String? = getString(KEY_LOGIN_RESPONSE)

    // ---------- DEVICE ----------
    fun saveAndroidId(id: String) = putString(KEY_ANDROID_ID, id)
    fun getAndroidId(): String? = getString(KEY_ANDROID_ID)

    // ---------- USER META ----------
    fun saveUserIds(userId: Long?, clientId: Long?, profileId: Long?) {
        putLong(KEY_USER_ID, userId)
        putLong(KEY_CLIENT_ID, clientId)
        putLong(KEY_PROFILE_ID, profileId)
    }
    fun getUserId(): Long? = getLongOrNull(KEY_USER_ID)
    fun getClientId(): Long? = getLongOrNull(KEY_CLIENT_ID)
    fun getProfileId(): Long? = getLongOrNull(KEY_PROFILE_ID)

    fun saveNrp(nrp: String?) = putString(KEY_NRP, nrp)
    fun getNrp(): String? = getString(KEY_NRP)

    fun saveUserProfile(fullName: String?, username: String?, role: String?, avatarUrl: String?) {
        putString(KEY_FULL_NAME, fullName)
        putString(KEY_USERNAME, username)
        putString(KEY_ROLE, role)
        putString(KEY_AVATAR_URL, avatarUrl)
    }
    fun getFullName(): String? = getString(KEY_FULL_NAME)
    fun getUsername(): String? = getString(KEY_USERNAME)
    fun getRole(): String? = getString(KEY_ROLE)
    fun getAvatarUrl(): String? = getString(KEY_AVATAR_URL)

    // ---------- MQTT CLIENT INSTANCE (optional) ----------
    fun getOrCreateClientInstanceId(): String {
        var v = getString(KEY_CLIENT_INSTANCE_ID)
        if (v.isNullOrBlank()) {
            v = UUID.randomUUID().toString()
            putString(KEY_CLIENT_INSTANCE_ID, v)
        }
        return v
    }

    // ---------- UNIT INFO ----------
    fun saveUnitInfo(satuan: String?, batalyon: String?, rank: String?, regu: String?) {
        putString(KEY_SATUAN, satuan)
        putString(KEY_BATALYON, batalyon)
        putString(KEY_RANK, rank)
        putString(KEY_REGU, regu)
    }
    fun getSatuan(): String? = getString(KEY_SATUAN)
    fun getBatalyon(): String? = getString(KEY_BATALYON)
    fun getRank(): String? = getString(KEY_RANK)
    fun getRegu(): String? = getString(KEY_REGU)

    // ---------- ABOUT ----------
    fun saveAbout(data: AboutData?) {
        putString(KEY_ABOUT_CONTENT, data?.content)
        putString(KEY_ABOUT_IMAGE, data?.imageUrl)
        putString(KEY_ABOUT_VERSION, data?.version)
        putString(KEY_ABOUT_DEV, data?.dev)
        putString(KEY_ABOUT_VIDEO, data?.videoUrl)
    }
    fun getAboutContent() = getString(KEY_ABOUT_CONTENT)
    fun getAboutImageUrl() = getString(KEY_ABOUT_IMAGE)
    fun getAboutVersion() = getString(KEY_ABOUT_VERSION)
    fun getAboutDev() = getString(KEY_ABOUT_DEV)
    fun getAboutVideoUrl() = getString(KEY_ABOUT_VIDEO)
    fun getAboutTitle() = "About Us"

    // ---------- SETTINGS ----------
    fun saveLogoUrl(url: String?) = putString(KEY_LOGO_URL, url)
    fun getLogoUrl(): String? = getString(KEY_LOGO_URL)

    fun saveSetting(title: String?, logo: String?, desc: String?) {
        putString(KEY_SETTING_TITLE, title)
        putString(KEY_SETTING_LOGO, logo)
        putString(KEY_SETTING_DESC, desc)
    }
    fun getSettingTitle(): String? = getString(KEY_SETTING_TITLE)
    fun getSettingLogo(): String? = getString(KEY_SETTING_LOGO)
    fun getSettingDesc(): String? = getString(KEY_SETTING_DESC)

    // ---------- HEART RATE (Activity → Service) ----------
    fun saveHeartRate(bpm: Int?, tsSec: Long?) {
        sharedPref.edit()
            .putInt(KEY_HEART_BPM, bpm ?: 0)
            .putLong(KEY_HEART_TS, tsSec ?: 0L)
            .apply()
    }
    fun getHeartRateBpm(): Int? =
        if (sharedPref.contains(KEY_HEART_BPM)) sharedPref.getInt(KEY_HEART_BPM, 0) else null
    fun getHeartRateTs(): Long? =
        if (sharedPref.contains(KEY_HEART_TS)) sharedPref.getLong(KEY_HEART_TS, 0L) else null

    // ---------- CLEAR ALL ----------
    fun clear() {
        sharedPref.edit().clear().apply()
    }
}
