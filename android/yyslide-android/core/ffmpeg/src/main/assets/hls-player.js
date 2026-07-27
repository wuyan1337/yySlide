(() => {
  'use strict';

  const video = document.getElementById('v');
  const unmuteBtn = document.getElementById('unmute');
  const statusEl = document.getElementById('status');
  const playlistEl = document.getElementById('playlist');
  const itemsEl = document.getElementById('playlist-items');
  const toggleBtn = document.getElementById('playlist-toggle');
  const token = new URLSearchParams(window.location.search).get('token');

  function showStatus(message, isError) {
    statusEl.textContent = message;
    statusEl.className = 'visible' + (isError ? ' error' : '');
  }

  function authenticated(path) {
    const url = new URL(path, window.location.origin);
    url.searchParams.set('token', token);
    return url.pathname + url.search;
  }

  if (!token) {
    showStatus('This stream URL is missing its access token.', true);
    return;
  }

  function hideStatus() { statusEl.className = ''; }
  function showUnmute() { unmuteBtn.classList.add('visible'); }
  function hideUnmute() { unmuteBtn.classList.remove('visible'); }

  unmuteBtn.addEventListener('click', () => {
    video.muted = false;
    video.play().catch(() => {});
    hideUnmute();
  });
  video.addEventListener('volumechange', () => {
    if (!video.muted) hideUnmute();
  });
  video.addEventListener('playing', () => {
    hideStatus();
    if (video.muted) showUnmute();
  });
  video.addEventListener('error', () => {
    const error = video.error;
    showStatus('Video error: ' + (error ? 'code ' + error.code : 'unknown'), true);
  });

  function tryPlay() {
    video.muted = true;
    video.play().catch((error) => {
      console.warn('autoplay failed:', error);
      showStatus('Tap the play button to start', false);
    });
  }

  let hls = null;
  const nativeHls = !window.Hls?.isSupported?.() &&
    video.canPlayType('application/vnd.apple.mpegurl');

  function loadSource() {
    const source = authenticated('/stream.m3u8');
    if (nativeHls) {
      video.src = source + '&t=' + Date.now();
      video.addEventListener('loadedmetadata', tryPlay, { once: true });
    } else if (window.Hls && Hls.isSupported()) {
      if (hls) {
        try { hls.destroy(); } catch (_) {}
      }
      // Keeping workers disabled lets the page enforce worker-src 'none'.
      hls = new Hls({ enableWorker: false });
      hls.on(Hls.Events.ERROR, (_, data) => {
        console.warn('hls.js error:', data);
        if (data.fatal) showStatus('Stream error: ' + data.details, true);
      });
      hls.on(Hls.Events.MANIFEST_PARSED, tryPlay);
      hls.loadSource(source);
      hls.attachMedia(video);
    } else {
      showStatus('HLS is not supported in this browser', true);
    }
  }

  toggleBtn.addEventListener('click', () => playlistEl.classList.toggle('visible'));

  function renderPlaylist(data) {
    itemsEl.replaceChildren();
    if (!data.items || data.items.length === 0) return;
    toggleBtn.classList.add('visible');
    playlistEl.classList.add('visible');
    data.items.forEach((title, index) => {
      const row = document.createElement('div');
      row.className = 'item' + (index === data.current ? ' current' : '');
      row.textContent = (index + 1) + '. ' + title;
      row.addEventListener('click', () => switchTo(index));
      itemsEl.appendChild(row);
    });
  }

  async function switchTo(index) {
    try {
      showStatus('Loading item ' + (index + 1) + '…', false);
      const switched = await fetch(authenticated('/switch?i=' + encodeURIComponent(index)), {
        cache: 'no-store',
      });
      if (!switched.ok) throw new Error('HTTP ' + switched.status);
      for (let attempt = 0; attempt < 30; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 200));
        const response = await fetch(authenticated('/stream.m3u8?t=' + Date.now()), {
          cache: 'no-store',
        });
        if (response.ok && (await response.text()).includes('seg')) break;
      }
      loadSource();
      refreshPlaylist();
    } catch (error) {
      showStatus('Switch failed: ' + error, true);
    }
  }

  async function refreshPlaylist() {
    try {
      const response = await fetch(authenticated('/playlist.json'), { cache: 'no-store' });
      if (response.ok) renderPlaylist(await response.json());
    } catch (_) {}
  }

  loadSource();
  refreshPlaylist();
})();
