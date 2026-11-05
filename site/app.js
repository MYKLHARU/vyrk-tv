// Points the download buttons at the newest release's APKs. One request to GitHub's public API;
// no cookies or storage. Without it (offline, rate-limited), every link still goes to the
// Releases page.
(function () {
  "use strict";
  var REPO = "MYKLHARU/vyrk-tv";

  function mb(bytes) { return (bytes / 1048576).toFixed(1) + " MB"; }
  function apkFor(assets, abi) {
    for (var i = 0; i < assets.length; i++) {
      var n = assets[i].name;
      if (/\.apk$/i.test(n) && n.indexOf("-" + abi + ".apk") !== -1) return assets[i];
    }
    return null;
  }

  fetch("https://api.github.com/repos/" + REPO + "/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
    .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function (rel) {
      var assets = rel.assets || [];
      var version = (rel.tag_name || "").replace(/^v/, "");
      var date = rel.published_at ? new Date(rel.published_at).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" }) : "";

      var universal = apkFor(assets, "universal");
      var main = document.getElementById("dl-main");
      var meta = document.getElementById("dl-meta");
      if (universal && main) {
        main.href = universal.browser_download_url;
        main.querySelector("span").textContent = "Download VYRK " + version;
        if (meta) meta.textContent = "Universal APK for any Android TV · " + mb(universal.size) + (date ? " · released " + date : "");
      }

      var line = document.getElementById("release-line");
      if (line && version) {
        line.innerHTML = "";
        line.appendChild(document.createTextNode("Version " + version + (date ? ", released " + date : "") + ". Signed, with "));
        var sums = document.createElement("a");
        sums.href = rel.html_url;
        sums.textContent = "checksums and release notes";
        line.appendChild(sums);
        line.appendChild(document.createTextNode(" on GitHub."));
      }

      var links = document.querySelectorAll(".apk[data-abi]");
      for (var i = 0; i < links.length; i++) {
        var a = apkFor(assets, links[i].getAttribute("data-abi"));
        if (!a) continue;
        links[i].href = a.browser_download_url;
        var size = links[i].querySelector(".apk-size");
        if (size) size.textContent = mb(a.size);
      }
    })
    .catch(function () { /* keep the Releases page links */ });
})();
