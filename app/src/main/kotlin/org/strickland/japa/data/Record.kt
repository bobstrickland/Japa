package org.strickland.japa.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A prayer the user entered.
 *
 * [name] is unique: it is what identifies a prayer in the spinner, in a set, and when a shared
 * bundle is matched against the library, so two prayers cannot share one.
 */
@Entity(
    tableName = "records",
    indices = [Index(value = ["name"], unique = true)]
)
data class Record (
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val image: String,
    val text: String
)

