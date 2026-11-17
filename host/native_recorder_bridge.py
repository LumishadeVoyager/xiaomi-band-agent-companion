"""Development loop: wrist button -> system recorder -> phone SPP -> local STT.
Requires the Android ADB transport and a private JSON configuration; no Agent
turn is submitted. This is not yet a standalone Android background service.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
from xiaomi_native import Session, blob, integer, mapping


def run(config, launch, once=False):
    session = Session(bytes.fromhex(config['authkey']))
    fingerprint = bytes.fromhex(config['fingerprint'])
    identities = {entry: blob(1, ('io.github.lumishadevoyager.bandagent.' + entry).encode()) + blob(2, fingerprint)
                  for entry in ('codex', 'harness')}
    queue = []
    drafts = {}
    acknowledged = set()
    active = None
    canceled = False
    output = Path(config['output'])
    output.mkdir(parents=True, exist_ok=True)

    def send(entry, message):
        data = json.dumps(dict(protocol=1, entryId=entry, **message), ensure_ascii=False).encode()
        session.send(20, 8, blob(9, blob(1, identities[entry]) + blob(2, data)))

    def status(entry, text, recording=False):
        send(entry, dict(type='record_status', text=text, recording=recording))

    def on_app(packet):
        nonlocal canceled
        wrapper = mapping(packet[22])
        content = mapping(wrapper[9]) if 9 in wrapper else None
        basic = content[1] if content else wrapper.get(5)
        if basic is None:
            return
        identity = mapping(basic)
        entry = identity[1].decode().rsplit('.', 1)[-1]
        if entry not in identities or basic != identities[entry]:
            return
        if not content:
            session.send(20, 7, blob(8, blob(1, basic) + integer(2, 1)))
            return
        message = json.loads(content[2])
        if message.get('entryId') != entry or message.get('protocol') != 1:
            return
        kind = message['type']
        if kind == 'ota_hello':
            status(entry, '系统录音通道就绪')
            if entry in drafts:
                send(entry, drafts[entry])
        elif kind == 'record_request' and active is None and not queue:
            queue.append(message)
        elif kind == 'record_cancel' and active == message.get('audioId'):
            canceled = True
        elif kind == 'draft_ack' and entry in drafts and message.get('id') == drafts[entry]['id']:
            acknowledged.add((entry, message['id']))
        elif kind == 'diag_batch':
            send(entry, dict(type='diag_ack', ids=[event['id'] for event in message['events']]))

    session.on_app = on_app
    # Only the two apps signed with our certificate receive a connected state.
    for basic in identities.values():
        session.send(20, 7, blob(8, blob(1, basic) + integer(2, 1)))
    if launch:
        session.send(20, 4, blob(6, blob(1, identities[launch]) + blob(2, b'/pages/index')))
    print('Native recorder bridge ready; wrist button is enabled.', flush=True)
    next_status = time.monotonic() + 15
    try:
        while True:
            item = session.receive()
            if item:
                packet = session.decode(item)
                if packet and packet.get(1) == 20:
                    on_app(packet)
            if not queue:
                if time.monotonic() > next_status:
                    session.status()
                    next_status = time.monotonic() + 15
                continue
            message = queue.pop(0)
            entry = message['entryId']
            active, canceled = message['audioId'], False
            if not active.startswith('audio_') or not active.replace('_', '').isalnum():
                raise ValueError('invalid_audio_id')
            try:
                identifier = session.start_recording()
                print('Wrist capture started:', entry, flush=True)
                try:
                    seconds = min(10, max(1, int(message.get('durationMs', 10000)) // 1000))
                    status(entry, '正在录音，请说话（%d秒）' % seconds, True)
                    end = time.monotonic() + seconds
                    while time.monotonic() < end and not canceled:
                        item = session.receive()
                        if item:
                            packet = session.decode(item)
                            if packet and packet.get(1) == 20:
                                on_app(packet)
                finally:
                    session.stop_recording()
                if canceled:
                    status(entry, '录音已取消')
                    continue
                status(entry, '录音结束，正在传输', True)
                audio = session.download(identifier)
                audio_file = output / (active + '.ogg')
                audio_file.write_bytes(audio)
                print('Verified wrist audio received:', len(audio), 'bytes', flush=True)
                status(entry, '音频已收到，正在转写', True)
                draft_file = output / (active + '.json')
                subprocess.run([config['python'], str(Path(__file__).with_name('transcribe.py')), str(audio_file),
                                '--output', str(draft_file)], check=True, capture_output=True)
                draft = json.loads(draft_file.read_text(encoding='utf-8'))
                drafts[entry] = dict(type='draft_ready', id=active, audioId=active,
                                     text=draft['text'], status='unsubmitted')
                status(entry, '转写完成，尚未执行任务')
                # System recorder may have brought its own page to the foreground.
                session.send(20, 4, blob(6, blob(1, identities[entry]) + blob(2, b'/pages/index')))
                send(entry, drafts[entry])
                end = time.monotonic() + 15
                while (entry, active) not in acknowledged and time.monotonic() < end:
                    item = session.receive()
                    if item:
                        packet = session.decode(item)
                        if packet and packet.get(1) == 20:
                            on_app(packet)
                if (entry, active) not in acknowledged:
                    raise TimeoutError('wrist_draft_not_acknowledged')
                print('Wrist acknowledged transcript; unsubmitted.', flush=True)
                if once:
                    return
            except Exception as error:
                print('Capture failed:', type(error).__name__, str(error), flush=True)
                status(entry, '录音处理失败：' + type(error).__name__)
            finally:
                active = None
    finally:
        session.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--launch', choices=['codex', 'harness'])
    parser.add_argument('--once', action='store_true', help='Exit after one draft message ACK; this does not verify visible UI')
    args = parser.parse_args()
    run(json.loads(args.config.read_text(encoding='utf-8')), args.launch, args.once)
