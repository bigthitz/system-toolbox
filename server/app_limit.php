<?php
/**
 * 系统工具箱 - 应用限时时间配置接口（App 端 JSON API + 家长管理页）
 *
 * 部署：上传到服务器（与 portal.php 同目录即可），App 端自动拉取。
 *
 * 访问方式：
 *   GET  /app_limit.php            → 返回 JSON 配置（App 端使用）
 *   GET  /app_limit.php?admin      → 家长管理页（浏览器）
 *   POST /app_limit.php?action=save → 保存配置（管理页调用，需口令）
 *
 * 配置存储于同目录 app_limit_config.json，格式：
 *   {"enabled":true,
 *    "windows":[{"start":"07:00","end":"20:00"}],                // 周一~周五
 *    "weekendWindows":[{"start":"09:00","end":"21:00"}]}        // 周六/周日（可选）
 * - windows 为允许使用的时段，支持多个，"20:00-06:00" 表示跨午夜；
 * - weekendWindows 缺失 → 周末沿用 windows；存在但为空 → 周末不限制；
 * - enabled=false 或所有时段为空 → 不限制。
 */

// ---------------- 配置 ----------------
$ACCESS_KEY  = 'eebbk-toolbox-2026';   // 管理口令（与激活门户一致）
$CONFIG_FILE = __DIR__ . '/app_limit_config.json';
// --------------------------------------

function parseWindowsField($arr) {
    $windows = array();
    if (!is_array($arr)) return $windows;
    foreach ($arr as $w) {
        if (is_array($w) && isset($w['start'], $w['end'])) {
            $windows[] = array('start' => (string)$w['start'], 'end' => (string)$w['end']);
        }
    }
    return $windows;
}

function loadConfig($file) {
    $default = array('enabled' => false, 'windows' => array(), 'weekendWindows' => null);
    if (!is_file($file)) return $default;
    $json = json_decode((string)file_get_contents($file), true);
    if (!is_array($json)) return $default;
    $weekend = null;
    if (array_key_exists('weekendWindows', $json)) {
        $weekend = parseWindowsField($json['weekendWindows']);
    }
    return array(
        'enabled'        => isset($json['enabled']) ? (bool)$json['enabled'] : false,
        'windows'        => parseWindowsField(isset($json['windows']) ? $json['windows'] : null),
        'weekendWindows' => $weekend,
    );
}

/** 将管理页多行文本解析为 windows 数组；格式非法直接终止并返回错误。 */
function parseWindowLines($lines) {
    $windows = array();
    foreach (preg_split('/\r\n|\r|\n/', trim($lines)) as $line) {
        $line = trim($line);
        if ($line === '') continue;
        if (!preg_match('/^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$/', $line, $m)) {
            http_response_code(400);
            echo json_encode(array('ok' => false, 'message' => '时段格式错误：' . $line . '（应为 HH:mm-HH:mm）'));
            exit;
        }
        $h1 = (int)$m[1]; $i1 = (int)$m[2]; $h2 = (int)$m[3]; $i2 = (int)$m[4];
        if ($h1 > 23 || $i1 > 59 || $h2 > 23 || $i2 > 59) {
            http_response_code(400);
            echo json_encode(array('ok' => false, 'message' => '时间超出范围：' . $line));
            exit;
        }
        if ($h1 * 60 + $i1 === $h2 * 60 + $i2) continue; // 起止相同，忽略
        $windows[] = array(
            'start' => sprintf('%02d:%02d', $h1, $i1),
            'end'   => sprintf('%02d:%02d', $h2, $i2),
        );
    }
    return $windows;
}

