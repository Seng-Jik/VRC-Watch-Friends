package com.vim.vrcfriends.api

import com.vim.vrcfriends.json.Json
import com.vim.vrcfriends.json.JsonException
import com.vim.vrcfriends.json.JsonParser

/** 好友在 VRChat 里的在线状态（只保留圆表要显示的四档 + 离线）。 */
enum class Presence(val label: String, val argb: Long) {
    ACTIVE("在线", 0xFF3BD16F),
    JOIN_ME("可加入", 0xFF3B9CFF),
    ASK_ME("需请求", 0xFFFFC53B),
    BUSY("忙碌", 0xFFFF5A5A),
    OFFLINE("离线", 0xFF8A8A8A),
}

/**
 * 一个好友的原始快照。
 * [location] 是 VRChat 的 `location` 字段：`offline` / `private` / `wrld_xxx:12345~region(us)`。
 */
data class VrcFriend(
    val id: String,
    val displayName: String,
    val status: String,
    val statusDescription: String,
    val location: String,
    val thumbnailUrl: String?,
)

/** 收藏分组（VRChat 好友收藏的 group_0..group_3）。 */
data class GroupMeta(val tag: String, val displayName: String)

/** 一行要画在表上的东西。 */
data class FriendRow(
    val id: String,
    val name: String,
    val presence: Presence,
    val worldLabel: String,
    val groupLabel: String,
    val thumbnailUrl: String?,
    val worldId: String? = null,
) {
    val argb: Long get() = presence.argb
}

object VrcParsing {

    fun parseFriends(text: String): List<VrcFriend> {
        val root = JsonParser.parse(text)
        val items = root.asArray() ?: throw JsonException("friends: expected array")
        return items.mapNotNull { it.obj()?.let(::friend) }
    }

    private fun friend(o: Map<String, Json>): VrcFriend? {
        val id = str(o["id"]) ?: return null
        val name = str(o["displayName"]) ?: id
        return VrcFriend(
            id = id,
            displayName = name,
            status = str(o["status"]) ?: "",
            statusDescription = str(o["statusDescription"]) ?: "",
            location = str(o["location"]) ?: "offline",
            thumbnailUrl = str(o["currentAvatarThumbnailImageUrl"])
                ?: str(o["currentAvatarImageUrl"]),
        )
    }

    /** 收藏好友：`[{favoriteId, id, tags:[group_0]}]` → friendId → 分组 tag 集合。 */
    fun parseFavoriteFriends(text: String): Map<String, Set<String>> {
        val items = JsonParser.parse(text).asArray() ?: return emptyMap()
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (it in items) {
            val o = it.obj() ?: continue
            // 注意：`id` 是收藏记录 id（fvrt_…），`favoriteId` 才是好友的 usr_… id
            val fid = str(o["favoriteId"]) ?: str(o["id"]) ?: continue
            val tags = (o["tags"] as? Json.Arr)?.items?.mapNotNull { t -> t.asString() } ?: emptyList()
            out.getOrPut(fid) { LinkedHashSet() }.addAll(tags.filter { it.startsWith(GROUP_TAG_PREFIX) })
        }
        return out
    }

    /** 收藏分组定义：接受数组或 `{favoriteGroups:[...]}` 两种壳。 */
    fun parseFavoriteGroups(text: String): List<GroupMeta> {
        val root = JsonParser.parse(text)
        val items = root.asArray() ?: root.array("favoriteGroups") ?: return emptyList()
        return items.mapNotNull { it.obj()?.let(::group) }
    }

    private fun group(o: Map<String, Json>): GroupMeta? {
        val type = str(o["type"])
        if (type != null && type != "friend") return null
        val tags = (o["tags"] as? Json.Arr)?.items
            ?.mapNotNull { t -> t.asString()?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()
        val tag = tags.firstOrNull { it.startsWith(GROUP_TAG_PREFIX) } ?: return null
        val name = str(o["displayName"])?.takeIf { it.isNotBlank() }
            ?: str(o["name"])?.takeIf { it.isNotBlank() }
            ?: defaultGroupLabel(tag)
        return GroupMeta(tag, name)
    }

    /** 世界名：`{id, name, ...}`；拿不到就当未知。 */
    fun parseWorldName(text: String): String? {
        val o = JsonParser.parse(text).obj() ?: return null
        return str(o["name"])?.takeIf { it.isNotBlank() }
    }

    private fun str(v: Json?): String? = when (v) {
        is Json.Str -> v.value
        is Json.Num -> Json.formatNumber(v.value)
        else -> null
    }

    private fun str(v: Json, fallback: String): String = str(v) ?: fallback
}

const val GROUP_TAG_PREFIX = "group_"

fun defaultGroupLabel(tag: String): String {
    val n = tag.removePrefix(GROUP_TAG_PREFIX).toIntOrNull() ?: return "未分组"
    return "组${n + 1}"
}

object FriendView {

