package com.vim.vrcfriends

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import com.vim.vrcfriends.api.Board
import com.vim.vrcfriends.api.FriendRow
import com.vim.vrcfriends.api.HttpTransport
import com.vim.vrcfriends.api.VrcApi
import com.vim.vrcfriends.api.VrcException
import com.vim.vrcfriends.api.VrcSession
import com.vim.vrcfriends.auth.AuthController
import com.vim.vrcfriends.auth.CredentialStore
import com.vim.vrcfriends.image.AvatarLoader
import com.vim.vrcfriends.image.AvatarStore
import com.vim.vrcfriends.ui.RefreshBlock
import com.vim.vrcfriends.ui.RefreshGate
import com.vim.vrcfriends.ui.BoardCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 圆表主界面：只有在线好友。
 *
 * 登录（2026-11-19 兴逸定的流程）：
 *  1. 有 cookie → 直接用；失效则清掉往下走。
 *  2. 有保存的用户名密码 → 自动登录；要 2FA 就弹验证码输入。
 *  3. 都没有 → 让用户输入；登录成功后保存，VRC 清会话时可自动重登。
 */
object RotaryBridge {
    /** 由 FriendList 注册；Activity 层收到滚轮/方向键时调用。 */
    var onScroll: ((Float) -> Unit)? = null
}

class MainActivity : ComponentActivity() {

    private lateinit var loader: AvatarLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loader = AvatarLoader(AvatarStore(File(cacheDir, "avatars")))
        val session = SessionStore.load(this)
        val prefs = UiPrefs(this)
        val creds = CredentialStore(this)
        // 调试假数据：仅 debuggable 包 + `am start ... --ez demo true` 时生效
        val demo = if (isDebuggable() && intent?.getBooleanExtra("demo", false) == true) {
            DemoBoard.build()
        } else {
            null
        }
        setContent { App(session, loader, prefs, creds, demo) }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_SCROLL) {
            val vs = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            val sc = ev.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            android.util.Log.d("vrc-rotary", "generic vscroll=$vs scroll=$sc")
            val v = if (sc != 0f) sc else vs
            if (v != 0f) RotaryBridge.onScroll?.invoke(-v * 2.1f)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        android.util.Log.d("vrc-rotary", "keydown=$keyCode")
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { RotaryBridge.onScroll?.invoke(60f); return true }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { RotaryBridge.onScroll?.invoke(-60f); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        loader.shutdown()
        super.onDestroy()
    }
}

private sealed class UiState {
    data object Loading : UiState()
    data class Ready(val board: Board) : UiState()
    data class Failed(val message: String) : UiState()
}

/** 表上的一行：分组标题 或 好友。 */
private sealed class ListItem {
    data class Header(val label: String) : ListItem()
    data class Friend(val row: FriendRow) : ListItem()
}

