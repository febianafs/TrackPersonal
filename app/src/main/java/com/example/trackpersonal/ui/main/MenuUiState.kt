package com.example.trackpersonal.ui.main

data class MainUiState(
    val title: String = "--",
    val logoUrl: String? = null,
    val fullName: String = "--",
    val nrp: String = "--",
    val rank: String = "--",
    val unit: String = "--",
    val battalion: String = "--",
    val squad: String = "--",
    val avatarUrl: String? = null
)
