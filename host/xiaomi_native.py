"""Small SPP recorder probe. Protocol references: HyperionD/minstall (MIT),
AstralSightStudios/AstroBox-NG-Module-Pb and Module-Core. No firmware changes.
The transport runs on Android; this development driver currently requires ADB.
"""
import hashlib
import hmac
import os
import socket
import struct
import time
import zlib
from Crypto.Cipher import AES


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))


def integer(number, value):
    return varint(number << 3) + varint(value)


def blob(number, value):
    return varint((number << 3) | 2) + varint(len(value)) + value


def fields(raw):
    def read(position):
        value = shift = 0
        while position < len(raw) and shift < 70:
            byte = raw[position]
            position += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, position
            shift += 7
        raise ValueError('truncated_varint')
    result = []
    position = 0
    while position < len(raw):
        tag, position = read(position)
        number, kind = tag >> 3, tag & 7
        if not number:
            raise ValueError('invalid_field')
        if kind == 0:
            value, position = read(position)
        else:
            if kind == 2:
                size, position = read(position)
            elif kind in (1, 5):
                size = 8 if kind == 1 else 4
            else:
                raise ValueError('unsupported_wire_type')
            if position + size > len(raw):
                raise ValueError('truncated_field')
            value = raw[position:position + size]
            position += size
        result.append((number, value))
    return result


def mapping(raw):
    return dict(fields(raw))


def crc16(raw):
    crc = 0
    for byte in raw:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0xA001 if crc & 1 else 0)
    return crc


def frame(kind, sequence, payload=b''):
    return b'\xa5\xa5' + struct.pack('<BBHH', kind, sequence % 256, len(payload), crc16(payload)) + payload


def derive(secret, phone_nonce, watch_nonce):
    key = hmac.digest(phone_nonce + watch_nonce, secret, 'sha256')
    first = hmac.digest(key, b'miwear-auth\x01', 'sha256')
    return first + hmac.digest(key, first + b'miwear-auth\x02', 'sha256')


def crypt(key, data):
    return AES.new(key, AES.MODE_CTR, nonce=b'', initial_value=key).encrypt(data)


class AudioFile:
    def __init__(self, expected_size):
        self.expected_size = expected_size
        self.parts = {}
        self.total = None
        self.header = None
        self.checksum = None

    def accept(self, payload):
        if len(payload) < 12 or payload[0] != 0:
            raise ValueError('invalid_audio_block')
        total, current = struct.unpack_from('<HH', payload, 2)
        if not 1 <= current <= total or (self.total is not None and self.total != total):
            raise ValueError('invalid_audio_sequence')
        self.total = total
        offset = 6
        if current == 1:
            offset = 12 + payload[6]
            if offset > len(payload):
                raise ValueError('truncated_audio_header')
            self.header = payload[6:offset]
        if current == total:
            self.checksum = int.from_bytes(payload[-4:], 'little')
            self.parts[current] = payload[offset:-4]
        else:
            self.parts[current] = payload[offset:]
        if len(self.parts) != total:
            return None
        audio = b''.join(self.parts[i] for i in range(1, total + 1))
        if len(audio) != self.expected_size or zlib.crc32(self.header + audio) != self.checksum:
            raise ValueError('audio_integrity_mismatch')
        if not audio.startswith(b'OggS'):
            raise ValueError('expected_ogg_audio')
        return audio


