package org.churchpresenter.signage.agent

import org.churchpresenter.signage.core.TvPower
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPowerPolicyTest {
    private fun run(vararg wanted: TvPower): List<CecCommand?> =
        TvPowerPolicy().let { policy -> wanted.map(policy::next) }

    @Test
    fun commandsAreSentOnlyOnAChange() {
        assertEquals(
            listOf(null, null, CecCommand.STANDBY, null, CecCommand.POWER_ON),
            run(TvPower.ON, TvPower.ON, TvPower.OFF, TvPower.OFF, TvPower.ON),
        )
    }

    @Test
    fun startingUpNeverPowersTheTvOnButDoesTurnItOff() {
        assertEquals(listOf(null), run(TvPower.ON))
        assertEquals(listOf(CecCommand.STANDBY), run(TvPower.OFF))
        assertEquals(listOf(null), run(TvPower.LEAVE))
    }

    @Test
    fun serviceHoursSendNothingAndTheirEndBringsTheTvBack() {
        assertEquals(
            listOf(null, null, CecCommand.POWER_ON),
            run(TvPower.ON, TvPower.LEAVE, TvPower.ON),
        )
    }
}
