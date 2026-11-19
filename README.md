# VRC 好友（Wear OS 圆表）

在 OPPO Watch（Wear OS）上看 VRChat **在线好友**：状态点、名字、所在世界、分组。
只显示**已分组**的在线好友，未分组不加载。

## 功能

- 在线好友列表（分组顺序 → 状态 → 名字排序）
- 状态点：绿=在线 / 蓝=可加入 / 黄=需请求 / 红=忙碌
- 世界名（后台解析，先出列表再补名字）
- 头像（可选，只请求 64px 版本，滚动时暂停加载）
- 表冠滚动（Activity 层读 `AXIS_VSCROLL`，逐帧平滑泵）
- 下拉/点击状态标签刷新，显示「刷新中… / 刚刚刷新 / X 分钟前刷新」
- 列表快照缓存：冷启动先画上次结果，再静默刷新

## 登录

1. 有保存的 cookie → 直接验证（`GET /api/1/auth/user`，必须是真实用户对象，不能是 2FA 挑战）
2. cookie 失效 → 用保存的用户名/密码登录；服务端要求 2FA 时进入验证码页（支持 `totp` 与 `emailOtp`）
3. 没有凭据 → 表上输入一次，**提交即保存**（手表打字成本高，失败也保存，下次只改错的那项）

凭据用 Android Keystore AES-256-GCM 加密存 SharedPreferences；401 时自动清 cookie 重登。

## 构建

```bash
./gradlew assembleRelease
```

release 使用 debug keystore 签名（可直接 `install -r` 覆盖）。零第三方依赖（Compose 来自 AndroidX BOM）。

## 已知限制

- `/api/1/favorites/groups`（自定义分组名）在当前账号返回 401，分组标题回落为「组1..组4」
- VRChat 的 2FA 挑战可能以 **HTTP 200 + `requiresTwoFactorAuth`** 返回，判断登录必须看 body
- 手表代理会让并发 TLS 握手失败，网络请求串行 + 一次重试
- Compose 全屏重绘在该 GPU 上约 12ms/帧，掉帧率约 3–13%（release）

## 目录

```
app/src/main/java/com/vim/vrcfriends/
  api/         REST 客户端、解析、会话
  auth/        登录状态机、凭据存储（Keystore）
  image/       头像下载与圆形位图缓存
  ui/          刷新节流、列表快照缓存
  MainActivity.kt  界面与表冠接入
icon-src/      图标生成脚本（输出物不入库）
```

## 图标

图标素材由画师 **regreg** 创作；若侵权请联系我删除。
