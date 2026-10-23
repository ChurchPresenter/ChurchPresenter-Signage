package org.churchpresenter.signage.agent

import org.slf4j.LoggerFactory

/**
 * The agent's one logger; journald (or the Windows service log) keeps what it writes. An error is
 * also a crash report, and a warning a breadcrumb that goes with the next one ([Diagnostics]).
 */
object Log {
    private val logger = LoggerFactory.getLogger("signage")

    fun info(message: String) = logger.info(message)

    fun warn(message: String, cause: Throwable? = null) {
        logger.warn(message, cause)
        Diagnostics.breadcrumb(message)
    }

    fun error(message: String, cause: Throwable? = null) {
        logger.error(message, cause)
        Diagnostics.error(message, cause)
    }
}
