// ES5 queue: acknowledge only after the phone has durably stored an event.
function Diagnostics(entry, storage, now) {
  this.entry = entry; this.storage = storage; this.now = now || Date.now;
  this.boot = this.now().toString(36) + Math.random().toString(36).slice(2, 7);
  this.sequence = 0; this.events = []; this.loaded = false; this.writing = false; this.dirty = false;
}
Diagnostics.prototype.load = function (done) {
  var self = this;
  function finish(old) {
    if (self.loaded) return;
    self.events = old.concat(self.events).slice(-24); self.loaded = true; self.persist(); if (done) done();
  }
  try { this.storage.get({ key: 'agent_diagnostics_v1', default: '[]', success: function (value) { try { var old = JSON.parse(value); finish(Array.isArray(old) ? old.filter(function (x) { return x && typeof x.id === 'string' && x.entryId === self.entry.id; }) : []); } catch (error) { finish([]); } }, fail: function () { finish([]); } }); }
  catch (error) { finish([]); }
};
Diagnostics.prototype.persist = function () {
  var self = this; this.dirty = true;
  if (!this.loaded || this.writing) return;
  this.writing = true; this.dirty = false;
  function complete() { self.writing = false; if (self.dirty) self.persist(); }
  try { this.storage.set({ key: 'agent_diagnostics_v1', value: JSON.stringify(this.events), success: complete, fail: function () { self.writing = false; } }); }
  catch (error) { this.writing = false; }
};
Diagnostics.prototype.record = function (code, details, level) {
  if (!/^[a-z0-9_]{1,64}$/.test(code)) throw new Error('invalid_diagnostic_code');
  var safe = {};
  // Explicit technical metadata; prompts, audio and transcript fields never enter logs.
  var allowed = ['state', 'exportType', 'start', 'nestedStart', 'canIUseRecord', 'canIUseStart', 'errorCode', 'phase', 'bytes', 'sequence', 'queueLength'];
  for (var i = 0; i < allowed.length; i++) {
    var value = details && details[allowed[i]];
    if (typeof value === 'boolean' || typeof value === 'number' || value === null) safe[allowed[i]] = value;
    else if (typeof value === 'string') safe[allowed[i]] = value.slice(0, 100);
  }
  var event = { id: this.boot + '_' + this.sequence++, entryId: this.entry.id, version: this.entry.version, versionCode: this.entry.versionCode, timestamp: this.now(), code: code, level: level === 'error' ? 'error' : 'info', details: safe };
  this.events.push(event); this.events = this.events.slice(-24); this.persist(); return event.id;
};
Diagnostics.prototype.batch = function () { return this.loaded ? this.events.slice(0, 3) : []; };
Diagnostics.prototype.ack = function (ids) {
  if (!Array.isArray(ids)) return;
  this.events = this.events.filter(function (event) { return ids.indexOf(event.id) === -1; }); this.persist();
};
module.exports = { Diagnostics: Diagnostics };
