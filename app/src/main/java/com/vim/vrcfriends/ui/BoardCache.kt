package com.vim.vrcfriends.ui

import android.content.Context
import com.vim.vrcfriends.api.Board
import com.vim.vrcfriends.api.FriendRow
import com.vim.vrcfriends.api.Presence
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上次的好友列表快照：冷启动先画这个（几十毫秒），再去拉网络。
 * 只缓存展示所需字段，不含任何凭证。
 */
class BoardCache(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vrc-board-cache", Context.MODE_PRIVATE)

    fun save(board: Board) {
        val arr = JSONArray()
        for (r in board.rows) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("presence", r.presence.name)
                put("world", r.worldLabel)
                put("group", r.groupLabel)
                put("thumb", r.thumbnailUrl ?: "")
                put("worldId", r.worldId ?: "")
            })
        }
        prefs.edit()
            .putString(KEY_ROWS, arr.toString())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): Board? {
        val raw = prefs.getString(KEY_ROWS, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val rows = ArrayList<FriendRow>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rows.add(
                    FriendRow(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        presence = runCatching { Presence.valueOf(o.getString("presence")) }
                            .getOrDefault(Presence.ACTIVE),
                        worldLabel = o.optString("world"),
                        groupLabel = o.optString("group"),
                        thumbnailUrl = o.optString("thumb").takeIf { it.isNotBlank() },
                        worldId = o.optString("worldId").takeIf { it.isNotBlank() },
                    )
                )
            }
            Board(rows = rows, onlineCount = rows.size)
        }.getOrNull()
    }

    fun ageMs(): Long =
        if (prefs.getLong(KEY_AT, 0L) == 0L) Long.MAX_VALUE else System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L)

    private companion object {
        const val KEY_ROWS = "rows"
        const val KEY_AT = "at"
    }
}
