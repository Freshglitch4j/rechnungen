/* Setzt Hell/Dunkel vor dem ersten Rendern, damit nichts aufblitzt. */
try {
  var s = JSON.parse(localStorage.getItem('rechnungen.v1') || '{}'), t = s.theme || 'system';
  if (t === 'system') t = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  document.documentElement.dataset.theme = t;
  var c = t === 'dark' ? '#0B1120' : '#F3F4F6';
  document.querySelectorAll('meta[name=theme-color]').forEach(function (m) { m.content = c; });
} catch (e) { }
