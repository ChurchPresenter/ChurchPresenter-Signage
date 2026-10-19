// The kiosk page. It keeps no state: it asks the agent for /state every second and draws that.
// A slideshow restarts only when the playlist itself changes.
(function () {
  "use strict";

  var POLL_MS = 1000;
  var layers = [document.getElementById("layer-a"), document.getElementById("layer-b")];
  var front = 0;
  var playlistKey = null;
  var items = [];
  var index = -1;
  var timer = null;
  var reloadToken = null;

  function $(id) { return document.getElementById(id); }
  function show(el, on) { el.classList.toggle("hidden", !on); }

  function stopSlideshow() {
    clearTimeout(timer);
    timer = null;
    layers.forEach(function (layer) {
      layer.classList.remove("shown");
      layer.style.transition = "";
      layer.innerHTML = "";
    });
    playlistKey = null;
    items = [];
    index = -1;
  }

  function element(item, done) {
    if (item.kind === "video") {
      var video = document.createElement("video");
      video.src = item.url;
      video.muted = true;
      video.autoplay = true;
      video.playsInline = true;
      video.onended = done;
      video.onerror = done;
      return video;
    }
    var img = document.createElement("img");
    img.src = item.url;
    img.onerror = done;
    return img;
  }

  function next() {
    clearTimeout(timer);
    if (items.length === 0) return;
    index = (index + 1) % items.length;
    var item = items[index];
    var back = 1 - front;
    var incoming = layers[back];
    var outgoing = layers[front];
    var advanced = false;
    function done() {
      if (advanced) return;
      advanced = true;
      next();
    }
    incoming.innerHTML = "";
    incoming.appendChild(element(item, done));
    var fade = item.transition === "crossfade" ? item.transitionMs : 0;
    incoming.style.transition = outgoing.style.transition = "opacity " + fade + "ms linear";
    incoming.classList.add("shown");
    outgoing.classList.remove("shown");
    setTimeout(function () {
      if (!outgoing.classList.contains("shown")) {
        var old = outgoing.querySelector("video");
        if (old) old.pause();
        outgoing.innerHTML = "";
      }
    }, fade + 50);
    front = back;
    // A single video loops by restarting through next(); a picture waits its duration.
    if (item.kind !== "video" || item.durationMs !== null) {
      timer = setTimeout(done, item.durationMs || 8000);
    }
  }

  function render(state) {
    if (reloadToken !== null && state.reloadToken !== reloadToken) {
      location.reload();
      return;
    }
    reloadToken = state.reloadToken;
    $("stage").className = state.orientation;

    show($("identify"), !!state.identify);
    $("identify-name").textContent = state.identify || "";

    if (state.mode === "playlist") {
      show($("idle"), false);
      var key = state.playlistId + "|" + JSON.stringify(state.items);
      if (key !== playlistKey) {
        stopSlideshow();
        playlistKey = key;
        items = state.items;
        next();
      }
      return;
    }
    stopSlideshow();
    var idle = state.mode === "idle";
    show($("idle"), idle);
    if (idle) {
      $("idle-title").textContent = state.idleTitle || "";
      $("idle-message").textContent = state.message || "";
      var logo = $("idle-logo");
      show(logo, !!state.idleLogoUrl);
      if (state.idleLogoUrl && logo.getAttribute("src") !== state.idleLogoUrl) logo.src = state.idleLogoUrl;
    }
  }

  function poll() {
    fetch("/state", { cache: "no-store" })
      .then(function (response) { return response.json(); })
      .then(render)
      .catch(function () { /* The agent is restarting; keep showing what is up. */ })
      .then(function () { setTimeout(poll, POLL_MS); });
  }

  poll();
})();
