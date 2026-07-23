/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.backup.data.smart

import android.graphics.Point
import android.os.Build

import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.database.DATABASE_VERSION
import com.buzbuz.smartautoclicker.core.database.entity.ActionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ActionType
import com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity
import com.buzbuz.smartautoclicker.core.database.entity.CompleteConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.CompleteEventEntity
import com.buzbuz.smartautoclicker.core.database.entity.CompleteScenario
import com.buzbuz.smartautoclicker.core.database.entity.ConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionType
import com.buzbuz.smartautoclicker.core.database.entity.EventEntity
import com.buzbuz.smartautoclicker.core.database.entity.EventType
import com.buzbuz.smartautoclicker.core.database.entity.ImageReferenceEntity
import com.buzbuz.smartautoclicker.core.database.entity.ScenarioEntity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ImageReferenceBackupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `multi image backup round trip preserves priorities and duplicates`() {
        val scenario = completeScenario(
            listOf(referenceEntity(0, "shared.png"), referenceEntity(1, "shared.png")),
        )
        val output = ByteArrayOutputStream()
        ScenarioSerializer().serialize(ScenarioBackup(DATABASE_VERSION, 1080, 2400, scenario), output)

        val restored = ScenarioSerializer().deserialize(ByteArrayInputStream(output.toByteArray()))
        val references = restored!!.scenario.events.single().conditions.single().imageReferences

        assertEquals(listOf(0, 1), references.map { it.priority })
        assertEquals(listOf("shared.png", "shared.png"), references.map { it.path })
    }

    @Test
    fun `backup includes and validates every related reference file`() {
        val sourceFolder = temporaryFolder.newFolder("source")
        val destinationFolder = temporaryFolder.newFolder("destination")
        File(sourceFolder, "Condition_1.png").createNewFile()
        File(sourceFolder, "Condition_2.png").createNewFile()
        val scenario = completeScenario(
            listOf(referenceEntity(0, "Condition_1.png"), referenceEntity(1, "Condition_2.png")),
        )
        val archive = createArchive(SmartBackupDataSource(sourceFolder), scenario)

        assertEquals(
            setOf("1/Condition_1.png", "1/Condition_2.png"),
            archiveEntryNames(archive).filterTo(mutableSetOf()) { it.endsWith(".png") },
        )
        val destination = extractArchive(archive, destinationFolder)
        assertNotNull(destination.validBackups.singleOrNull())
    }

    @Test
    fun `missing related reference rejects backup`() {
        val destinationFolder = temporaryFolder.newFolder("missing-destination")
        val scenario = completeScenario(
            listOf(referenceEntity(0, "Condition_1.png"), referenceEntity(1, "Condition_2.png")),
        )
        val archive = createIncompleteArchive(scenario)

        val destination = extractArchive(archive, destinationFolder)

        assertNull(destination.validBackups.singleOrNull())
        assertEquals(1, destination.failureCount)
    }

    @Test
    fun `version 26 backup imports legacy image as empty related list`() {
        val condition = imageConditionEntity(path = "legacy.png")
        val legacyJson = legacyBackupJson(condition)

        val restored = ScenarioSerializer().deserialize(ByteArrayInputStream(legacyJson.toByteArray()))
        val restoredCondition = restored!!.scenario.events.single().conditions.single()

        assertEquals("legacy.png", restoredCondition.condition.path)
        assertEquals(emptyList<ImageReferenceEntity>(), restoredCondition.imageReferences)
    }

    private fun completeScenario(references: List<ImageReferenceEntity>) = CompleteScenario(
        scenario = scenarioEntity(),
        events = listOf(
            CompleteEventEntity(
                event = eventEntity(),
                actions = listOf(pauseAction()),
                conditions = listOf(CompleteConditionEntity(imageConditionEntity(), references)),
            ),
        ),
        counters = emptyList(),
    )

    private fun createArchive(source: SmartBackupDataSource, scenario: CompleteScenario): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { source.addScenarioToZipFile(it, scenario, Point(1080, 2400)) }
        return output.toByteArray()
    }

    private fun createIncompleteArchive(scenario: CompleteScenario): ByteArray {
        val backupJson = ByteArrayOutputStream().also { output ->
            ScenarioSerializer().serialize(ScenarioBackup(DATABASE_VERSION, 1080, 2400, scenario), output)
        }.toByteArray()
        return zipEntries(mapOf("1/1.json" to backupJson, "1/Condition_1.png" to byteArrayOf(1)))
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
            }
        }
        return output.toByteArray()
    }

    private fun archiveEntryNames(archive: ByteArray): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) add(zip.nextEntry?.name ?: break)
        }
    }

    private fun extractArchive(archive: ByteArray, folder: File): SmartBackupDataSource {
        val destination = SmartBackupDataSource(folder)
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) destination.extractFromZip(zip, zip.nextEntry?.name ?: break)
        }
        destination.verifyExtractedScenarios(Point(1080, 2400))
        return destination
    }

    private fun legacyBackupJson(condition: ConditionEntity): String {
        val json = Json
        return """
            {
              "version": 26,
              "screenWidth": 1080,
              "screenHeight": 2400,
              "scenario": {
                "scenario": ${json.encodeToString(scenarioEntity())},
                "events": [{
                  "event": ${json.encodeToString(eventEntity())},
                  "actions": [${json.encodeToString(pauseAction())}],
                  "conditions": [${json.encodeToString(condition)}]
                }],
                "counters": []
              }
            }
        """.trimIndent()
    }

    private fun scenarioEntity() = ScenarioEntity(
        id = 1L,
        name = "Scenario",
        detectionQuality = 400,
    )

    private fun eventEntity() = EventEntity(
        id = 2L,
        scenarioId = 1L,
        name = "Event",
        conditionOperator = 1,
        priority = 0,
        type = EventType.IMAGE_EVENT,
    )

    private fun pauseAction() = CompleteActionEntity(
        action = ActionEntity(
            id = 3L,
            eventId = 2L,
            name = "Pause",
            type = ActionType.PAUSE,
            pauseDuration = 10L,
        ),
        intentExtras = emptyList(),
        eventsToggle = emptyList(),
    )

    private fun imageConditionEntity(path: String = "first.png") = ConditionEntity(
        id = 4L,
        eventId = 2L,
        name = "Image",
        type = ConditionType.ON_IMAGE_DETECTED,
        priority = 0,
        shouldBeDetected = true,
        path = path,
        areaLeft = 1,
        areaTop = 2,
        areaRight = 31,
        areaBottom = 42,
        threshold = 10,
        detectionType = 1,
    )

    private fun referenceEntity(priority: Int, path: String) = ImageReferenceEntity(
        conditionId = 4L,
        priority = priority,
        path = path,
        areaLeft = priority,
        areaTop = priority + 1,
        areaRight = priority + 20,
        areaBottom = priority + 21,
    )
}
