// Farbschema vor dem ersten Rendern setzen, damit nichts aufblitzt (eingebunden in index.html)
;(function () {
  try {
    var stored = localStorage.getItem('theme')
    var dark = stored ? stored === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches
    if (dark) document.documentElement.classList.add('dark')
  } catch (e) {}
})()
