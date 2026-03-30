package com.reminder.multistage
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val totalCycles: Int,
	val isVibrateEnabled: Boolean = true, 
    val isSoundEnabled: Boolean = true,  
    val ringtoneDurationSeconds: Int = 10 
)
