package com.alianga.idea.deploy.model

import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 旧版 JSON（缺新字段 / 字段显式为 null）反序列化后，新代码读取的字段都不为 null。
 *
 * @author 郑明亮
 */
class OldJsonCompatTest {
    private val gson = NullSafeModels.builder().create()

    @Test fun mappingMissingAndNullFields() {
        val json = """[{"name":"lib","local_dir":"/p/lib","server_id":"s","remote_dir":"/r/lib"},
            {"name":"x","local_dir":"/p","server_id":"s","remote_dir":"/r","pre_command":null,"post_command":null,"exclude":null,"backup_dir":null}]"""
        val list: List<MappingConfig> = gson.fromJson(json, object : TypeToken<List<MappingConfig>>() {}.type)
        list.forEach { m ->
            assertFalse(m.effectivePreCommandEnabled)
            assertFalse(m.effectivePostCommandEnabled)
            assertEquals("", m.preCommand); assertEquals("", m.backupDir); assertEquals("", m.unzipDest); assertEquals("", m.id)
            assertTrue(m.exclude.isEmpty())
            assertTrue(m.effectiveId.startsWith("legacy_"))
        }
    }

    @Test fun historyOldRecord() {
        val json = """[{"id":"1","timestamp":1,"type":"deploy","source_path":"/p","server_id":"s","target_path":"/r","status":"success","report_text":null}]"""
        val list: List<HistoryRecord> = gson.fromJson(json, object : TypeToken<List<HistoryRecord>>() {}.type)
        val r = list[0]
        assertEquals("", r.backupFilePath); assertEquals("", r.serverName); assertEquals("", r.reportText)
        assertTrue(r.transferredFiles.isEmpty()); assertTrue(r.remotePaths.isEmpty()); assertTrue(r.relativePaths.isEmpty())
        assertFalse(r.canRollback)
        // 新代码路径
        com.alianga.idea.deploy.ui.HistoryDetail.steps(r)
        com.alianga.idea.deploy.ui.HistoryDetail.files(r)
        com.alianga.idea.deploy.ui.HistoryFilter.matchesQuery(r, "x")
        com.alianga.idea.deploy.service.RollbackCommands.newerSuccessfulCount(r, list)
    }

    @Test fun historyNullEnums() {
        val json = """{"id":"1","type":null,"status":null}"""
        val r = gson.fromJson(json, HistoryRecord::class.java)
        assertEquals(HistoryRecord.OperationType.SYNC, r.type)
        assertEquals(HistoryRecord.OperationStatus.SUCCESS, r.status)
        r.summary
    }

    @Test fun scriptOldFormat() {
        val json = """[{"id":"a","name":"ls","command":"ls","tags":null,"params":[{"name":"p","label":null}],"server_id":null,"last_run_status":null}]"""
        val list: List<ScriptConfig> = gson.fromJson(json, object : TypeToken<List<ScriptConfig>>() {}.type)
        val s = list[0]
        assertEquals("", s.serverId); assertEquals("", s.lastRunStatus); assertTrue(s.tags.isEmpty())
        assertEquals("p", s.params[0].displayLabel)
        assertEquals(1, com.alianga.idea.deploy.ui.script.ScriptCard.commandLines(s.command))
        assertFalse(com.alianga.idea.deploy.ui.script.ScriptCard.runsDirectly(s))
    }

    @Test fun rollbackOldFormat() {
        val r = gson.fromJson("""{"id":"1","status":"success"}""", RollbackRecord::class.java)
        assertEquals("", r.remoteTargetPath); assertTrue(r.rolledBackFiles.isEmpty())
    }
}
