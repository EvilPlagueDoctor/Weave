package app.weave

import android.net.Uri

data class GroupLink(
    val groupId: String,
    val creatorRoot: String,
) {
    fun encode(): String = Uri.Builder()
        .scheme("weave")
        .authority("group")
        .appendQueryParameter("id", groupId)
        .appendQueryParameter("root", creatorRoot)
        .build()
        .toString()

    companion object {
        fun parse(raw: String): GroupLink? = runCatching {
            val uri = Uri.parse(raw.trim())
            if (!uri.scheme.equals("weave", ignoreCase = true)) return null
            if (!uri.authority.equals("group", ignoreCase = true)) return null
            val id = uri.getQueryParameter("id").orEmpty()
            val root = uri.getQueryParameter("root").orEmpty()
            if (id.isBlank() || root.isBlank()) null else GroupLink(id, root)
        }.getOrNull()

        fun forGroup(group: GroupRecord): GroupLink? =
            group.rootRecordKey.takeIf { it.isNotBlank() }?.let { GroupLink(group.groupId, it) }
    }
}
