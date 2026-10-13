package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.ui.script.ScriptCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class ScriptCardTest {
    private val s = ScriptConfig(id = "a", name = "重启", group = "运维", command = "cd /app\n\nsh restart.sh\n",
        runCount = 5, lastRunAt = 9, lastRunStatus = "success")

    @Test fun lines() = assertEquals(2, ScriptCard.commandLines(s.command))

    @Test fun runsDirectly() {
        assertFalse(ScriptCard.runsDirectly(s))
        assertTrue(ScriptCard.runsDirectly(s.copy(serverId = "164")))
    }

    @Test fun duplicateZeroesStats() {
        val d = ScriptCard.duplicate(s, "b", " 副本", 100)
        assertEquals("重启 副本", d.name); assertEquals("b", d.id)
        assertEquals(0, d.runCount); assertEquals(0L, d.lastRunAt); assertEquals("", d.lastRunStatus)
        assertEquals(s.command, d.command)
    }

    @Test fun params() {
        assertFalse(ScriptCard.hasParams(s))
        assertTrue(ScriptCard.hasParams(s.copy(params = listOf(ScriptParam(name = "v")))))
    }

    @Test fun lineNumbers() = assertEquals("1\n2\n3", ScriptCard.lineNumbers("a\nb\nc"))
}
