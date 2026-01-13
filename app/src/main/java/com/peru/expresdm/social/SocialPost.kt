package com.peru.expresdm.social

data class SocialPost(
    val user: String,
    val content: String,
    val time: String,
    val imageRes: Int? = null
)
