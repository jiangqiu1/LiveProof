# 保利威「指定时间点截图」尺寸探测（一次性排查脚本，不属于业务代码）
#
# 目的：确认返回 JPEG 的宽高是否受 width 参数影响。
# 背景：源视频 1920x1080 横屏，但经 MaterialFrameService 取到的帧是 720x1080 竖屏，
#       与「按宽等比缩放应为 720x405」不符。
#
# 用法：python scripts/probe-polyv-snapshot.py <vid>
# 凭据从 .env.local 读取，不打印、不落日志。

import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_env():
    env = {}
    path = os.path.join(ROOT, ".env.local")
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def sign_v2(params, secret):
    # v2 签名：参数按 key 升序拼成 k=v&，末尾接 secretkey，SHA1 后转大写
    joined = "&".join(f"{k}={params[k]}" for k in sorted(params))
    return hashlib.sha1((joined + secret).encode("utf-8")).hexdigest().upper()


def post_form(url, body):
    data = urllib.parse.urlencode(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_json(url):
    with urllib.request.urlopen(url, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def jpeg_size(raw):
    i = 2
    while i < len(raw) - 9:
        if raw[i] != 0xFF:
            i += 1
            continue
        marker = raw[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h = (raw[i + 5] << 8) | raw[i + 6]
            w = (raw[i + 7] << 8) | raw[i + 8]
            return w, h
        if marker in (0xD8, 0xD9):
            i += 2
            continue
        length = (raw[i + 2] << 8) | raw[i + 3]
        i += 2 + length
    return None


def snapshot(env, vid, offset, width=None, label=""):
    params = {
        "vid": vid,
        "offsetTimes": str(offset),
        "ptime": str(int(time.time() * 1000)),
    }
    if width is not None:
        params["width"] = str(width)
    body = dict(params)
    body["sign"] = sign_v2(params, env["POLYV_VOD_SECRET_KEY"])

    base = "https://api.polyv.net"
    uid = env["POLYV_VOD_USER_ID"]
    created = post_form(f"{base}/v2/video/snapshot/{uid}/addTask", body)
    if created.get("code") != 200:
        print(f"[{label}] addTask 失败: {created}")
        return
    task_id = created.get("data")
    deadline = time.time() + 30
    while time.time() < deadline:
        q = {"taskId": str(task_id), "ptime": str(int(time.time() * 1000))}
        query = urllib.parse.urlencode(q)
        url = f"{base}/v2/video/snapshot/{uid}/getTaskStatus?{query}&sign={sign_v2(q, env['POLYV_VOD_SECRET_KEY'])}"
        root = get_json(url)
        if root.get("code") != 200:
            print(f"[{label}] 查询失败: {root}")
            return
        data = root.get("data") or {}
        snaps = data.get("snapshots")
        if snaps:
            out_dir = os.path.join(ROOT, "docs", "assets", "p2-check")
            os.makedirs(out_dir, exist_ok=True)
            for node in snaps:
                img_url = node.get("imageUrl")
                with urllib.request.urlopen(img_url, timeout=30) as resp:
                    raw = resp.read()
                size = jpeg_size(raw)
                off = node.get("offsetTime")
                print(f"[{label}] offset={off} JPEG={size} bytes={len(raw)}")
                path = os.path.join(out_dir, f"probe-{label}-{off}s.jpg")
                with open(path, "wb") as fh:
                    fh.write(raw)
            # 打印原始结构一次，便于对照文档
            print(f"[{label}] raw data keys: {list(data.keys())}")
            print(f"[{label}] raw snapshot node: {json.dumps(snaps[0], ensure_ascii=False)}")
            return
        if "fail" in str(data.get("status", "")).lower():
            print(f"[{label}] 任务失败: {data}")
            return
        time.sleep(1)
    print(f"[{label}] 超时")


if __name__ == "__main__":
    env = load_env()
    vid = sys.argv[1] if len(sys.argv) > 1 else env.get("POLYV_DEMO_VID")
    if not vid:
        raise SystemExit("缺少 vid")
    offset = int(sys.argv[2]) if len(sys.argv) > 2 else 23
    print(f"vid={vid} offset={offset}")
    snapshot(env, vid, offset, width=None, label="nowidth")
    snapshot(env, vid, offset, width=720, label="w720")
    snapshot(env, vid, offset, width=1920, label="w1920")