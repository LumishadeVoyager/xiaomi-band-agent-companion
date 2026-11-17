import struct
import sys
from pathlib import Path
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'host'))
from xiaomi_native import AudioFile, Session, blob, crc16, fields, frame, integer, mapping


class NativeRecorderTest(unittest.TestCase):
    def test_sync_list_waits_for_report_16_rather_than_request_15(self):
        session = Session.__new__(Session)
        sent = []
        session.send = lambda *args: sent.append(args)
        status = integer(1, 18) + integer(2, 22) + blob(20, blob(19, b''))
        report = integer(1, 18) + integer(2, 16) + blob(20, blob(14, b''))
        responses = iter([(3, 1, b'\x01\x01' + status), (3, 2, b'\x01\x01' + report)])
        session.receive = lambda: next(responses)
        session.on_app = lambda packet: self.fail('Unexpected application packet')
        self.assertEqual(session.request(15), {14: b''})
        self.assertEqual(sent, [(18, 15, b'')])

    def test_media_wire_commands_and_frame_crc(self):
        # Wire values captured on the device; no identifiers/audio in this fixture.
        command = integer(1, 18) + integer(2, 20) + blob(20, blob(17, integer(1, 0) + integer(2, 0)))
        self.assertEqual(command.hex(), '08121014a201078a010408001000')
        self.assertEqual(mapping(mapping(mapping(command)[20])[17]), {1: 0, 2: 0})
        self.assertEqual(crc16(b'123456789'), 0xbb3d)
        self.assertEqual(frame(3, 257, command)[2:4], b'\x03\x01')
        with self.assertRaises(ValueError):
            fields(b'\x0a\x10short')

    def blocks(self):
        data = b'OggS' + bytes(range(64))
        header = b'\x08test.ogg' + bytes(5)
        first = b'\x00\x00' + struct.pack('<HH', 2, 1) + header + data[:30]
        last = b'\x00\x00' + struct.pack('<HH', 2, 2) + data[30:] + struct.pack('<I', zlib.crc32(header + data))
        return data, first, last

    def test_reverse_audio_is_complete_and_retries_do_not_duplicate(self):
        data, first, last = self.blocks()
        audio = AudioFile(len(data))
        self.assertIsNone(audio.accept(first))
        self.assertIsNone(audio.accept(first))
        self.assertEqual(audio.accept(last), data)

    def test_corruption_and_wrong_size_cannot_become_a_transcription(self):
        data, first, last = self.blocks()
        for size, ending in [(len(data) + 1, last), (len(data), last[:-1] + bytes([last[-1] ^ 1]))]:
            audio = AudioFile(size)
            audio.accept(first)
            with self.assertRaisesRegex(ValueError, 'integrity'):
                audio.accept(ending)


if __name__ == '__main__':
    unittest.main()
