# 系统工具箱（SystemToolbox）

面向已具备 **system 权限 / system 签名** 设备的系统级工具 APK。

- 静默安装选中的 APK（跳过安装确认界面，支持覆盖安装 / 降级替换）
- 冻结 / 解冻已安装应用（语义等价 `pm disable-user`，可从桌面隐藏且不再运行）
- 预留扩展功能槽位（卸载、备份、隐藏图标、清理加速，后续版本逐步开放）
- Compose + Material3 实现，深色/浅色自适应，页面切换带过渡动画

## 技术要点

| 模块 | 实现 |
| --- | --- |
| 静默安装 | `PackageInstaller.Session` + `PendingIntent` 广播回执（`core/SilentInstaller.kt`） |
| 应用冻结 | 优先 `PackageManager.setApplicationEnabledSetting`，失败回退 `pm disable-user --user 0` |
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
