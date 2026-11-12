// The theme before the first paint (D-497): a classic blocking script in index.html's head, since the CSP allows no
// inline script. The same rules as src/theme.ts, which owns every change after this (theme.test.ts runs both): the
// browser's stored choice, else Blue on a dark device and Light otherwise.
(function () {
  var theme = null
  try {
    var stored = window.localStorage.getItem('finance-tracker.theme')
    if (stored === 'blue' || stored === 'light' || stored === 'dark') theme = stored
  } catch (e) {
    theme = null
  }
  if (theme === null) {
    var dark = false
    try {
      dark = window.matchMedia('(prefers-color-scheme: dark)').matches
    } catch (e) {
      dark = false
    }
    theme = dark ? 'blue' : 'light'
  }
  document.documentElement.setAttribute('data-theme', theme)
})()
