#!/usr/bin/env python3
"""Pretends to be the PenBridge phone app. Useful for testing the PC program
without a phone:  python3 tools/fake_phone.py [host] [port]"""
import socket, struct, sys, time

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 47800

def s(text):
    b = text.encode()
    return struct.pack(">H", len(b)) + b

def frame(ty, payload=b""):
    body = bytes([ty]) + payload
    return struct.pack(">H", len(body)) + body

def read_frame(sock):
    hdr = sock.recv(2, socket.MSG_WAITALL)
    n = struct.unpack(">H", hdr)[0]
    return sock.recv(n, socket.MSG_WAITALL)

def expect(sock, ty):
    """Reads frames until one of type `ty`, printing any notices on the way."""
    while True:
        f = read_frame(sock)
        if f[0] == 0x83:
            print("notice:", f[3:].decode())
        if f[0] == ty:
            return f

def pen(flags, x, y, p=0, tx=0, ty=0):
    return frame(0x10, struct.pack(">BHHHbb", flags, x, y, p, tx, ty))

# Discovery
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
u.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
u.settimeout(1.0)
u.sendto(b"PENBRIDGE?1", ("255.255.255.255" if host != "127.0.0.1" else host, port + 1))
try:
    print("discovery reply:", u.recvfrom(256)[0].decode())
except socket.timeout:
    print("discovery: no reply")

c = socket.create_connection((host, port))
c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
c.sendall(frame(0x01, struct.pack(">H", 1) + s("Fake phone") + s("0.1.0")))
w = read_frame(c)
assert w[0] == 0x81, w
ver = struct.unpack(">H", w[1:3])[0]
off = 3
def rs():
    global off
    n = struct.unpack(">H", w[off:off+2])[0]; v = w[off+2:off+2+n].decode(); off += 2 + n; return v
hostname, os_name = rs(), rs()
count = w[off]; off += 1
print(f"welcome: v{ver} host={hostname} os={os_name} displays={count}")
for i in range(count):
    x, y, wd, ht, prim = struct.unpack(">iiiiB", w[off:off+17]); off += 17
    print(f"  display {i}: {rs()} {wd}x{ht} at {x},{y} primary={prim}")

c.sendall(frame(0x02, bytes([0])))
# hover -> stroke -> lift -> leave
c.sendall(pen(0x01, 1000, 1000))
c.sendall(pen(0x03, 1000, 1000, 20000, 10, -5))
for i in range(5):
    c.sendall(pen(0x03, 1000 + i * 5000, 1000 + i * 3000, 30000 + i * 5000))
c.sendall(pen(0x01, 26000, 13000))
c.sendall(pen(0x00, 26000, 13000))
# keys
c.sendall(frame(0x20, bytes([3]) + s("ctrl+z")))
c.sendall(frame(0x20, bytes([1]) + s("space")))
c.sendall(frame(0x20, bytes([2]) + s("space")))
c.sendall(frame(0x20, bytes([3]) + s("ctrl+banana")))
# ping
t = int(time.time() * 1000) & 0xFFFFFFFF
c.sendall(frame(0x30, struct.pack(">I", t)))
p = expect(c, 0xB0)
assert p[0] == 0xB0 and struct.unpack(">I", p[1:5])[0] == t
print("pong ok")
# disconnect mid-stroke with a held key: PC should clean up
c.sendall(frame(0x20, bytes([1]) + s("alt")))
c.sendall(pen(0x03, 5000, 5000, 40000))
time.sleep(0.2)
c.close()
print("done")
