// Kept ES5-compatible for the Vela QuickJS application.
var ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function encodeBase64(bytes) {
  var out = '';
  for (var i = 0; i < bytes.length; i += 3) {
    var remaining = bytes.length - i;
    var n = (bytes[i] << 16) | ((remaining > 1 ? bytes[i + 1] : 0) << 8) | (remaining > 2 ? bytes[i + 2] : 0);
    out += ALPHABET[(n >>> 18) & 63] + ALPHABET[(n >>> 12) & 63];
    out += remaining > 1 ? ALPHABET[(n >>> 6) & 63] : '=';
    out += remaining > 2 ? ALPHABET[n & 63] : '=';
  }
  return out;
}
function validateIdentity(entryId, audioId) {
  if (entryId !== 'codex' && entryId !== 'harness') throw new Error('invalid_entry');
  if (typeof audioId !== 'string' || !/^[a-z0-9_-]{8,64}$/.test(audioId)) throw new Error('invalid_audio_id');
}
function envelope(entryId, audioId, type, extra) {
  validateIdentity(entryId, audioId);
  var value = { protocol: 1, entryId: entryId, audioId: audioId, type: type };
  for (var key in extra) if (Object.prototype.hasOwnProperty.call(extra, key)) value[key] = extra[key];
  return value;
}
module.exports = { encodeBase64: encodeBase64, envelope: envelope, validateIdentity: validateIdentity };
