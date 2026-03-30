package com.reminder.multistage
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stages",
    foreignKeys = [ForeignKey(entity = Template::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("templateId")]
)
data class Stage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val orderIndex: Int,
    val durationMinutes: Int,
    val ringtoneUri: String,
	val ringtoneName: String,
    val maxPlaySeconds: Int = 0
)
