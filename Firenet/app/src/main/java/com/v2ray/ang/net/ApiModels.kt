// ApiModels.kt
package com.v2ray.ang.data.net

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val device_id: String
)

@Serializable
data class LoginSuccess(val token: String?)

@Serializable
data class ErrorResponse(val message: String? = null, val error: String? = null)

@Serializable
data class StatusResponse(
    val username: String? = null,
    val used_traffic: Long? = null,
    val data_limit: Long? = null,
    val expire: Long? = null,
    val status: String? = null,
    val links: List<String>? = null
)
