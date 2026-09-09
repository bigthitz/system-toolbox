<?php
/**
 * 系统工具箱 - 远程自毁开关接口（App 端纯文本 API + 管理页）
 *
 * 部署：上传到服务器（与 app_limit.php 同目录即可），App 端自动拉取。
 *
 * 访问方式：
 *   GET  /use.php             → 纯文本 true / false（App 端使用）
 *   GET  /use.php?admin       → 管理页（浏览器）
 *   POST /use.php?action=save → 保存开关（管理页调用，需口令）
 *
 * 配置存储于同目录 use_config.json：{"self_destruct":false}
 * 返回 true 时，App 会在下次联网检查（进入应用时 / 守护服务每 15 分钟）
 * 尝试卸载自己；卸载失败则闪退，且本地记录标记，断网也无法绕过。
 */

// ---------------- 配置 ----------------
$ACCESS_KEY  = 'eebbk-toolbox-2026';   // 管理口令（与其他后台一致）
$CONFIG_FILE = __DIR__ . '/use_config.json';
// --------------------------------------

function loadFlag($file) {
    if (!is_file($file)) return false;
    $json = json_decode((string)file_get_contents($file), true);
    return is_array($json) && isset($json['self_destruct']) && $json['self_destruct'] === true;
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

    $flag = isset($_POST['flag']) && $_POST['flag'] === '1';
    $ok = @file_put_contents(
        $CONFIG_FILE,
        json_encode(array('self_destruct' => $flag), JSON_UNESCAPED_SLASHES)
    );

    if ($ok === false) {
        http_response_code(500);
        echo json_encode(array('ok' => false, 'message' => '配置文件写入失败（检查目录权限）'));
        exit;
    }

    echo json_encode(array('ok' => true, 'message' => '已保存'));
    exit;
}

// ---- App 端纯文本 API ----
if (!isset($_GET['admin'])) {
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo loadFlag($CONFIG_FILE) ? 'true' : 'false';
    exit;
}

// ---- 管理页 ----
$flag = loadFlag($CONFIG_FILE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>远程自毁开关</title>
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
  input:checked + .slider { background: #dc2626; }
  input:checked + .slider:before { transform: translateX(20px); }
  label { display: block; font-size: 13px; color: #666; margin: 14px 0 6px; }
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
  .danger { color: #dc2626; font-weight: 600; }
</style>
</head>
<body>
<div class="card">
  <h1>远程自毁开关</h1>
  <div class="sub">设备上的工具箱进入应用时及每 15 分钟自动检查一次本开关</div>

  <div class="row">
    <label style="margin:0">允许应用继续使用</label>
    <div class="switch">
      <input type="checkbox" id="flag" <?php echo $flag ? '' : 'checked'; ?>>
      <span class="slider" onclick="document.getElementById('flag').click()"></span>
    </div>
  </div>

  <label>管理口令</label>
  <input id="key" type="password" autocomplete="off">

  <button id="go" onclick="save()">保存配置</button>

  <div id="msg" class="msg"></div>

  <div class="tips">
    · 开关拨到左侧（红色）= 下发<span class="danger">自毁</span>指令：<br>
    &nbsp;&nbsp;设备端将尝试卸载工具箱，卸载失败会直接闪退，<br>
    &nbsp;&nbsp;且已触发的设备断网也无法恢复使用；<br>
    · 开关拨到右侧 = 正常使用（无网络时应用会禁止使用直到联网）。
  </div>
</div>

<script>
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

  // 页面开关语义为「允许使用」，接口参数 flag 为「自毁」，取反
  var allow = document.getElementById('flag').checked;

  var fd = new FormData();
  fd.append('flag', allow ? '0' : '1');
  fd.append('key', key);

  fetch('?action=save', { method: 'POST', body: fd })
    .then(function (r) { return r.json(); })
    .then(function (j) {
      btn.disabled = false; btn.textContent = '保存配置';
      showMsg(j.ok ? '已保存，设备将在下次检查时生效' : (j.message || '保存失败'), !!j.ok);
    })
    .catch(function () {
      btn.disabled = false; btn.textContent = '保存配置';
      showMsg('网络错误，请重试', false);
    });
}
</script>
</body>
</html>
