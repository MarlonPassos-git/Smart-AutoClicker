package com.buzbuz.smartautoclicker.core.script.packages

import java.nio.channels.OverlappingFileLockException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionStateIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(completed: Boolean = false) = buildJsonObject {
        put("formatVersion", 1); put("revision", "immutable-revision")
        put("answers", buildJsonObject { put("limit", 10) })
        put("state", buildJsonObject { put("version", 1); put("confirmed", 3) })
        put("completed", completed)
    }

    @Test fun reopenedRepositoryKeepsLastAtomicCheckpointAndIsolatesPackages() {
        val root = temporary.newFolder()
        val repository = ScriptExecutionStateRepository(root)
        repository.acquire("farm").use { it.write(record()) }
        root.resolve("farm.pending").writeText("interrupted next write")
        val reopened = ScriptExecutionStateRepository(root)
        assertEquals(record(), reopened.read("farm"))
        assertNull(reopened.read("another-farm"))
        reopened.acquire("farm").use { it.write(record(completed = true)) }
        assertEquals(record(true), repository.read("farm"))
    }

    @Test fun configurationSurvivesNewRoundsAndFailedReplacementWithoutEnteringProgressOrAnotherPackage() {
        val root = temporary.newFolder()
        val repository = ScriptExecutionStateRepository(root)
        val configuration = buildJsonObject { put("formatVersion", 1); put("items", JsonArray(emptyList())) }
        repository.acquire("farm").use { owner ->
            owner.write(record())
            owner.writeConfiguration(configuration)
            owner.write(record(completed = true))
            assertThrows(IllegalArgumentException::class.java) {
                owner.writeConfiguration(buildJsonObject { put("oversized", "x".repeat(262144)) })
            }
        }
        root.resolve("configuration/farm.pending").writeText("incomplete write")
        val reopened = ScriptExecutionStateRepository(root)
        assertEquals(configuration, reopened.readConfiguration("farm"))
        assertEquals(record(true), reopened.read("farm"))
        assertNull(reopened.readConfiguration("other"))
    }

    @Test fun anotherExecutorCannotOwnTheSameRoundUntilReleased() {
        val repository = ScriptExecutionStateRepository(temporary.newFolder())
        val owner = repository.acquire("farm")
        assertThrows(OverlappingFileLockException::class.java) { repository.acquire("farm") }
        owner.close()
        repository.acquire("farm").use { it.write(record()) }
        assertEquals(record(), repository.read("farm"))
    }

    @Test fun malformedOrOversizedRecordsRemainIntactAndRequireExplicitReplacement() {
        val root = temporary.newFolder()
        val repository = ScriptExecutionStateRepository(root)
        for (invalid in listOf("broken", "{}", "x".repeat(262145))) {
            root.resolve("farm.json").writeText(invalid)
            assertThrows(IllegalArgumentException::class.java) { repository.read("farm") }
            assertEquals(invalid, root.resolve("farm.json").readText())
        }
        repository.acquire("farm").use { it.write(record()) }
        assertEquals(record(), repository.read("farm"))
    }

    @Test fun invalidWritesCannotReplaceTheLastCheckpointOrEscapeThePrivateDirectory() {
        val repository = ScriptExecutionStateRepository(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { repository.acquire("../escape") }
        repository.acquire("farm").use { owner ->
            owner.write(record())
            assertThrows(IllegalArgumentException::class.java) {
                owner.write(buildJsonObject { put("oversized", "x".repeat(262144)) })
            }
            assertThrows(IllegalArgumentException::class.java) { owner.write(buildJsonObject {}) }
            assertEquals(record(), repository.read("farm"))
        }
    }
}
