package com.example.trackpersonal.ui.login

import com.example.trackpersonal.data.model.LoginResponse
import com.example.trackpersonal.utils.SecurePref
import com.google.gson.Gson

/**
 * Persist semua data penting dari LoginResponse ke SecurePref.
 * @return true jika token valid & penyimpanan berhasil.
 */
fun LoginResponse?.persistTo(pref: SecurePref): Boolean {
    val data = this?.data ?: return false
    val user = data.user ?: return false

    val token = data.token.orEmpty()
    if (token.isEmpty()) return false

    // Nama tampilan: name → username → fullName (profile)
    val displayName = user.name
        ?: user.username
        ?: user.profile?.profile?.fullName
        ?: "User"
    pref.saveUser(token, displayName)

    // ID & meta (model kamu sudah camelCase)
    val userId = user.id
    val clientId = user.clientId
    val profileId = user.profile?.profile?.id
    pref.saveUserIds(userId?.toLong(), clientId?.toLong(), profileId?.toLong())

    val fullName = user.profile?.profile?.fullName ?: user.name
    val username = user.username ?: user.profile?.user?.username
    val role = user.profile?.user?.role
    val avatarUrl = user.profile?.profile?.avatarUrl
    pref.saveUserProfile(fullName, username, role, avatarUrl)

    // Unit info
    val satuan = user.profile?.profile?.satuan?.name
    val batalyon = user.profile?.profile?.batalyon?.name
    val pangkat = user.profile?.profile?.rank?.name
    val regu = user.profile?.profile?.regu?.name
    pref.saveUnitInfo(satuan, batalyon, pangkat, regu)

    // Setting: utamakan nested; fallback top-level
    val nested = user.profile?.setting
    val top = data.setting
    val settingTitle = nested?.title ?: top?.title
    val settingLogo  = nested?.logo  ?: top?.logo
    val settingDesc  = nested?.desc  ?: top?.desc
    pref.saveSetting(settingTitle, settingLogo, settingDesc)

    // Simpan snapshot JSON (opsional)
    pref.saveLoginResponse(Gson().toJson(this))

    return true
}
