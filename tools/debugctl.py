# -*- coding: utf-8 -*-
"""刷题调试端口客户端（宿主机侧）。

前提：手机上打开调试模式（关于页连点版本号 7 次 + 调试密码），app 会在
127.0.0.1:8765 监听命令端口；本脚本自动做 `adb forward` 后连接。

用法：
    python tools\\debugctl.py help
    python tools\\debugctl.py -c "state"
    python tools\\debugctl.py -c "source list"
    python tools\\debugctl.py -c "update source http://10.0.2.2:8099/dav/ testuser testpass shuaati/"
    python tools\\debugctl.py -c "update"
    python tools\\debugctl.py                     # 交互模式，一行一条命令，quit 退出

选项：
    --adb PATH     指定 adb（默认取本机 Android SDK 的 platform-tools）
    --no-forward   不执行 adb forward（已手动转发时用）
    --port N       端口，默认 8765（要和 app 里 DebugServer.PORT 一致）
"""
import argparse
import os
import socket
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

ADB_DEFAULT = r"C:\Users\SDMW\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEFAULT_PORT = 8765


def ensure_forward(adb, port):
    """把设备的调试端口转发到宿主机（重复执行无副作用）。"""
    if not os.path.isfile(adb):
        print(f"[warn] 找不到 adb：{adb}（可用 --adb 指定，或 --no-forward 跳过）")
        return False
    result = subprocess.run(
        [adb, "forward", f"tcp:{port}", f"tcp:{port}"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"[warn] adb forward 失败：{result.stderr.strip()[:200]}")
        return False
    return True


def send(command, port, timeout=15):
    """发一条命令，读一行 JSON 响应。"""
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.sendall((command + "\n").encode("utf-8"))
        buffer = b""
        while not buffer.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            buffer += chunk
    return buffer.decode("utf-8", "replace").strip()


def main():
    parser = argparse.ArgumentParser(description="刷题调试端口客户端")
    parser.add_argument("-c", "--command", help="单条命令（不传则进入交互模式）")
    parser.add_argument("--adb", default=ADB_DEFAULT)
    parser.add_argument("--no-forward", action="store_true")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()

    if not args.no_forward:
        ensure_forward(args.adb, args.port)

    if args.command:
        try:
            print(send(args.command, args.port))
        except Exception as e:
            print(f"[error] {e}")
            sys.exit(1)
        return

    print(f"已连接 127.0.0.1:{args.port}（一行一条命令，quit 退出）")
    for line in sys.stdin:
        command = line.strip()
        if not command:
            continue
        if command in ("quit", "exit"):
            break
        try:
            print(send(command, args.port))
        except Exception as e:
            print(f"[error] {e}")


if __name__ == "__main__":
    main()
