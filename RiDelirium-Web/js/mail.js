// Builds mailto links at runtime so the address never appears in the static HTML (keeps scrapers out).
document.querySelectorAll('a[data-u][data-d]').forEach(function (a) {
  var addr = a.dataset.u + '@' + a.dataset.d;
  a.href = 'mailto:' + addr;
  a.textContent = addr;
});
