<?php
/**
 * 系统工具箱 - 激活码签发门户（前端 + 后端单文件）
 *
 * 部署：与 private_key.pem 放同一目录，浏览器访问本文件即可。
 * 口令与 activate.php 的 $ACCESS_KEY 保持一致（下方 $ACCESS_KEY 可修改）。
 *
 * API（本文件自提供）：
 *   POST ?action=issue   参数：sn（序列号）、key（访问口令）
 *   返回 JSON：{"ok":true,"sn":"...","expire":秒级时间戳,"activation":"..."}
 */

// ---------------- 配置 ----------------
$ACCESS_KEY       = 'eebbk-toolbox-2026';   // 访问口令（与 App 端签发接口一致）
$PRIVATE_KEY_FILE = __DIR__ . '/private_key.pem';
$DURATION         = 86400;                  // 授权时长：一天
// --------------------------------------

if (isset($_GET['action']) && $_GET['action'] === 'issue') {
    header('Content-Type: application/json; charset=utf-8');

    $key = isset($_POST['key']) ? (string)$_POST['key'] : '';
    $sn  = isset($_POST['sn'])  ? trim((string)$_POST['sn']) : '';

    if ($ACCESS_KEY !== '' && !hash_equals($ACCESS_KEY, $key)) {
        http_response_code(403);
        echo json_encode(['ok' => false, 'message' => '访问口令错误']);
        exit;
    }
    if ($sn === '' || strlen($sn) > 128 || !preg_match('/^[\x21-\x7E]+$/', $sn)) {
        http_response_code(400);
        echo json_encode(['ok' => false, 'message' => '序列号格式无效']);
        exit;
    }

    $private = @openssl_pkey_get_private('file://' . $PRIVATE_KEY_FILE);
    if ($private === false) {
        http_response_code(500);
        echo json_encode(['ok' => false, 'message' => '服务器私钥不可用']);
        exit;
    }

    $expire      = time() + $DURATION;
    $expireBytes = pack('J', $expire);
    if (!openssl_sign($sn . $expireBytes, $sig, $private, OPENSSL_ALGO_SHA256)) {
        http_response_code(500);
        echo json_encode(['ok' => false, 'message' => '签发失败']);
        exit;
    }

    $payload    = pack('n', strlen($sn)) . $sn . $expireBytes . $sig;
    $activation = rtrim(strtr(base64_encode($payload), '+/', '-_'), '=');

    echo json_encode(
        ['ok' => true, 'sn' => $sn, 'expire' => $expire, 'activation' => $activation],
        JSON_UNESCAPED_SLASHES
    );
    exit;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>激活码</title>
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
  h1 { font-size: 20px; font-weight: 700; margin-bottom: 22px; }
  label { display: block; font-size: 13px; color: #666; margin: 14px 0 6px; }
  input {
    width: 100%; padding: 11px 13px; font-size: 14.5px;
    color: #111; background: #fafafa;
    border: 1px solid #ddd; border-radius: 9px; outline: none;
    transition: border .2s;
  }
  input:focus { border-color: #111; }
  input.mono { font-family: Consolas, monospace; }
  button {
    width: 100%; margin-top: 20px; padding: 12px; font-size: 15px; font-weight: 600;
    color: #fff; background: #111;
    border: none; border-radius: 9px; cursor: pointer; transition: opacity .2s;
  }
  button:disabled { opacity: .5; cursor: not-allowed; }
  .result { display: none; margin-top: 20px; }
  .result.show { display: block; }
  .expire { font-size: 13px; color: #16a34a; margin-bottom: 10px; }
  textarea {
    width: 100%; min-height: 104px; padding: 11px; font-size: 12px; line-height: 1.55;
    color: #333; background: #fafafa;
    border: 1px solid #ddd; border-radius: 9px; outline: none;
    resize: none; font-family: Consolas, monospace; word-break: break-all;
  }
  .copy {
    margin-top: 10px; padding: 10px; font-size: 13.5px; font-weight: 500;
    background: #f3f4f6; color: #111; border: 1px solid #ddd;
  }
  .msg { margin-top: 13px; font-size: 13px; color: #dc2626; display: none; }
  .msg.err { display: block; }
</style>
</head>
<body>
<div class="card">
  <h1>激活码</h1>

  <label>设备序列号</label>
  <input id="sn" class="mono" autocomplete="off">

  <label>访问口令</label>
  <input id="key" type="password" autocomplete="off">

  <button id="go" onclick="issue()">生成</button>

  <div id="msg" class="msg"></div>

  <div id="result" class="result">
    <div id="expire" class="expire"></div>
    <textarea id="code" readonly></textarea>
    <button class="copy" onclick="copyCode()">复制</button>
  </div>
</div>

<script>
function showMsg(text) {
  var m = document.getElementById('msg');
  m.textContent = text;
  m.className = text ? 'msg err' : 'msg';
}

function issue() {
  var sn  = document.getElementById('sn').value.trim();
  var key = document.getElementById('key').value;
  var btn = document.getElementById('go');
  if (!sn) { showMsg('请输入设备序列号'); return; }
  if (!key) { showMsg('请输入访问口令'); return; }

  btn.disabled = true; btn.textContent = '生成中…';
  showMsg('');

  var fd = new FormData();
  fd.append('sn', sn);
  fd.append('key', key);

  fetch('?action=issue', { method: 'POST', body: fd })
    .then(function (r) { return r.json(); })
    .then(function (j) {
      btn.disabled = false; btn.textContent = '生成';
      if (!j.ok) { showMsg(j.message || '生成失败'); return; }
      showMsg('');
      var d = new Date(j.expire * 1000);
      var pad = function (n) { return (n < 10 ? '0' : '') + n; };
      document.getElementById('expire').textContent =
        '有效期至 ' + d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
        ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
      document.getElementById('code').value = j.activation;
      document.getElementById('result').classList.add('show');
    })
    .catch(function () {
      btn.disabled = false; btn.textContent = '生成';
      showMsg('网络错误，请重试');
    });
}

function copyCode() {
  var code = document.getElementById('code').value;
  var done = function () {
    var b = document.querySelector('.copy');
    b.textContent = '已复制';
    setTimeout(function () { b.textContent = '复制'; }, 1600);
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(code).then(done, function () { fallbackCopy(code); done(); });
  } else {
    fallbackCopy(code); done();
  }
}

function fallbackCopy(text) {
  var ta = document.getElementById('code');
  ta.focus(); ta.select();
  try { document.execCommand('copy'); } catch (e) {}
}

document.getElementById('sn').addEventListener('keydown', function (e) {
  if (e.key === 'Enter') issue();
});
</script>
</body>
</html>
