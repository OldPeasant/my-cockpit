// Applies the stored theme before the first paint (loaded synchronously from index.html, a file
// rather than an inline script so a strict CSP needs no hash). ThemeService owns the same key.
(function () {
  var mode = 'system';
  try {
    var stored = window.localStorage.getItem('cockpit.theme');
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      mode = stored;
    }
  } catch (e) {
    // Storage unavailable: follow the system preference.
  }
  document.documentElement.setAttribute('data-theme', mode);
})();
