package org.churchpresenter.signage.agent

import org.slf4j.LoggerFactory

/** The agent's one logger; journald (or the Windows service log) keeps what it writes. */
object Log {
    private val logger = LoggerFactory.getLogger("signage")

    fun info(message: String) = logger.info(message)

    fun warn(message: String, cause: Throwable? = null) = logger.warn(message, cause)

    fun error(message: String, cause: Throwable? = null) = logger.error(message, cause)
}
