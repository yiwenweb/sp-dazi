package com.sunnypilot.toolbox.model

import com.sunnypilot.toolbox.data.SshManager

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = SshManager.DEFAULT_USER,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKeyContent: String = "",
    val savedKeyFileName: String = ""
)

