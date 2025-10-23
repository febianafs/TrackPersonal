package com.example.trackpersonal.data.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName(value = "status_code", alternate = ["status"])
    val statusCode: Int?,
    @SerializedName("status_message") val statusMessage: String?,
    val success: Boolean?,
    val message: String?,
    val data: LoginData?
)

data class LoginData(
    val token: String?,
    val user: User?,
    val setting: Setting?
)

data class User(
    val id: Int?,
    val name: String?,
    val email: String?,
    val username: String?,
    @SerializedName("client_id") val clientId: Int?,
    val profile: UserProfileContainer?
)

data class UserProfileContainer(
    val token: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("expires_in") val expiresIn: Int?,
    val user: UserDetail?,
    val setting: Setting?,
    val profile: ProfileDetail?
)

data class UserDetail(
    val id: Int?,
    val name: String?,
    val email: String?,
    val username: String?,
    @SerializedName("client_id") val clientId: Int?,
    val roles: List<String> = emptyList(),
    val role: String? = null,
    val permissions: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class Setting(
    val title: String?,
    val logo: String?,
    val desc: String?
)

data class ProfileDetail(
    val id: Int?,
    @SerializedName("nrp") val nrp: String?,
    @SerializedName("full_name") val fullName: String?,
    @SerializedName("phone_number") val phoneNumber: String?,
    @SerializedName("date_of_birth") val dateOfBirth: String?,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?,
    val satuan: NamedEntity?,
    val batalyon: NamedEntity?,
    val rank: NamedEntity?,
    val regu: NamedEntity?
)

data class NamedEntity(
    val name: String?
)
