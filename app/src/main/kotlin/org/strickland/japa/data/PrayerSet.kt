package org.strickland.japa.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named, ordered collection of prayers — "Sunday Assembly", say. */
@Entity(tableName = "prayer_sets")
data class PrayerSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/**
 * Membership of a prayer in a set.
 *
 * Order lives here rather than on [Record] because the same prayer belongs to more than one set —
 * a Shanti mantra can close both a weekly assembly and a festival — at a different position in each.
 */
@Entity(
    tableName = "set_members",
    primaryKeys = ["setId", "recordId"],
    foreignKeys = [
        ForeignKey(
            entity = PrayerSet::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Record::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId"), Index(value = ["setId", "position"])]
)
data class SetMember(
    val setId: Long,
    val recordId: Long,
    val position: Int
)
