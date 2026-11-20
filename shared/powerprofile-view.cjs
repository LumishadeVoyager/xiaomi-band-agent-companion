function number(metric, format) { return metric && typeof metric.value === 'number' && isFinite(metric.value) && metric.value >= 0 ? format(metric.value) : '未统计'; }
function duration(value) { var m = Math.floor(value / 60); return Math.floor(m / 60) + '小时 ' + (m % 60) + '分'; }
function tokens(value) { return value >= 1000000 ? (value / 1000000).toFixed(1) + 'M' : value >= 1000 ? (value / 1000).toFixed(1) + 'K' : String(value); }
function windowText(window, observedAt, now) {
  if (!window || typeof window.remainingPercent !== 'number') return { text: '未知', progress: 0, reset: '等待额度数据' };
  var stamp = Date.parse(observedAt), stale = !isFinite(stamp) || now - stamp > 180000 || now / 1000 >= window.resetsAt;
  var reset = new Date(window.resetsAt * 1000 + 8 * 3600000).toISOString().slice(5, 16).replace('T', ' ');
  return { text: (stale ? '上次 ' : '剩余 ') + window.remainingPercent.toFixed(0) + '%', progress: Math.max(0, Math.min(100, window.remainingPercent)), reset: (stale ? '待刷新 · ' : '') + reset + ' 重置' };
}
function present(data, now) {
  now = now || Date.now();
  var quota = data.quota || {}, five = windowText(quota.fiveHour, quota.observedAt, now), week = windowText(quota.weekly, quota.observedAt, now);
  var sources = [data.usageSeconds, data.energyKWh, data.gameSeconds, data.aiTokens];
  var stale = sources.some(function (m) { var at = m && Date.parse(m.observedAt); return !at || now - at > 180000; });
  return { day: data.day, usage: number(data.usageSeconds, duration), energy: number(data.energyKWh, function (n) { return n.toFixed(2) + ' 度'; }), game: number(data.gameSeconds, duration), tokens: number(data.aiTokens, tokens), five: five.text, fiveProgress: five.progress, fiveReset: five.reset, week: week.text, weekProgress: week.progress, weekReset: week.reset, freshness: stale ? '部分统计未更新' : '统计已同步' };
}
// Compact screen uses the same sources and expiry rules as the detailed view.
function resetCountdown(resetsAt, now) {
  if (typeof resetsAt !== 'number' || !isFinite(resetsAt)) return '暂无数据';
  var minutes = Math.ceil((resetsAt * 1000 - now) / 60000);
  if (minutes <= 0) return '待刷新';
  if (minutes >= 1440) return Math.floor(minutes / 1440) + '天' + Math.floor(minutes % 1440 / 60) + '小时后重置';
  return Math.floor(minutes / 60) + '小时' + minutes % 60 + '分后重置';
}
function compact(data, now) {
  now = now || Date.now();
  function value(metric, format) { return metric && typeof metric.value === 'number' && isFinite(metric.value) && metric.value >= 0 ? format(metric.value) : '—'; }
  function clock(seconds) { var minutes = Math.floor(seconds / 60); var h=Math.floor(minutes/60),m=minutes%60;return (h<10?'0':'')+h+':'+(m<10?'0':'')+m; }
  function quota(window, at, label) {
    if (!window || typeof window.remainingPercent !== 'number' || !isFinite(window.remainingPercent) || typeof window.resetsAt !== 'number' || !isFinite(window.resetsAt)) return { value: '—', label: label, reset: '暂无数据', progress: 0, stale: true };
    var observed = Date.parse(at), stale = !isFinite(observed) || now-observed > 180000 || now/1000 >= window.resetsAt;
    return { value: Math.max(0,Math.min(100,window.remainingPercent)).toFixed(0)+'%', label: label+(stale?' · 上次':''), reset: resetCountdown(window.resetsAt, now), progress: Math.max(0,Math.min(100,window.remainingPercent)), stale: stale };
  }
  var q=data.quota||{}, five=quota(q.fiveHour,q.observedAt,'5 小时'), week=quota(q.weekly,q.observedAt,'本周');
  var at=Date.parse(data.generatedAt), sync=isFinite(at)?new Date(at+8*3600000).toISOString().slice(11,16):'—';
  return { usageValue: value(data.usageSeconds,clock), energyValue: value(data.energyKWh,function(n){return n>0&&n<0.01?'<0.01':n.toFixed(2);}), gameValue: value(data.gameSeconds,clock), tokenValue: value(data.aiTokens,tokens), fiveNumber: five.value, fiveLabel: five.label, fiveReset: five.reset, fiveProgress: five.progress, weekNumber: week.value, weekLabel: week.label, weekReset: week.reset, weekProgress: week.progress, syncedAt: sync, snapshotStale: !isFinite(at)||now-at>90000, snapshotAgeMinutes: isFinite(at)?Math.max(0,Math.floor((now-at)/60000)):0, partialStale: present(data,now).freshness === '部分统计未更新' || five.stale || week.stale };
}
module.exports = { present, duration, windowText, compact, resetCountdown };