    /** 只有真的在某世界里才算在线；traveling/offline/空 都不显示。 */
    fun presenceOf(friend: VrcFriend): Presence {
        val loc = friend.location.trim()
        if (loc.isEmpty() || loc == "offline" || loc == "traveling") return Presence.OFFLINE
        return when (friend.status.lowercase()) {
            "join me" -> Presence.JOIN_ME
            "ask me" -> Presence.ASK_ME
            "busy" -> Presence.BUSY
            else -> Presence.ACTIVE
        }
    }

    /**
     * 世界显示名。
     * - 私人世界 → 「私人世界」（VRChat 不给世界 ID，猜不出来也不编）
     * - 已知世界 → 名字
     * - 未知世界 → 用 wrld_ ID 兜底（好过显示空白）
     */
    fun worldLabel(friend: VrcFriend, worldNames: Map<String, String>): String {
        val loc = friend.location.trim()
        if (loc.isEmpty() || loc == "offline" || loc == "traveling") return ""
        if (loc == "private" || loc.startsWith("private:")) return "私人世界"
        val worldId = loc.substringBefore(':')
        if (!worldId.startsWith("wrld_")) return loc.substringBefore(':')
        return worldNames[worldId]?.takeIf { it.isNotBlank() } ?: worldId
    }

    /** 好友的收藏分组标签（只取 group_N，其它标签忽略）。 */
    fun groupTag(favoriteTags: Set<String>?): String? =
        favoriteTags?.filter { it.startsWith(GROUP_TAG_PREFIX) }?.minByOrNull { tagIndex(it) }

    private fun tagIndex(tag: String): Int =
        tag.removePrefix(GROUP_TAG_PREFIX).toIntOrNull() ?: Int.MAX_VALUE

    /**
     * 组装表格：**只保留「在线 + 有分组」的好友**，按 分组顺序 → 状态优先级 → 名字 排序。
     * 未分组的好友直接丢弃（兴逸 2026-11-19：不显示也不加载）。
     */
    fun buildRows(
        friends: List<VrcFriend>,
        groups: List<GroupMeta>,
        favorites: Map<String, Set<String>>,
        worldNames: Map<String, String>,
        onlyGrouped: Boolean = true,
    ): List<FriendRow> {
        val groupOrder = HashMap<String, Int>()
        val groupNames = HashMap<String, String>()
        groups.forEachIndexed { i, g ->
            groupOrder[g.tag] = i
            groupNames[g.tag] = g.displayName
        }

        val rows = ArrayList<FriendRow>()
        for (f in friends) {
            val p = presenceOf(f)
            if (p == Presence.OFFLINE) continue
            val tag = groupTag(favorites[f.id])
            if (tag == null && onlyGrouped) continue // 只要已分组的
            val wid = f.location.trim().substringBefore(':').takeIf { it.startsWith("wrld_") }
            rows.add(
                FriendRow(
                    id = f.id,
                    name = f.displayName,
                    presence = p,
                    worldLabel = worldLabel(f, worldNames),
                    groupLabel = if (tag == null) "未分组" else (groupNames[tag] ?: defaultGroupLabel(tag)),
                    thumbnailUrl = f.thumbnailUrl,
                    worldId = wid,
                )
            )
        }

        val presenceRank = { p: Presence -> when (p) {
            Presence.ACTIVE -> 0
            Presence.JOIN_ME -> 1
            Presence.ASK_ME -> 2
            Presence.BUSY -> 3
            Presence.OFFLINE -> 4
        } }

        return rows.sortedWith(
            compareBy<FriendRow> { f ->
                val tag = groupTag(favorites[f.id])
                if (tag == null) Int.MAX_VALUE else (groupOrder[tag] ?: (1000 + tagIndex(tag)))
            }
                .thenBy { presenceRank(it.presence) }
                .thenBy { it.name.lowercase() }
        )
    }

    /** 表上要拉取世界名的 ID 列表（只算已分组+在线、去重、跳过私人/未知）。 */
    fun worldIdsToResolve(
        friends: List<VrcFriend>,
        favorites: Map<String, Set<String>>,
        onlyGrouped: Boolean = true,
    ): List<String> =
        friends.asSequence()
            .filter { presenceOf(it) != Presence.OFFLINE }
            .filter { !onlyGrouped || groupTag(favorites[it.id]) != null }
            .map { it.location.trim().substringBefore(':') }
            .filter { it.startsWith("wrld_") }
            .distinct()
            .toList()
}
