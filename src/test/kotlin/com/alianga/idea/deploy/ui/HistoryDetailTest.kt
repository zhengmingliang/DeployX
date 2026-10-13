package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.HistoryRecord.OperationStatus
import com.alianga.idea.deploy.model.HistoryRecord.OperationType
import com.alianga.idea.deploy.ui.HistoryDetail.Mark
import com.alianga.idea.deploy.ui.HistoryDetail.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class HistoryDetailTest {
    private val deploy = HistoryRecord(type = OperationType.DEPLOY, preCommand = "a", backupDir = "/bak")

    @Test fun rerunMatchesType() {
        assertEquals(HistoryDetail.Rerun.DEPLOY, HistoryDetail.rerun(deploy))
        assertEquals(HistoryDetail.Rerun.PUSH, HistoryDetail.rerun(deploy.copy(type = OperationType.SYNC)))
        assertEquals(HistoryDetail.Rerun.PUSH, HistoryDetail.rerun(deploy.copy(type = OperationType.UPLOAD)))
        assertEquals(HistoryDetail.Rerun.PULL, HistoryDetail.rerun(deploy.copy(type = OperationType.PULL)))
        assertEquals(HistoryDetail.Rerun.NONE, HistoryDetail.rerun(deploy.copy(type = OperationType.SCRIPT)))
    }

    @Test fun rerunLabels() {
        assertEquals("history.detail.rerun.deploy", HistoryDetail.rerunLabelKey(deploy))
        assertEquals("history.detail.rerun.retry", HistoryDetail.rerunLabelKey(deploy.copy(status = OperationStatus.FAILED)))
        assertEquals("history.detail.rerun.none", HistoryDetail.rerunLabelKey(deploy.copy(type = OperationType.SCRIPT)))
    }

    @Test fun backupText() {
        assertEquals("未启用", HistoryDetail.backupText(deploy, "未启用"))
        assertEquals("—", HistoryDetail.backupText(deploy.copy(type = OperationType.PULL), "未启用"))
        assertEquals("/b.tgz", HistoryDetail.backupText(deploy.copy(backupFilePath = "/b.tgz"), "未启用"))
    }

    @Test fun stepsSuccessAndFailed() {
        val ok = HistoryDetail.steps(deploy).toMap()
        assertEquals(Mark.OK, ok[Step.PRE]); assertEquals(Mark.OK, ok[Step.BACKUP]); assertEquals(Mark.SKIP, ok[Step.POST])
        val bad = HistoryDetail.steps(deploy.copy(status = OperationStatus.FAILED)).toMap()
        assertEquals(Mark.FAIL, bad[Step.UPLOAD]); assertEquals(Mark.SKIP, bad[Step.PRE])
    }
}
