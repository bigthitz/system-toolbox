<?php
/**
 * 系统工具箱 - 激活码签发接口（带授权时长）
 *
 * 用法：
 *   https://your.host/server/activate.php?sn=设备序列号&key=访问口令
 *
 * 激活码结构：
 *   Base64URL( 2字节大端SN长度 + SN + 8字节大端过期时间戳(秒) + RSA-2048签名 )
 *   签名内容 = SN + 过期时间字节（SHA256withRSA），App 端公钥验签并校验未过期。
 *
 * 返回 JSON：
 *   成功 {"ok":true, "sn":"...", "expire":1690000000, "activation":"..."}
 *   失败 {"ok":false, "message":"原因"}
 */

// ---------------- 配置 ----------------
// 授权时长（秒）：一天
$DURATION = 86400;
// 简单访问口令，防止接口被陌生人滥用；留空 '' 则不校验。
$ACCESS_KEY = 'eebbk-toolbox-2026';
// 私钥文件路径（与本文件同目录）
$PRIVATE_KEY_FILE = __DIR__ . '/private_key.pem';
// --------------------------------------

header('Content-Type: application/json; charset=utf-8');

$sn  = isset($_GET['sn'])  ? trim((string)$_GET['sn'])  : '';
$key = isset($_GET['key']) ? (string)$_GET['key'] : '';

if ($ACCESS_KEY !== '' && !hash_equals($ACCESS_KEY, $key)) {
    http_response_code(403);
    echo json_encode(['ok' => false, 'message' => '访问口令错误']);
    exit;
}

// SN 仅允许可见 ASCII，防止注入与异常输入
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

$expire = time() + $DURATION;

// 签名内容 = SN + 8字节大端过期时间戳
$expireBytes = pack('J', $expire);
if (!openssl_sign($sn . $expireBytes, $sig, $private, OPENSSL_ALGO_SHA256)) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'message' => '签名失败']);
    exit;
}

// 激活码 = Base64URL( 2字节SN长度 + SN + 过期时间 + 签名 )，无填充
$payload    = pack('n', strlen($sn)) . $sn . $expireBytes . $sig;
$activation = rtrim(strtr(base64_encode($payload), '+/', '-_'), '=');

echo json_encode(
    ['ok' => true, 'sn' => $sn, 'expire' => $expire, 'activation' => $activation],
    JSON_UNESCAPED_SLASHES
);
