package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class ActionBarLayoutTest {
    @Test
    fun `宽度足够时左右分开`() = assertFalse(ActionBarLayout.stacked(320, 90, 110, 8))

    @Test
    fun `宽度不足时上下堆叠`() = assertTrue(ActionBarLayout.stacked(200, 90, 110, 8))

    @Test
    fun `恰好相等不堆叠`() = assertFalse(ActionBarLayout.stacked(208, 90, 110, 8))
}