@Composable
private fun App(
    session: VrcSession,
    loader: AvatarLoader,
    prefs: UiPrefs,
    credStore: CredentialStore,
    demo: Board? = null,
) {
    val controller = remember { AuthController(HttpTransport(session), session, credStore) }
    // null = 正在判定；demo 直接进 Ready
    var auth by remember {
        mutableStateOf<AuthController.State?>(if (demo != null) AuthController.State.Ready else null)
    }
    var state by remember {
        mutableStateOf<UiState>(if (demo != null) UiState.Ready(demo) else UiState.Loading)
    }
    var reloadKey by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var lastRefreshAt by remember { mutableLongStateOf(0L) }
    var avatarsOn by remember { mutableStateOf(prefs.avatarsEnabled) }
    val gate = remember { RefreshGate() }
    val scope = rememberCoroutineScope()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val boardCache = remember { BoardCache(ctx) }

    // 登录成功后立刻把 cookie 落盘：否则每次冷启动都要重新过邮件验证码
    LaunchedEffect(auth) {
        if (auth == AuthController.State.Ready) SessionStore.save(ctx, session)
    }

    // 启动：cookie → 保存的账号 → 要求输入
    LaunchedEffect(Unit) {
        if (demo == null) auth = withContext(Dispatchers.IO) { controller.start() }
    }

    // 登录完成后拉一次；只在 Ready 时联网
    LaunchedEffect(auth, reloadKey) {
        if (demo != null) return@LaunchedEffect // 假数据不联网
        if (auth != AuthController.State.Ready) return@LaunchedEffect
        val blocked = gate.tryStart(System.currentTimeMillis())
        if (blocked != null) {
            hint = when (blocked) {
                RefreshBlock.IN_FLIGHT -> "正在刷新…"
                RefreshBlock.TOO_SOON -> "刚刷过，等 ${gate.waitSeconds(System.currentTimeMillis())}s"
                RefreshBlock.COOLDOWN -> "失败后等 ${gate.waitSeconds(System.currentTimeMillis())}s 再试"
            }
            return@LaunchedEffect
        }
        hint = null
        refreshing = true
        // 冷启动先把上次的列表画出来（几十毫秒），网络回来再覆盖
        val cached = if (reloadKey == 0) boardCache.load() else null
        if (cached != null && cached.rows.isNotEmpty()) {
            state = UiState.Ready(cached)
        } else {
            state = UiState.Loading
        }
        var ok = false
        try {
            val api = VrcApi(HttpTransport(session), session)
            // 先出列表（世界名用缓存/ID 兜底），再后台补世界名
            val board = withContext(Dispatchers.IO) { api.fetchBoard() }
            state = UiState.Ready(board)
            boardCache.save(board)
            lastRefreshAt = System.currentTimeMillis()
            ok = true
            SessionStore.save(ctx, session)
            if (board.pendingWorlds.isNotEmpty()) {
                val names = withContext(Dispatchers.IO) { api.resolveWorlds(board.pendingWorlds) }
                val merged = board.withWorlds(names)
                state = UiState.Ready(merged)
                boardCache.save(merged)
            }
        } catch (e: VrcException) {
            if (e.code == 401) {
                // 会话被服务端清掉：走一次自动重登
                val next = withContext(Dispatchers.IO) { controller.onUnauthorized() }
                auth = next
                state = UiState.Loading
            } else {
                state = UiState.Failed(e.message ?: "HTTP ${e.code}")
            }
        } catch (e: Exception) {
            // 有缓存就继续显示上次列表，只在标题标一下，别整屏报错
            val current = state
            if (current is UiState.Ready && current.board.rows.isNotEmpty()) {
                hint = "网络异常 · 上次数据"
            } else {
                state = UiState.Failed(friendlyNetwork(e))
            }
        } finally {
            refreshing = false
            gate.finish(System.currentTimeMillis(), ok)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 圆屏安全区：h34 + v34 dp ⇒ 内容矩形内接于 466 px 圆（角落不再被边框切）
            .padding(horizontal = 0.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val a = auth) {
            null -> Text("检查登录…", color = Color(0xFFBDBDBD), fontSize = 12.sp)

            is AuthController.State.NeedLogin -> LoginScreen(
                initialUser = controller.savedUsername() ?: "",
                error = null,
                onSubmit = { u, p ->
                    scope.launch {
                        auth = withContext(Dispatchers.IO) { controller.login(u, p) }
                    }
                }
            )

            is AuthController.State.NeedTotp -> TotpScreen(
                hint = a.hint,
                onSubmit = { code ->
                    scope.launch {
                        auth = withContext(Dispatchers.IO) { controller.submitTotp(code) }
                    }
                }
            )

            is AuthController.State.Error -> ErrorScreen(
                message = a.message,
                onRetry = {
                    if (a.retryLogin) {
                        auth = AuthController.State.NeedLogin
                    } else {
                        reloadKey++
                    }
                }
            )

            AuthController.State.Ready -> when (val s = state) {
                UiState.Loading -> Text("加载中…", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                is UiState.Failed -> ErrorScreen(message = s.message, onRetry = { reloadKey++ })
                is UiState.Ready -> FriendList(
                    board = s.board,
                    loader = loader,
                    avatarsOn = avatarsOn,
                    hint = hint,
                    refreshing = refreshing,
                    lastRefreshAt = lastRefreshAt,
                    onToggleAvatars = {
                        avatarsOn = !avatarsOn
                        prefs.avatarsEnabled = avatarsOn
                    },
                    onRefresh = { reloadKey++ },
                )
            }
        }
    }
}


/** 把 SSL/EOF/超时翻成人话。 */
private fun friendlyNetwork(e: Exception): String {
    val m = (e.message ?: "").lowercase()
    return when {
        m.contains("ssl") || m.contains("connection closed") || m.contains("eof") ->
            "网络连接失败（手表 Wi-Fi / 蓝牙代理）"
        m.contains("timed out") || m.contains("timeout") -> "网络超时，稍后重试"
        else -> "连接失败：" + (e.message ?: e.javaClass.simpleName)
    }
}

@Composable
private fun LoginScreen(
    initialUser: String,
    error: String?,
    onSubmit: (String, String) -> Unit,
) {
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录 VRChat", color = Color(0xFFE0E0E0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            singleLine = true,
            label = { Text("用户名 / 邮箱", fontSize = 9.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            singleLine = true,
            label = { Text("密码", fontSize = 9.sp) },
            visualTransformation = PasswordVisualTransformation(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(error, color = Color(0xFFFF6B6B), fontSize = 10.sp, maxLines = 2)
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { onSubmit(user.trim(), pass) }) {
            Text("登录", fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "登录一次后会记住账号密码；\nVRC 清掉登录态时会自动重登",
            color = Color(0xFF7A7A7A),
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TotpScreen(hint: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("两步验证", color = Color(0xFFE0E0E0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(hint, color = Color(0xFF9E9E9E), fontSize = 9.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
            singleLine = true,
            label = { Text("6 位验证码", fontSize = 9.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = { onSubmit(code) }, enabled = code.length >= 6) {
            Text("验证", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            message,
            color = Color(0xFFFF5A5A),
            fontSize = 11.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = onRetry) { Text("重试", fontSize = 11.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendList(
    board: Board,
    loader: AvatarLoader,
    avatarsOn: Boolean,
    hint: String?,
    refreshing: Boolean,
    lastRefreshAt: Long,
    onToggleAvatars: () -> Unit,
    onRefresh: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // 表冠每格给的位移很大，直接滚就是“一格一格”。这里累积成一个目标位移，
    // 再用逐帧小步长（≤36px/帧）吃掉，连续滚动就顺了。
    var pendingScroll by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun pushScrollImpl(px: Float) {
        android.util.Log.d("vrc-rotary", "push px=" + px + " pending=" + pendingScroll)
        pendingScroll += px
        if (scrollJob?.isActive == true) return
        scrollJob = scope.launch {
            while (kotlin.math.abs(pendingScroll) > 0.5f) {
                // 比例步长：一口吃 20%，剩余越多走得越快，接近目标时自然减速
                val step = (pendingScroll * 0.2f).let {
                    if (kotlin.math.abs(it) < 1f) pendingScroll else it
                }
                pendingScroll -= step
                listState.scrollBy(step)
                androidx.compose.runtime.withFrameNanos { }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        RotaryBridge.onScroll = { px -> pushScrollImpl(px) }
        onDispose { RotaryBridge.onScroll = null }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val items = remember(board) { buildList {
        var lastGroup: String? = null
        for (r in board.rows) {
            if (r.groupLabel != lastGroup) {
                add(ListItem.Header(r.groupLabel))
                lastGroup = r.groupLabel
            }
            add(ListItem.Friend(r))
        }
    } }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            ,
        // 上下留白：第一条能滚到表盘中间；标题本身也是列表的一部分
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        item(key = "__status") {
            RefreshChip(
                refreshing = refreshing,
                lastRefreshAt = lastRefreshAt,
                onRefresh = onRefresh,
            )
        }

        item(key = "__header") {
            HeaderRow(
                board = board,
                hint = hint,
                onToggleAvatars = onToggleAvatars,
                onRefresh = onRefresh,
            )
        }

        if (board.isEmpty) {
            item(key = "__empty") {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (board.groupedFilter) "没有已分组的好友在线" else "没有在线好友",
                        color = Color(0xFF8A8A8A),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        items(items) { item ->
            when (item) {
                is ListItem.Header -> Text(
                    item.label,
                    color = Color(0xFF5F8FBF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp, bottom = 1.dp),
                )
                is ListItem.Friend -> RowItem(item.row, loader, avatarsOn, listState)
            }
        }

        item(key = "__tail") {
            Spacer(Modifier.height(40.dp))
        }
    }
}


@Composable
private fun RefreshChip(
    refreshing: Boolean,
    lastRefreshAt: Long,
    onRefresh: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val label = when {
        refreshing -> "刷新中…"
        lastRefreshAt == 0L -> "尚未刷新"
        now - lastRefreshAt < 60_000L -> "刚刚刷新"
        now - lastRefreshAt < 3_600_000L -> "${(now - lastRefreshAt) / 60_000L} 分钟前刷新"
        else -> {
            val mins = (now - lastRefreshAt) / 60_000L
            "${mins / 60} 小时 ${mins % 60} 分钟前刷新"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (refreshing) "…" else "↻",
            color = if (refreshing) Color(0xFF9E9E9E) else Color(0xFF3B9CFF),
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 5.dp),
        )
        Text(
            label,
            color = Color(0xFF9E9E9E),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderRow(
    board: Board,
    hint: String?,
    onToggleAvatars: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            hint ?: if (board.groupedFilter) "在线 ${board.onlineCount}"
            else "在线 ${board.onlineCount}（分组数据不可用）",
            color = if (hint == null) Color(0xFFE0E0E0) else Color(0xFF9E9E9E),
            fontSize = if (hint == null) 13.sp else 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .combinedClickable(onClick = {}, onLongClick = onToggleAvatars),
        )
    }
}

@Composable
private fun RowItem(row: FriendRow, loader: AvatarLoader, avatarsOn: Boolean, listState: androidx.compose.foundation.lazy.LazyListState) {
    val url = row.thumbnailUrl?.takeIf { avatarsOn }
    var bmp by remember(row.id, url) { mutableStateOf(loader.cached(url)) }
    LaunchedEffect(row.id, url) {
        if (url != null && bmp == null) {
            // 滚动时先等一等，别抢主线程/CPU；等待不触发重组
            runCatching { snapshotFlow { listState.isScrollInProgress }.first { !it } }
            loader.load(url, 44) { bmp = it }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            val image = bmp?.asImageBitmap()
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(Color(0xFF262626), CircleShape)
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(8.dp)
                    .background(Color(row.argb), CircleShape)
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.worldLabel,
                color = Color(0xFF9E9E9E),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一个布尔开关也要落盘：长按标题切换「头像显示」，重启后保持。 */
class UiPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vrc-ui", Context.MODE_PRIVATE)

    var avatarsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AVATARS, true)
        set(value) = prefs.edit().putBoolean(KEY_AVATARS, value).apply()

    private companion object {
        const val KEY_AVATARS = "avatars"
    }
}

/** auth cookie 的落盘：优先 SharedPreferences，其次 `Download/vrc-auth.txt`（adb push 用）。 */
object SessionStore {
    private const val PREFS = "vrc"
    private const val KEY_AUTH = "auth"

    fun load(ctx: Context): VrcSession {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_AUTH, null)
        val session = VrcSession()
        if (!saved.isNullOrBlank()) {
            session.authCookie = saved
            return session
        }
        val fromFile = readPushedCookie(ctx)
        if (fromFile != null) {
            session.authCookie = fromFile
            prefs.edit().putString(KEY_AUTH, fromFile).apply()
        }
        return session
    }

    fun save(ctx: Context, session: VrcSession) {
        session.authCookie?.let {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AUTH, it).apply()
        }
    }

    private fun readPushedCookie(ctx: Context): String? {
        val candidates = listOf(
            java.io.File(ctx.getExternalFilesDir(null), "vrc-auth.txt"),
            java.io.File("/sdcard/Download/vrc-auth.txt"),
        )
        for (f in candidates) {
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val v = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue
            return v.removePrefix("auth=").trim().trim('"')
        }
        return null
    }
}
