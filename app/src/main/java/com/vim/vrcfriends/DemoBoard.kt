package com.vim.vrcfriends

import com.vim.vrcfriends.api.Board
import com.vim.vrcfriends.api.FriendRow
import com.vim.vrcfriends.api.Presence

/**
 * 调试用假数据（只在 `BuildConfig.DEBUG` + `adb ... --ez demo true` 时生效）。
 *
 * 目的：真机验收「圆表排版 / 分组标题 / 长名字截断 / 头像占位 / 表冠滚动」，
 * 不必真登录 VRChat（登录要兴逸本人密码）。发布版永远拿不到这里。
 */
internal object DemoBoard {

    fun build(): Board {
        val rows = listOf(
            FriendRow(
                id = "usr_demo_1",
                name = "Aki",
                presence = Presence.JOIN_ME,
                worldLabel = "The Black Cat",
                groupLabel = "好友",
                thumbnailUrl = "https://picsum.photos/96",
            ),
            FriendRow(
                id = "usr_demo_2",
                name = "一个特别特别长的名字用来测截断",
                presence = Presence.ACTIVE,
                worldLabel = "一个同样很长很长很长的世界名字",
                groupLabel = "好友",
                thumbnailUrl = null,
            ),
            FriendRow(
                id = "usr_demo_3",
                name = "Mio",
                presence = Presence.ASK_ME,
                worldLabel = "私人世界",
                groupLabel = "群友",
                thumbnailUrl = null,
            ),
            FriendRow(
                id = "usr_demo_4",
                name = "Kuro",
                presence = Presence.BUSY,
                worldLabel = "wrld_4432ea9b-729c-46e3-8eaf-846aa0a37fdd",
                groupLabel = "群友",
                thumbnailUrl = null,
            ),
            FriendRow(
                id = "usr_demo_5",
                name = "Nagi",
                presence = Presence.ACTIVE,
                worldLabel = "Midnight Rooftop",
                groupLabel = "未分组",
                thumbnailUrl = null,
            ),
        )
        return Board(rows = rows, onlineCount = rows.size)
    }
}
