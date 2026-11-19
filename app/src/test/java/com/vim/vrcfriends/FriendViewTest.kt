package com.vim.vrcfriends

import com.vim.vrcfriends.api.FriendRow
import com.vim.vrcfriends.api.FriendView
import com.vim.vrcfriends.api.GroupMeta
import com.vim.vrcfriends.api.Presence
import com.vim.vrcfriends.api.VrcFriend
import com.vim.vrcfriends.api.VrcParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendViewTest {

    private fun friend(
        id: String,
        name: String = id,
        status: String = "active",
        loc: String = "wrld_abc:1",
    ) = VrcFriend(id, name, status, "", loc, null)

    // --- 状态映射 ---

    @Test
    fun offlineLocationsAreOffline() {
        for (loc in listOf("offline", "traveling", "", " ")) {
            assertEquals("loc=$loc", Presence.OFFLINE, FriendView.presenceOf(friend("a", loc = loc)))
        }
    }

    @Test
    fun statusMapsToFourOnlinePresences() {
        assertEquals(Presence.ACTIVE, FriendView.presenceOf(friend("a", status = "active")))
        assertEquals(Presence.JOIN_ME, FriendView.presenceOf(friend("a", status = "join me")))
        assertEquals(Presence.ASK_ME, FriendView.presenceOf(friend("a", status = "ask me")))
        assertEquals(Presence.BUSY, FriendView.presenceOf(friend("a", status = "busy")))
        assertEquals(Presence.ACTIVE, FriendView.presenceOf(friend("a", status = "weird-new-status")))
    }

    @Test
    fun statusIsCaseInsensitive() {
        assertEquals(Presence.JOIN_ME, FriendView.presenceOf(friend("a", status = "Join Me")))
    }

    // --- 世界名 ---

    @Test
    fun privateWorldsAreLabelled() {
        assertEquals("私人世界", FriendView.worldLabel(friend("a", loc = "private"), emptyMap()))
        assertEquals("私人世界", FriendView.worldLabel(friend("a", loc = "private:12345"), emptyMap()))
    }

    @Test
    fun knownWorldUsesNameUnknownFallsBackToId() {
        val names = mapOf("wrld_abc" to "The Great Pug")
        assertEquals("The Great Pug", FriendView.worldLabel(friend("a", loc = "wrld_abc:1"), names))
        assertEquals("wrld_zzz", FriendView.worldLabel(friend("a", loc = "wrld_zzz:1"), names))
    }

    @Test
    fun offlineHasNoWorldLabel() {
        assertEquals("", FriendView.worldLabel(friend("a", loc = "offline"), emptyMap()))
    }

    // --- 组装表格 ---

    @Test
    fun offlineFriendsAreFilteredOut() {
        val friends = listOf(friend("on"), friend("off", loc = "offline"), friend("trav", loc = "traveling"))
        val rows = FriendView.buildRows(friends, emptyList(), mapOf("on" to setOf("group_0")), emptyMap())
        assertEquals(listOf("on"), rows.map { it.id })
    }

    @Test
    fun sortedByGroupThenPresenceThenName() {
        val friends = listOf(
            friend("u1", name = "未分组A"),
            friend("g1busy", name = "B", status = "busy"),
            friend("g1active", name = "Z", status = "active"),
            friend("g0active", name = "Y", status = "active"),
        )
        val groups = listOf(GroupMeta("group_0", "老友"), GroupMeta("group_1", "同事"))
        val favs = mapOf(
            "g1busy" to setOf("group_1"),
            "g1active" to setOf("group_1"),
            "g0active" to setOf("group_0"),
        )
        val rows = FriendView.buildRows(friends, groups, favs, emptyMap())
        // 未分组的 u1 现在会被直接丢弃（兴逸 2026-11-19 要求）
        assertEquals(listOf("g0active", "g1active", "g1busy"), rows.map { it.id })
        assertEquals("老友", rows[0].groupLabel)
    }

    @Test
    fun groupOrderFollowsDefinitionNotTagNumber() {
        val friends = listOf(friend("a"), friend("b"))
        val groups = listOf(GroupMeta("group_2", "第一组定义"), GroupMeta("group_0", "第二组定义"))
        val favs = mapOf("a" to setOf("group_2"), "b" to setOf("group_0"))
        val rows = FriendView.buildRows(friends, groups, favs, emptyMap())
        assertEquals(listOf("第一组定义", "第二组定义"), rows.map { it.groupLabel })
    }

    @Test
    fun unknownGroupNameFallsBackToIndexLabel() {
        val rows = FriendView.buildRows(
            listOf(friend("a")), emptyList(), mapOf("a" to setOf("group_3")), emptyMap()
        )
        assertEquals("组4", rows[0].groupLabel)
    }

    @Test
    fun multipleTagsUsesLowestGroup() {
        assertEquals("group_1", FriendView.groupTag(setOf("group_3", "group_1", "vip")))
        assertEquals(null, FriendView.groupTag(setOf("vip")))
        assertEquals(null, FriendView.groupTag(null))
    }

    @Test
    fun worldIdsToResolveSkipsOfflineAndPrivate() {
        val friends = listOf(
            friend("a", loc = "wrld_1:1"),
            friend("b", loc = "wrld_1:2"),
            friend("c", loc = "private"),
            friend("d", loc = "offline"),
        )
        val favs = mapOf("a" to setOf("group_0"), "b" to setOf("group_0"))
        assertEquals(listOf("wrld_1"), FriendView.worldIdsToResolve(friends, favs))
        // 未分组的不参与解析
        assertTrue(FriendView.worldIdsToResolve(friends, emptyMap()).isEmpty())
    }

    @Test
    fun rowColorMatchesPresence() {
        val rows = FriendView.buildRows(
            listOf(friend("a", status = "busy")), emptyList(), mapOf("a" to setOf("group_0")), emptyMap()
        )
        val row: FriendRow = rows[0]
        assertEquals(Presence.BUSY, row.presence)
        assertEquals(0xFFFF5A5A, row.argb)
    }

    // --- 解析 ---

    @Test
    fun parsesFriendsPayload() {
        val text = """
            [{"id":"usr_1","displayName":"小明","status":"join me","statusDescription":"来",
              "location":"wrld_x:12","currentAvatarThumbnailImageUrl":"https://img/1.png"},
             {"id":"usr_2","location":"offline"}]
        """.trimIndent()
        val friends = VrcParsing.parseFriends(text)
        assertEquals(2, friends.size)
        assertEquals("小明", friends[0].displayName)
        assertEquals("https://img/1.png", friends[0].thumbnailUrl)
        assertEquals("offline", friends[1].location)
        assertEquals("usr_2", friends[1].displayName) // 缺 displayName 用 id 兜底
    }

    @Test
    fun parsesFavoriteFriendsKeepingGroupTags() {
        // 真实响应里 favoriteId 才是好友 usr_ id，id 是收藏记录 fvrt_…
        val text = """[{"favoriteId":"usr_1","id":"fvrt_1","tags":["group_0"]},
                       {"favoriteId":"usr_2","id":"fvrt_2","tags":["group_2","group_1"]}]"""
        val favs = VrcParsing.parseFavoriteFriends(text)
        assertEquals(setOf("group_0"), favs["usr_1"])
        assertEquals(setOf("group_1", "group_2"), favs["usr_2"])
    }

    @Test
    fun parsesFavoriteGroupsBothShapes() {
        val arr = """[{"name":"组名A","displayName":"老友","tags":["group_0"],"type":"friend"},
                      {"name":"notFriend","tags":["group_1"],"type":"world"}]"""
        val groups = VrcParsing.parseFavoriteGroups(arr)
        assertEquals(listOf(GroupMeta("group_0", "老友")), groups)

        val wrapped = """{"favoriteGroups":[{"name":"b","tags":["group_1"],"type":"friend"}]}"""
        assertEquals(listOf(GroupMeta("group_1", "b")), VrcParsing.parseFavoriteGroups(wrapped))
    }

    @Test
    fun parsesWorldName() {
        assertEquals("The Great Pug", VrcParsing.parseWorldName("""{"id":"wrld_x","name":"The Great Pug"}"""))
        assertEquals(null, VrcParsing.parseWorldName("""{"id":"wrld_x"}"""))
    }

    @Test
    fun blankWorldNameTreatedAsUnknown() {
        assertEquals(null, VrcParsing.parseWorldName("""{"name":"  "}"""))
    }

    @Test
    fun emptyFriendsArrayIsFine() {
        assertTrue(VrcParsing.parseFriends("[]").isEmpty())
    }
}
