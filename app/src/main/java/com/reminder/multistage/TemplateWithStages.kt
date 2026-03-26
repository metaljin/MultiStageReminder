package com.reminder.multistage
import androidx.room.Embedded
import androidx.room.Relation

data class TemplateWithStages(
    @Embedded val template: Template,
    @Relation(parentColumn = "id", entityColumn = "templateId") val stages: List<Stage>
)
