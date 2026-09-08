# 系统工具箱（SystemToolbox）

面向已具备 **system 权限 / system 签名** 设备的系统级工具 APK。

- 静默安装选中的 APK（跳过安装确认界面，支持覆盖安装 / 降级替换）
- 冻结 / 解冻已安装应用（语义等价 `pm disable-user`，可从桌面隐藏且不再运行）
- 应用限时：按云端时间配置自动禁用 / 解禁通过本工具箱安装的应用（守护服务开机自启、每 2 分钟扫描一次）
- 预留扩展功能槽位（卸载、备份、隐藏图标、清理加速，后续版本逐步开放）
- Compose + Material3 实现，深色/浅色自适应，页面切换带过渡动画

## 技术要点

| 模块 | 实现 |
| --- | --- |
| 静默安装 | `PackageInstaller.Session` + `PendingIntent` 广播回执（`core/SilentInstaller.kt`） |
| 应用冻结 | 优先 `PackageManager.setApplicationEnabledSetting`，失败回退 `pm disable-user --user 0` |
| 应用限时 | 前台守护服务（`service/AppLimitService.kt`）：开机自启 + `persistent` 常驻 + 精确闹钟兜底；每 2 分钟扫描受管应用，按云端时段禁用/解禁；配置 24 小时云端更新一次，失败回退本地缓存 |
| 系统权限 | `INSTALL_PACKAGES` / `DELETE_PACKAGES` / `CHANGE_COMPONENT_ENABLED_STATE` / `QUERY_ALL_PACKAGES` |
| UID 方案 | Manifest 声明 `sharedUserId="android.uid.system"`，签名匹配后以 UID 1000 运行 |
| UI | Jetpack Compose、Material3、Navigation 过渡动画、按包名动态主题色 |

## 构建

本机不构建，全部走 GitHub Actions：

1. 推送到仓库的 `main` 分支（或在 Actions 页手动 `Run workflow`）。
2. 构建产物从 Actions 的 Artifacts 下载。

默认产物使用 debug key 签名（`app-release.apk`），仅用于普通安装测试；
**要在设备上获得 system 权限，必须用你的 platform/system 证书重签名**。

### 方式一：在 Actions 中自动重签名（推荐）

把证书以 base64 内容加入仓库 Secrets（Settings → Secrets and variables → Actions）：

- `PLATFORM_PK8` ← `base64 -w0 platform.pk8`
- `PLATFORM_X509` ← `base64 -w0 platform.x509.pem`

工作流会自动产出 `SystemToolbox-platform-signed.apk`。

### 方式二：本地重签名（Windows PowerShell 示例）

```powershell
# 需要 Android build-tools 中的 zipalign / apksigner
zipalign -f -p 4 app-release.apk app-aligned.apk
apksigner sign --key platform.pk8 --cert platform.x509.pem `
  --out SystemToolbox-platform-signed.apk app-aligned.apk
```

## 部署到设备

- 将 platform 签名后的 APK `adb push` 到 `/system/priv-app/SystemToolbox/`（或 `/product/priv-app/`）后重启；
- 若设备允许（签名与系统一致），也可直接 `adb install`。

> 若安装时报 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`：
> 说明该 ROM 不允许普通方式加入 `android.uid.system`，请改为放入系统分区部署；
> 或删除 `AndroidManifest.xml` 中的 `android:sharedUserId` 行重新构建（仍保留 signature 级权限，但部分能力受限）。

## 权限自检

安装后在 App 内「关于」页可看到：

- 进程 UID 是否为 1000（system）
- 四个关键系统权限是否授予
- 未授予时首页会显示醒目提示

## 应用限时

按云端下发的「允许使用时段」自动控制工具箱所装应用的可用性：时段外禁用应用入口（等价 `pm disable-user`），时段内自动恢复；服务开机自启并保持后台长期存活。

- **作用范围**：通过本工具箱静默安装 / 应用商店安装的应用自动纳入管理（可在「应用限时」页手动增删）；
- **扫描周期**：前台守护服务每 2 分钟扫描一轮；
- **配置缓存**：云端配置每 24 小时更新一次，更新失败自动回退上一次的本地缓存；从未成功拉取时不限制（避免锁死设备）；
- **保活方式**：`persistent` 常驻（需 system 分区部署生效）+ `BOOT_COMPLETED` 自启 + 前台服务（START_STICKY、stopWithTask=false）+ 精确闹钟兜底重启。

### 云端配置

把 `server/app_limit.php` 上传到服务器（App 默认拉取 `https://eebbk.bbroot.com/app_limit.php`），返回 JSON：

```json
{
  "enabled": true,
  "windows": [ {"start": "07:00", "end": "20:00"}, {"start": "21:30", "end": "22:30"} ]
}
```

- `windows` 为允许使用的时段，支持多个；`start > end` 表示跨午夜（如 `20:00-06:00`）；
- `enabled=false` 或 `windows` 为空 = 不限制；
- 浏览器访问 `app_limit.php?admin` 打开管理页（口令与激活门户一致），可视化编辑时段。