// ---- 保存配置 ----
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_GET['action']) && $_GET['action'] === 'save') {
    header('Content-Type: application/json; charset=utf-8');

    $key = isset($_POST['key']) ? (string)$_POST['key'] : '';
    if ($ACCESS_KEY !== '' && !hash_equals($ACCESS_KEY, $key)) {
        http_response_code(403);
        echo json_encode(array('ok' => false, 'message' => '口令错误'));
        exit;
    }

    $enabled        = isset($_POST['enabled']) && $_POST['enabled'] === '1';
    $weekendEnabled = isset($_POST['weekendEnabled']) && $_POST['weekendEnabled'] === '1';
    $windows        = parseWindowLines(isset($_POST['windows']) ? (string)$_POST['windows'] : '');

    $config = array('enabled' => $enabled, 'windows' => $windows);
    if ($weekendEnabled) {
        // 单独设置周末：保存 weekendWindows（可为空 = 周末不限制）
        $config['weekendWindows'] = parseWindowLines(
            isset($_POST['weekendWindows']) ? (string)$_POST['weekendWindows'] : ''
        );
    }
    // 未单独设置周末：不写 weekendWindows 字段 → 周末沿用平日时段

    $ok = @file_put_contents(
        $CONFIG_FILE,
        json_encode($config, JSON_UNESCAPED_SLASHES)
    );

    if ($ok === false) {
        http_response_code(500);
        echo json_encode(array('ok' => false, 'message' => '配置文件写入失败（检查目录权限）'));
        exit;
    }

    echo json_encode(array('ok' => true, 'message' => '已保存'));
    exit;
}

// ---- App 端 JSON API ----
if (!isset($_GET['admin'])) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode(loadConfig($CONFIG_FILE), JSON_UNESCAPED_SLASHES);
    exit;
}

