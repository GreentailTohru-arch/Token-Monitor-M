"""Windows RFCOMM listener + SDP advertisement without third-party packages.

API reference: https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsasetservice
SOCKADDR_BTH is packed to one byte by ws2bth.h; WSAQUERYSETW uses normal ABI alignment.
"""
import ctypes as C
import os
import socket
import struct
import threading
import uuid


class GUID(C.Structure):
    _fields_ = [("Data1", C.c_uint32), ("Data2", C.c_uint16), ("Data3", C.c_uint16), ("Data4", C.c_ubyte * 8)]


class SOCKADDR_BTH(C.Structure):
    _pack_ = 1
    _fields_ = [("addressFamily", C.c_uint16), ("btAddr", C.c_uint64), ("serviceClassId", GUID), ("port", C.c_uint32)]


class SOCKET_ADDRESS(C.Structure):
    _fields_ = [("lpSockaddr", C.c_void_p), ("iSockaddrLength", C.c_int)]


class CSADDR_INFO(C.Structure):
    _fields_ = [("LocalAddr", SOCKET_ADDRESS), ("RemoteAddr", SOCKET_ADDRESS),
                ("iSocketType", C.c_int), ("iProtocol", C.c_int)]


class WSAQUERYSETW(C.Structure):
    _fields_ = [("dwSize", C.c_uint32), ("lpszServiceInstanceName", C.c_wchar_p),
                ("lpServiceClassId", C.POINTER(GUID)), ("lpVersion", C.c_void_p),
                ("lpszComment", C.c_wchar_p), ("dwNameSpace", C.c_uint32),
                ("lpNSProviderId", C.c_void_p), ("lpszContext", C.c_wchar_p),
                ("dwNumberOfProtocols", C.c_uint32), ("lpafpProtocols", C.c_void_p),
                ("lpszQueryString", C.c_wchar_p), ("dwNumberOfCsAddrs", C.c_uint32),
                ("lpcsaBuffer", C.POINTER(CSADDR_INFO)), ("dwOutputFlags", C.c_uint32),
                ("lpBlob", C.c_void_p)]


class BluetoothServer:
    def __init__(self, service_uuid, handler):
        if os.name != "nt" or not hasattr(socket, "AF_BLUETOOTH"):
            raise RuntimeError("Windows Bluetooth socket support required")
        self.handler = handler
        self.sock = None
        self.registered = False
        self.stopped = threading.Event()
        self.ws2 = C.WinDLL("Ws2_32.dll")
        self.ws2.WSASetServiceW.argtypes = [C.POINTER(WSAQUERYSETW), C.c_int, C.c_uint32]
        self.ws2.WSASetServiceW.restype = C.c_int
        self.ws2.WSAGetLastError.restype = C.c_int
        self.guid = GUID.from_buffer_copy(uuid.UUID(service_uuid).bytes_le)

    def start(self):
        self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        try:
            # Require an authenticated, encrypted Bluetooth link. Android uses
            # createRfcommSocketToServiceRecord, which matches these settings.
            self.sock.setsockopt(3, -2147483647, struct.pack("I", 1))  # SO_BTH_AUTHENTICATE
            self.sock.setsockopt(3, 2, struct.pack("I", 1))  # SO_BTH_ENCRYPT
            # CPython 3.10's bind port parser cannot represent BT_PORT_ANY (-1).
            # Bind the first available RFCOMM channel, then advertise it by UUID.
            for port in list(range(7, 31)) + list(range(1, 7)):
                try:
                    self.sock.bind(("00:00:00:00:00:00", port))
                    break
                except OSError as exc:
                    if getattr(exc, "winerror", None) not in (10048, 10013) and exc.errno not in (10048, 10013):
                        raise
            else:
                raise RuntimeError("No free RFCOMM channel")
            self.sock.listen(4)
            self.sock.settimeout(1)
            self.address, self.channel = self.sock.getsockname()
            self.addr = SOCKADDR_BTH()
            self.addr.addressFamily = 32
            self.addr.btAddr = int(self.address.replace(":", ""), 16)
            self.addr.port = self.channel
            address = SOCKET_ADDRESS(C.addressof(self.addr), C.sizeof(self.addr))
            self.csaddr = CSADDR_INFO(address, address, 1, 3)
            self.query = WSAQUERYSETW()
            self.query.dwSize = C.sizeof(WSAQUERYSETW)
            self.query.lpszServiceInstanceName = "Token Monitor Mobile"
            self.query.lpServiceClassId = C.pointer(self.guid)
            self.query.lpszComment = "Authenticated token usage snapshots"
            self.query.dwNameSpace = 16
            self.query.dwNumberOfCsAddrs = 1
            self.query.lpcsaBuffer = C.pointer(self.csaddr)
            if self.ws2.WSASetServiceW(C.byref(self.query), 0, 0) == -1:
                raise OSError(self.ws2.WSAGetLastError(), "Bluetooth SDP registration failed")
            self.registered = True
            self.thread = threading.Thread(target=self._accept, daemon=True, name="RFCOMM-listener")
            self.thread.start()
        except Exception:
            self.close()
            raise

    def _accept(self):
        while not self.stopped.is_set():
            try:
                client, _ = self.sock.accept()
                threading.Thread(target=self.handler, args=(client,), daemon=True).start()
            except socket.timeout:
                continue
            except OSError:
                break

    def close(self):
        self.stopped.set()
        if self.registered:
            self.ws2.WSASetServiceW(C.byref(self.query), 2, 0)  # RNRSERVICE_DELETE
            self.registered = False
        if self.sock:
            self.sock.close()
            self.sock = None
