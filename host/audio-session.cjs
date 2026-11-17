const { createHash } = require('node:crypto');
const { validateIdentity } = require('../shared/audio-wire.cjs');

class AudioSession {
  constructor(begin) {
    validateIdentity(begin.entryId, begin.audioId);
    if (begin.protocol !== 1 || begin.type !== 'audio_begin' || begin.sampleRate !== 16000 || begin.channels !== 1 || begin.encoding !== 'pcm_s16le') throw new Error('unsupported_audio');
    this.entryId = begin.entryId;
    this.audioId = begin.audioId;
    this.chunks = [];
    this.bytes = 0;
    this.ended = false;
  }
  accept(message) {
    if (message.protocol !== 1 || message.entryId !== this.entryId || message.audioId !== this.audioId) throw new Error('identity_mismatch');
    if (message.type === 'audio_chunk') {
      if (!Number.isSafeInteger(message.sequence) || message.sequence < 0 || typeof message.data !== 'string' || message.data.length > 4096 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(message.data)) throw new Error('invalid_chunk');
      const data = Buffer.from(message.data, 'base64');
      if (!data.length || data.length % 2 || data.toString('base64') !== message.data) throw new Error('invalid_pcm');
      if (message.sequence < this.chunks.length) {
        if (!this.chunks[message.sequence].equals(data)) throw new Error('conflicting_duplicate');
        return { nextSequence: this.chunks.length, duplicate: true };
      }
      if (this.ended) throw new Error('already_ended');
      if (message.sequence !== this.chunks.length) throw new Error('missing_chunk');
      if (this.bytes + data.length > 16000 * 2 * 60) throw new Error('recording_too_large');
      this.chunks.push(data);
      this.bytes += data.length;
      return { nextSequence: this.chunks.length };
    }
    if (message.type === 'audio_end') {
      if (!this.bytes || message.chunks !== this.chunks.length || message.bytes !== this.bytes) throw new Error('incomplete_recording');
      this.ended = true;
      return { complete: true, bytes: this.bytes, sha256: createHash('sha256').update(Buffer.concat(this.chunks)).digest('hex') };
    }
    throw new Error('invalid_message');
  }
  wav() {
    if (!this.ended) throw new Error('not_complete');
    const header = Buffer.alloc(44);
    header.write('RIFF'); header.writeUInt32LE(this.bytes + 36, 4); header.write('WAVEfmt ', 8);
    header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
    header.writeUInt32LE(16000, 24); header.writeUInt32LE(32000, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
    header.write('data', 36); header.writeUInt32LE(this.bytes, 40);
    return Buffer.concat([header, ...this.chunks]);
  }
}
module.exports = { AudioSession };
