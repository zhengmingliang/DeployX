package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class TargetCandidatesTest {
    @Test
    fun `单个选中项返回其全部服务器且保持顺序`() =
        assertEquals(listOf("233", "164"), TargetCandidates.coveringAll(listOf(listOf("233", "164"))))

    @Test
    fun `多选取交集`() =
        assertEquals(listOf("233"), TargetCandidates.coveringAll(listOf(listOf("233", "164"), listOf("233"))))

    @Test
    fun `无交集返回空`() =
        assertEquals(emptyList<String>(), TargetCandidates.coveringAll(listOf(listOf("164"), listOf("233"))))

    @Test
    fun `无选中项返回空`() = assertEquals(emptyList<String>(), TargetCandidates.coveringAll(emptyList()))
}
