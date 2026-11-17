// A missing native feature may return undefined without throwing in Vela.
// Inspect it before dereferencing; keep the native object out of JSON reports.
function inspectRecord(loadRecord, canIUse) {
  var report = { state: 'unknown', exportType: 'undefined', start: 'undefined', nestedStart: 'absent', keys: '', canIUseRecord: null, canIUseStart: null };
  var selected = null;
  if (typeof canIUse === 'function') {
    try { report.canIUseRecord = canIUse('@system.record'); } catch (error) { report.capabilityQueryError = String(error.message || error).slice(0, 120); }
    try { report.canIUseStart = canIUse('@system.record.start'); } catch (error) { report.capabilityQueryError = String(error.message || error).slice(0, 120); }
  }
  try {
    var raw = loadRecord();
    report.exportType = raw === null ? 'null' : typeof raw;
    if (raw === null || raw === undefined) { report.state = 'module_missing'; return { recorder: null, report: report }; }
    report.start = typeof raw.start;
    var nested = raw.default;
    report.nestedStart = nested === null || nested === undefined ? 'absent' : typeof nested.start;
    // Native methods can live on the prototype; empty keys do not mean absent.
    try { report.keys = Object.keys(raw).join(',').slice(0, 180); } catch (error) {}
    selected = report.start === 'function' ? raw : report.nestedStart === 'function' ? nested : null;
    report.state = selected ? 'method_available' : 'start_missing';
  } catch (error) {
    report.state = 'module_load_error';
    report.error = String(error.message || error).slice(0, 160);
  }
  return { recorder: selected, report: report };
}
module.exports = { inspectRecord: inspectRecord };
