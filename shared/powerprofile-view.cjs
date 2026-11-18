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
module.exports = { present, duration, windowText };
