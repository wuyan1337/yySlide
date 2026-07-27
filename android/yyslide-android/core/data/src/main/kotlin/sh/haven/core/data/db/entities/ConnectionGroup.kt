package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "connection_groups")
data class ConnectionGroup(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val colorTag: Int = 0,
    val sortOrder: Int = 0,
    val collapsed: Boolean = false,
)
