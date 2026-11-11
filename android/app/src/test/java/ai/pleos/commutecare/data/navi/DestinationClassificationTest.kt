/**
 * @file DestinationClassificationTest.kt
 * @brief 병원·마트·맛집을 출근이나 자택으로 오인하지 않는지 검증한다.
 */
package ai.pleos.commutecare.data.navi

import ai.pleos.commutecare.domain.CommuteType
import org.junit.Assert.*
import org.junit.Test

class DestinationClassificationTest {
    /** @brief 명시적으로 알려진 목적지 키워드만 통근/여행으로 분류한다. @return Unit @throws 없음 */
    @Test fun classifiesKnownDestinations() {
        assertEquals(CommuteType.MORNING, PleosNaviManager.classifyDestination("현대자동차 양재 본사", "서울"))
        assertEquals(CommuteType.EVENING, PleosNaviManager.classifyDestination("자택", "서울"))
        assertEquals(CommuteType.TRAVEL, PleosNaviManager.classifyDestination("델피노 리조트", "강원"))
    }

    /** @brief 알 수 없는 목적지는 일반 주행으로 유지한다. @return Unit @throws 없음 */
    @Test fun leavesOrdinaryDestinationsUnclassified() {
        listOf("병원", "마트", "맛집", "강릉 병원", "").forEach { assertNull(PleosNaviManager.classifyDestination(it, "서울")) }
    }
}