// ---- 家长管理页 ----
$config = loadConfig($CONFIG_FILE);
$linesText = '';
foreach ($config['windows'] as $w) {
    $linesText .= $w['start'] . '-' . $w['end'] . "\n";
}
$weekendOn = $config['weekendWindows'] !== null;
$weekendText = '';
if ($weekendOn) {
    foreach ($config['weekendWindows'] as $w) {
        $weekendText .= $w['start'] . '-' . $w['end'] . "\n";
    }
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>应用限时配置</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    min-height: 100vh;
    font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: #fff; color: #111;
    display: flex; align-items: center; justify-content: center;
    padding: 24px 16px;
  }
  .card { width: 100%; max-width: 420px; }
  h1 { font-size: 20px; font-weight: 700; margin-bottom: 6px; }
  .sub { font-size: 13px; color: #888; margin-bottom: 20px; }
  .row { display: flex; align-items: center; justify-content: space-between; margin: 16px 0; }
  .row label { font-size: 14.5px; }
  .switch { position: relative; width: 46px; height: 26px; flex: none; }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider {
    position: absolute; inset: 0; background: #ddd; border-radius: 999px;
    transition: .2s; cursor: pointer;
  }
  .slider:before {
    content: ""; position: absolute; width: 20px; height: 20px; left: 3px; top: 3px;
    background: #fff; border-radius: 50%; transition: .2s;
  }
  input:checked + .slider { background: #111; }
  input:checked + .slider:before { transform: translateX(20px); }
  label { display: block; font-size: 13px; color: #666; margin: 14px 0 6px; }
  textarea {
    width: 100%; min-height: 110px; padding: 11px; font-size: 14px; line-height: 1.7;
    color: #111; background: #fafafa;
    border: 1px solid #ddd; border-radius: 9px; outline: none;
    resize: vertical; font-family: Consolas, monospace;
  }
  textarea:focus { border-color: #111; }
  input[type=password] {
    width: 100%; padding: 11px 13px; font-size: 14.5px;
    color: #111; background: #fafafa;
    border: 1px solid #ddd; border-radius: 9px; outline: none;
  }
  input[type=password]:focus { border-color: #111; }
  button {
    width: 100%; margin-top: 20px; padding: 12px; font-size: 15px; font-weight: 600;
    color: #fff; background: #111;
    border: none; border-radius: 9px; cursor: pointer;
  }
  button:disabled { opacity: .5; cursor: not-allowed; }
  .msg { margin-top: 13px; font-size: 13px; display: none; text-align: center; }
  .msg.ok { display: block; color: #16a34a; }
  .msg.err { display: block; color: #dc2626; }
  .tips { margin-top: 18px; font-size: 12px; color: #999; line-height: 1.8; }
</style>
</head>
<body>
<div class="card">
  <h1>应用限时配置</h1>
  <div class="sub">设备上的工具箱每 24 小时自动拉取一次本配置</div>

  <div class="row">
    <label style="margin:0">启用限时</label>
    <div class="switch">
      <input type="checkbox" id="enabled" <?php echo $config['enabled'] ? 'checked' : ''; ?>>
      <span class="slider" onclick="document.getElementById('enabled').click()"></span>
    </div>
  </div>

  <label>周一至周五允许使用的时段（每行一个，格式 HH:mm-HH:mm，跨午夜如 20:00-06:00）</label>
  <textarea id="windows" placeholder="07:00-20:00&#10;21:30-22:30"><?php echo htmlspecialchars($linesText); ?></textarea>

  <div class="row">
    <label style="margin:0">周六、周日单独设置时段</label>
    <div class="switch">
      <input type="checkbox" id="weekendEnabled" <?php echo $weekendOn ? 'checked' : ''; ?>>
      <span class="slider" onclick="document.getElementById('weekendEnabled').click()"></span>
    </div>
  </div>

  <div id="weekendBlock">
    <label>周六、周日允许使用的时段（留空 = 周末不限制）</label>
    <textarea id="weekendWindows" placeholder="09:00-21:00"><?php echo htmlspecialchars($weekendText); ?></textarea>
  </div>

  <label>管理口令</label>
  <input id="key" type="password" autocomplete="off">

  <button id="go" onclick="save()">保存配置</button>

  <div id="msg" class="msg"></div>

  <div class="tips">
    · 时段之外的时间，设备将自动暂停工具箱安装的应用；<br>
    · 未开启周末单独设置时，周末沿用平日时段；<br>
    · 时段为空或关闭开关 = 不限制；<br>
    · 保存后最长 24 小时内生效（设备端也可在「应用限时」页手动刷新）。
  </div>
</div>

<script>
function syncWeekendBlock() {
  var on = document.getElementById('weekendEnabled').checked;
  var block = document.getElementById('weekendBlock');
  block.style.opacity = on ? '1' : '.4';
  block.style.pointerEvents = on ? 'auto' : 'none';
}
document.getElementById('weekendEnabled').addEventListener('change', syncWeekendBlock);
syncWeekendBlock();

function showMsg(text, ok) {
  var m = document.getElementById('msg');
  m.textContent = text;
  m.className = 'msg ' + (ok ? 'ok' : 'err');
}

function save() {
  var btn = document.getElementById('go');
  var key = document.getElementById('key').value;
  if (!key) { showMsg('请输入管理口令', false); return; }

  btn.disabled = true; btn.textContent = '保存中…';

  var fd = new FormData();
  fd.append('enabled', document.getElementById('enabled').checked ? '1' : '0');
  fd.append('windows', document.getElementById('windows').value);
  fd.append('weekendEnabled', document.getElementById('weekendEnabled').checked ? '1' : '0');
  fd.append('weekendWindows', document.getElementById('weekendWindows').value);
  fd.append('key', key);

  fetch('?action=save', { method: 'POST', body: fd })
    .then(function (r) { return r.json(); })
    .then(function (j) {
      btn.disabled = false; btn.textContent = '保存配置';
      showMsg(j.ok ? '已保存，设备将在下次更新时生效' : (j.message || '保存失败'), !!j.ok);
    })
    .catch(function () {
      btn.disabled = false; btn.textContent = '保存配置';
      showMsg('网络错误，请重试', false);
    });
}
</script>
</body>
</html>
