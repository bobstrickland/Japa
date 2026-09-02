package org.strickland.japa.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "records")
data class Record (
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val image: String,
    val text: String
)

