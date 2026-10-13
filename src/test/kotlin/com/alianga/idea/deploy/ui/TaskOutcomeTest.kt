package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.DeployResult
import com.alianga.idea.deploy.model.SyncResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class TaskOutcomeTest {
    @Test fun single() {
        assertEquals(false, TaskOutcome.failed(DeployResult(success = true)))
        assertEquals(true, TaskOutcome.failed(SyncResult(success = false)))
    }

    @Test fun lists() {
        assertEquals(false, TaskOutcome.failed(listOf(SyncResult(true), SyncResult(true))))
        assertEquals(true, TaskOutcome.failed(listOf(SyncResult(true), SyncResult(false))))
        assertEquals(null, TaskOutcome.failed(emptyList<SyncResult>()))
    }

    /** 路径里含 error 也不会误判：判定只看 success 标志 */
    @Test fun unknownIsNull() {
        assertEquals(null, TaskOutcome.failed(null))
        assertEquals(null, TaskOutcome.failed("/data/error-pages ok"))
    }
}