class Session:
    def __init__(self, secret, port=18791):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=8)
        self.socket.settimeout(.2)
        self.buffer = bytearray()
        self.sequence = 2
        self.on_app = lambda packet: None
        self.keys = None
        self.socket.sendall(bytes.fromhex('badcfe00c00300000100ef'))
        tlvs = b'\x01\x03\x00\x01\x00\x00\x02\x02\x00\x00\xfc\x03\x02\x00\x20\x00\x04\x02\x00\x10\x27'
        self.socket.sendall(frame(2, 0, b'\x01' + tlvs))
        self.wait(lambda item: item if item[0] == 2 and item[2][0] == 2 else None)
        phone_nonce = os.urandom(16)
        request = integer(1, 1) + integer(2, 26) + blob(3, blob(30, blob(1, phone_nonce)))
        self.socket.sendall(frame(3, 0, b'\x01\x01' + request))
        response = self.wait(lambda item: self.auth_payload(item, 31))
        watch = mapping(response)
        self.keys = derive(secret, phone_nonce, watch[1])
        if not hmac.compare_digest(watch[2], hmac.digest(self.keys[:16], watch[1] + phone_nonce, 'sha256')):
            raise ValueError('device_authentication_failed')
        info = integer(1, 0) + b'\x15' + struct.pack('<f', 36.) + blob(3, b'BandAgentRecorder') + integer(4, 224) + blob(5, b'CN')
        cipher = AES.new(self.keys[16:32], AES.MODE_CCM, nonce=self.keys[36:40] + bytes(8), mac_len=4)
        encrypted, tag = cipher.encrypt_and_digest(info)
        ack = hmac.digest(self.keys[16:32], phone_nonce + watch[1], 'sha256')
        request = integer(1, 1) + integer(2, 27) + blob(3, blob(32, blob(1, ack) + blob(2, encrypted + tag)))
        self.socket.sendall(frame(3, 1, b'\x01\x01' + request))
        self.wait(lambda item: item if item[0] == 3 and mapping(item[2][2:]).get(2) in (5, 27) else None)

    @staticmethod
    def auth_payload(item, number):
        if item[0] != 3:
            return None
        packet = mapping(item[2][2:])
        if packet.get(1) == 1 and 3 in packet:
            return mapping(packet[3]).get(number)
        return None

    def receive(self):
        while True:
            if len(self.buffer) >= 8:
                if self.buffer[:2] != b'\xa5\xa5':
                    del self.buffer[0]
                    continue
                kind, sequence, size, checksum = struct.unpack_from('<BBHH', self.buffer, 2)
                if len(self.buffer) >= size + 8:
                    payload = bytes(self.buffer[8:size + 8])
                    del self.buffer[:size + 8]
                    if crc16(payload) != checksum:
                        raise ValueError('frame_crc_mismatch')
                    if kind & 15 == 3:
                        self.socket.sendall(frame(1, sequence))
                    return kind & 15, sequence, payload
            try:
                data = self.socket.recv(65536)
            except socket.timeout:
                return None
            if not data:
                raise ConnectionError('phone_transport_closed')
            self.buffer.extend(data)

    def wait(self, predicate, seconds=8):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            item = self.receive()
            if item is None:
                continue
            result = predicate(item)
            if result is not None:
                return result
            packet = self.decode(item)
            if packet and packet.get(1) == 20:
                self.on_app(packet)
        raise TimeoutError('device_response_timeout')

    def decode(self, item):
        if item[0] != 3 or len(item[2]) < 2 or item[2][0] != 1:
            return None
        raw = item[2][2:]
        if item[2][1] == 2:
            raw = crypt(self.keys[:16], raw)
        return mapping(raw)

    def send(self, kind, command, payload):
        raw = integer(1, kind) + integer(2, command) + blob(kind + 2, payload)
        self.socket.sendall(frame(3, self.sequence, b'\x01\x02' + crypt(self.keys[16:32], raw)))
        self.sequence = (self.sequence + 1) % 256

    def request(self, command, payload=b''):
        self.send(18, command, payload)
        def match(item):
            packet = self.decode(item)
            if packet and packet.get(1) == 18 and packet.get(2) == command:
                if 100 in packet:
                    raise ValueError('media_command_rejected')
                return mapping(packet[20])
            return None
        return self.wait(match)

    def status(self):
        return mapping(mapping(self.request(21)[19])[3])[2]

    def start_recording(self):
        if self.status() != 1:
            raise RuntimeError('system_recorder_busy')
        reply = mapping(self.request(20, blob(17, integer(1, 0) + integer(2, 0)))[18])
        if reply[1] != 0:
            raise RuntimeError('start_recording_rejected')
        return reply[2]

    def stop_recording(self):
        self.send(18, 20, blob(17, integer(1, 0) + integer(2, 1)))
        if self.status() != 1:
            raise RuntimeError('stop_recording_failed')

    def download(self, identifier):
        records = [mapping(item) for number, item in fields(self.request(15)[14]) if number == 1]
        record = next(item for item in records if item[1] == identifier)
        audio = AudioFile(record[3])
        # This firmware responds to REQUEST_MEDIA_FILE_LIST (18), not single-file 17.
        self.send(18, 18, blob(16, blob(1, identifier)))
        def match(item):
            if item[0] != 3 or item[2][0] != 2:
                return None
            data = item[2][2:]
            if item[2][1] == 2:
                data = crypt(self.keys[:16], data)
            return audio.accept(data)
        return self.wait(match, 30)

    def close(self):
        self.socket.close()
