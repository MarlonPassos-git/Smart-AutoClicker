package com.buzbuz.smartautoclicker.core.database.migrations

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.entity.*
import com.buzbuz.smartautoclicker.core.database.serialization.DeserializerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CounterQuestionPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ClickDatabase::class.java)
    private val question = CounterQuestionEntity("Recurso", "Escolha a prioridade", 2, CounterQuestionType.CHOICE,
        options = listOf(CounterQuestionOptionEntity("Ouro", 10.0), CounterQuestionOptionEntity("Elixir", 20.0)),
        visibleWhen = CounterQuestionVisibilityEntity("enabled", 1.0))

    @Test fun `existing version 29 questions without visibility metadata remain unconditional`() {
        val question = CounterQuestionConverters().decode("""{"title":"Meta","minimum":1.0,"maximum":100.0}""")!!
        assertNull(question.visibleWhen)
        assertEquals("Meta", question.title)
    }

    @Test fun `migration preserves old counters with no questions`() {
        val path = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("counter-questions").path
        helper.createDatabase(path, 28).use { db ->
            db.execSQL("INSERT INTO scenario_table (id, name, detection_quality) VALUES (1, 'Farm', 1)")
            db.execSQL("INSERT INTO counters_table VALUES ('target', 1, 50.0)")
        }
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ClickDatabase::class.java, path).build()
        try {
            db.openHelper.writableDatabase.query("SELECT startingValue, startQuestion FROM counters_table").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(50.0, cursor.getDouble(0), 0.0); assertTrue(cursor.isNull(1))
            }
        } finally { db.close() }
    }

    @Test fun `room and portable backup retain metadata for copied scenarios`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ClickDatabase::class.java).build()
        try {
            db.scenarioDao().add(ScenarioEntity(1, "Original", 1))
            db.scenarioDao().add(ScenarioEntity(2, "Copy", 1))
            val original = CountersEntity("priority", 1, 10.0, question)
            db.countersDao().upsertCounter(original)
            db.countersDao().upsertCounter(original.copy(scenarioId = 2))
            assertEquals(original, db.countersDao().getScenarioCounters(1).single())
            val scenario = db.scenarioDao().getCompleteScenario(2)!!
            val encoded = Json.parseToJsonElement(Json.encodeToString(scenario)).jsonObject
            assertEquals(scenario, DeserializerFactory.create(29)!!.deserializeCompleteScenario(encoded))
            assertEquals(question, scenario.counters.single().startQuestion)
            db.countersDao().deleteCounter("priority", 1)
            assertEquals(question, db.countersDao().getScenarioCounters(2).single().startQuestion)
        } finally { db.close() }
    }

    @Test fun `version 28 native backups remain readable with legacy default`() {
        val scenario = CompleteScenario(ScenarioEntity(1, "Legacy", 1), emptyList(), listOf(CountersEntity("x", 1, 5.0)))
        val encoded = Json.parseToJsonElement(Json.encodeToString(scenario)).jsonObject
        assertEquals(scenario, DeserializerFactory.create(28)!!.deserializeCompleteScenario(encoded))
    }
}
