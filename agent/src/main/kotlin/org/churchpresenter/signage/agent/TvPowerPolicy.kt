package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.TvPower

enum class CecCommand { POWER_ON, STANDBY }

/**
 * When to send CEC. Only on a change of the wanted power, never repeatedly, and never for
 * [TvPower.LEAVE] — many TVs switch to the sender's input on power-on, which during a service would
 * pull the TV off ChurchPresenter's or the mixer's input.
 *
 * On the first decision after the agent starts it sends standby when the TV should be off, but never
 * power-on: a player that reboots during unmarked service hours must not grab the TV.
 */
class TvPowerPolicy {
    private var last: TvPower? = null

    fun next(wanted: TvPower): CecCommand? {
        val previous = last
        last = wanted
        return when {
            wanted == previous -> null
            wanted == TvPower.OFF -> CecCommand.STANDBY
            wanted == TvPower.ON && previous != null -> CecCommand.POWER_ON
            else -> null
        }
    }
}
