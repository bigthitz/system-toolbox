<?php
/**
 * 系统工具箱 - 激活码签发接口
 *
 * 用法：
 *   https://your.host/server/activate.php?sn=设备序列号&key=访问口令
 *
 * 原理：
 *   服务器持有 RSA-2048 私钥（本目录 private_key.pem），对序列号 SN 做
 *   SHA256withRSA 签名，激活码 = Base64URL( len(2字节,大端) + SN + 签名(256字节) )。
 *   App 内置公钥，校验激活码中的 SN 与本机 /data/misc/bbksn 一致且签名有效即激活。
 *
 * 返回 JSON：
 *   成功 {"ok":true, "sn":"...", "activation":"..."}
 *   失败 {"ok":false, "message":"原因"}
 */

// ---------------- 配置 ----------------
// 简单访问口令，防止接口被陌生人滥用；App 请求时带 &key=...。留空 '' 则不校验。
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

if (!openssl_sign($sn, $sig, $private, OPENSSL_ALGO_SHA256)) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'message' => '签名失败']);
    exit;
}

// 激活码结构：2字节大端 SN 长度 + SN 原文 + RSA 签名，整体 Base64URL（无填充）
$payload    = pack('n', strlen($sn)) . $sn . $sig;
$activation = rtrim(strtr(base64_encode($payload), '+/', '-_'), '=');

echo json_encode(
    ['ok' => true, 'sn' => $sn, 'activation' => $activation],
    JSON_UNESCAPED_SLASHES
);
