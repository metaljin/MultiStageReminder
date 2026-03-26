package com.reminder.multistage
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao interface ReminderDao {
    @Insert suspend fun insertTemplate(template: Template): Long
    @Update suspend fun updateTemplate(template: Template)
    @Delete suspend fun deleteTemplate(template: Template)
    @Insert suspend fun insertStages(stages: List<Stage>)
    @Query("DELETE FROM stages WHERE templateId = :templateId") suspend fun deleteStagesByTemplate(templateId: Long)
    @Transaction @Query("SELECT * FROM templates ORDER BY id DESC") fun getAllTemplates(): Flow<List<TemplateWithStages>>
    @Transaction @Query("SELECT * FROM templates WHERE id = :id") suspend fun getTemplateById(id: Long): TemplateWithStages?
}
