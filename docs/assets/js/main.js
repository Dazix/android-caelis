(() => {
  'use strict';

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const STORAGE_KEY = 'caelis-lang';

  /* ---------- Language ----------
     Every language is its own static page (for search engines). The English root sends a
     first-time visitor whose browser prefers another supported language to that page once;
     a language picked by hand is remembered and always wins. */
  const picker = document.querySelector('.lang-picker');
  const langLinks = picker ? [...picker.querySelectorAll('a[data-lang]')] : [];

  function storedLang() {
    try { return localStorage.getItem(STORAGE_KEY); } catch (e) { return null; }
  }
  function storeLang(lang) {
    try { localStorage.setItem(STORAGE_KEY, lang); } catch (e) { /* private mode: not remembered */ }
  }

  if (document.body.dataset.lang === 'en' && !storedLang()) {
    const available = langLinks.map((a) => a.dataset.lang);
    for (const tag of navigator.languages || [navigator.language]) {
      const base = String(tag).toLowerCase().split('-')[0];
      if (base === 'en') break;
      if (available.includes(base)) {
        const target = langLinks.find((a) => a.dataset.lang === base);
        location.replace(target.href + location.hash);
        return;
      }
    }
  }

  langLinks.forEach((a) => a.addEventListener('click', () => storeLang(a.dataset.lang)));
  if (picker) {
    document.addEventListener('click', (e) => { if (!picker.contains(e.target)) picker.open = false; });
    picker.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && picker.open) { picker.open = false; picker.querySelector('summary').focus(); }
    });
  }

  /* ---------- Header & navigation ---------- */
  const header = document.querySelector('.site-header');
  const nav = document.getElementById('site-nav');
  const toggle = document.querySelector('.nav-toggle');

  const onScroll = () => header.classList.toggle('is-scrolled', window.scrollY > 24);
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });

  function setMenu(open) {
    toggle.setAttribute('aria-expanded', String(open));
    nav.classList.toggle('is-open', open);
  }
  toggle.addEventListener('click', () => setMenu(toggle.getAttribute('aria-expanded') !== 'true'));
  nav.addEventListener('click', (e) => { if (e.target.closest('.nav-links a')) setMenu(false); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && nav.classList.contains('is-open')) { setMenu(false); toggle.focus(); }
  });

  // Highlight the section in view.
  const navLinks = [...nav.querySelectorAll('.nav-links a')];
  const sections = navLinks.map((a) => document.querySelector(a.getAttribute('href'))).filter(Boolean);
  if ('IntersectionObserver' in window) {
    const spy = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        navLinks.forEach((a) => {
          if (a.getAttribute('href') === `#${entry.target.id}`) a.setAttribute('aria-current', 'true');
          else a.removeAttribute('aria-current');
        });
      });
    }, { rootMargin: '-45% 0px -50% 0px' });
    sections.forEach((s) => spy.observe(s));
  }

  /* ---------- Reveal on scroll ---------- */
  const reveals = document.querySelectorAll('.reveal');
  // Stagger siblings that share a parent.
  reveals.forEach((el) => {
    const siblings = [...el.parentElement.children].filter((c) => c.classList.contains('reveal'));
    const i = siblings.indexOf(el);
    if (i > 0) el.style.setProperty('--delay', `${Math.min(i, 5) * 0.09}s`);
  });
  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-visible');
        countUp(entry.target);
        io.unobserve(entry.target);
      });
    }, { threshold: 0.15, rootMargin: '0px 0px -40px 0px' });
    reveals.forEach((el) => io.observe(el));
  } else {
    reveals.forEach((el) => el.classList.add('is-visible'));
  }

  /* ---------- Count-up numbers ---------- */
  function countUp(scope) {
    const el = scope.querySelector('[data-count]');
    if (!el || reduceMotion.matches) return;
    const target = Number(el.dataset.count);
    const start = performance.now();
    const duration = 1400;
    const step = (now) => {
      const t = Math.min(1, (now - start) / duration);
      el.textContent = String(Math.round(target * (1 - Math.pow(1 - t, 3))));
      if (t < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  /* ---------- Card spotlight ---------- */
  document.querySelectorAll('.card').forEach((card) => {
    card.addEventListener('pointermove', (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty('--mx', `${e.clientX - r.left}px`);
      card.style.setProperty('--my', `${e.clientY - r.top}px`);
    });
  });

  /* ---------- Gallery ---------- */
  const carousel = document.querySelector('.carousel');
  if (carousel) {
    const track = carousel.querySelector('.gallery-track');
    const slides = [...track.querySelectorAll('.slide')];
    const prev = carousel.querySelector('[data-gallery="prev"]');
    const next = carousel.querySelector('[data-gallery="next"]');
    const dotsBox = carousel.querySelector('.carousel-dots');
    const label = carousel.dataset.dotLabel || '{n} / {total}';
    let active = 0;

    const dots = slides.map((_, i) => {
      const dot = document.createElement('button');
      dot.type = 'button';
      dot.setAttribute('aria-controls', track.id);
      dot.setAttribute('aria-label', label.replace('{n}', i + 1).replace('{total}', slides.length));
      dot.addEventListener('click', () => goTo(i));
      dotsBox.appendChild(dot);
      return dot;
    });

    function goTo(i) {
      const slide = slides[Math.max(0, Math.min(slides.length - 1, i))];
      const left = slide.offsetLeft - (track.clientWidth - slide.offsetWidth) / 2;
      track.scrollTo({ left, behavior: reduceMotion.matches ? 'auto' : 'smooth' });
    }

    function setActive(i) {
      active = i;
      slides.forEach((s, j) => s.classList.toggle('is-active', j === i));
      dots.forEach((d, j) => (j === i ? d.setAttribute('aria-current', 'true') : d.removeAttribute('aria-current')));
      prev.disabled = i === 0;
      next.disabled = i === slides.length - 1;
    }

    // The active slide is the one nearest the middle of the track.
    function syncActive() {
      const middle = track.scrollLeft + track.clientWidth / 2;
      let best = 0;
      let bestDist = Infinity;
      slides.forEach((s, i) => {
        const d = Math.abs(s.offsetLeft + s.offsetWidth / 2 - middle);
        if (d < bestDist) { bestDist = d; best = i; }
      });
      if (best !== active || !slides[best].classList.contains('is-active')) setActive(best);
    }

    let ticking = false;
    track.addEventListener('scroll', () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => { ticking = false; syncActive(); });
    }, { passive: true });
    window.addEventListener('resize', () => goTo(active));

    prev.addEventListener('click', () => goTo(active - 1));
    next.addEventListener('click', () => goTo(active + 1));
    slides.forEach((s, i) => s.addEventListener('click', () => { if (i !== active) goTo(i); }));
    track.addEventListener('keydown', (e) => {
      const keys = { ArrowLeft: active - 1, ArrowRight: active + 1, Home: 0, End: slides.length - 1 };
      if (e.key in keys) { e.preventDefault(); goTo(keys[e.key]); }
    });

    setActive(0);
    syncActive();
  }

  /* ---------- Hero sky: day and night, stars ----------
     One cycle is a day: the Sun rises from behind the ridge, crosses the arc and sets, the sky
     glows at both horizons, then the stars come out and the Moon takes the same arc. */
  const hero = document.querySelector('.hero');
  const canvas = hero && hero.querySelector('.starfield');
  if (canvas && canvas.getContext) {
    const ctx = canvas.getContext('2d');
    const arc = hero.querySelector('.sun-arc-path');
    const sun = hero.querySelector('.sky-sun');
    const moon = hero.querySelector('.sky-moon');
    const skyDay = hero.querySelector('.sky-day');
    const skyWarm = hero.querySelector('.sky-warm');
    const sunLow = [...hero.querySelectorAll('.sun-low')];
    const sunScale = hero.querySelector('.sun-scale');
    const arcLength = arc ? arc.getTotalLength() : 0;

    const CYCLE_MS = 40000;
    const START_PHASE = 0.32; // afternoon, so the first thing a visitor sees is the Sun heading for the ridge
    const STILL_PHASE = 0.47; // reduced motion: a fixed sunset
    // ?sky=0.25 freezes the sky at that point of the day (0 sunrise, 0.25 noon, 0.5 sunset, 0.75 midnight).
    const pinned = parseFloat(new URLSearchParams(location.search).get('sky'));
    const PINNED = Number.isFinite(pinned) ? ((pinned % 1) + 1) % 1 : null;
    const smoothstep = (a, b, x) => {
      const t = Math.min(1, Math.max(0, (x - a) / (b - a)));
      return t * t * (3 - 2 * t);
    };

    let stars = [];
    let width = 0;
    let height = 0;
    let running = false;
    let visible = true;
    let rafId = 0;
    let shooting = null;
    let starLight = 1;
    let t0 = null;
    let pausedAt = 0; // ms of the cycle already shown when the loop stopped

    // In the SVG's units: the ridges stand at about y 450, the arc's top at about y 165.
    const HORIZON_Y = 450;
    const ZENITH_Y = 165;

    function setSky(phase) {
      const isDay = phase < 0.5;
      const body = isDay ? sun : moon;
      const progress = isDay ? phase / 0.5 : (phase - 0.5) / 0.5;
      // Height as seen: 0 on the ridge, 1 at the top of the arc, negative behind the hills.
      let altitude;
      if (arcLength) {
        const p = arc.getPointAtLength(progress * arcLength);
        body.style.transform = `translate(${p.x}px, ${p.y}px)`;
        altitude = (HORIZON_Y - p.y) / (HORIZON_Y - ZENITH_Y);
      } else {
        altitude = Math.sin(Math.PI * progress);
      }
      // The arc's ends lie below the ridges, so a body that switches there is never seen switching.
      sun.style.opacity = isDay ? '1' : '0';
      moon.style.opacity = isDay ? '0' : '1';

      // At night the Sun sinks further the higher the Moon stands; both meet at the arc's end (y 560),
      // so dusk and dawn join without a jump.
      const END = (HORIZON_Y - 560) / (HORIZON_Y - ZENITH_Y);
      const sunHeight = isDay ? altitude : END - (altitude - END);
      skyDay.style.opacity = smoothstep(0, 0.6, sunHeight).toFixed(3);
      skyWarm.style.opacity = (0.9 * Math.exp(-(((sunHeight - 0.02) / 0.3) ** 2))).toFixed(3);
      starLight = 1 - 0.95 * smoothstep(-0.15, 0.3, sunHeight);

      // Near the horizon the Sun turns red and looks larger, as it does through more air.
      const low = isDay ? 1 - smoothstep(0.05, 0.55, altitude) : 1;
      sunLow.forEach((el) => { el.style.opacity = low.toFixed(3); });
      sunScale.style.transform = `scale(${(1 + 0.18 * low).toFixed(3)})`;
    }

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const count = Math.round(Math.min(320, (width * height) / 4200));
      stars = Array.from({ length: count }, () => {
        const warm = Math.random() < 0.18;
        return {
          x: Math.random() * width,
          y: Math.random() * height * 0.85,
          r: Math.random() < 0.08 ? 1.3 + Math.random() * 0.9 : 0.4 + Math.random() * 0.8,
          base: 0.35 + Math.random() * 0.6,
          speed: 0.4 + Math.random() * 1.6,
          phase: Math.random() * Math.PI * 2,
          color: warm ? '255, 214, 160' : '220, 230, 255',
        };
      });
      if (!running) drawStill();
    }

    function drawStars(t) {
      ctx.clearRect(0, 0, width, height);
      if (starLight < 0.02) return;
      const still = reduceMotion.matches;
      for (const s of stars) {
        const twinkle = still ? 1 : 0.65 + 0.35 * Math.sin(t * s.speed + s.phase);
        let x = s.x + (still ? 0 : ((t * 2) % width) * s.r * 0.15);
        if (x > width) x -= width;
        ctx.globalAlpha = s.base * twinkle * starLight;
        ctx.fillStyle = `rgb(${s.color})`;
        ctx.beginPath();
        ctx.arc(x, s.y, s.r, 0, Math.PI * 2);
        ctx.fill();
      }
      if (!still && starLight > 0.6) drawShootingStar();
      ctx.globalAlpha = 1;
    }

    function drawShootingStar() {
      if (!shooting && Math.random() < 0.004) {
        shooting = { x: Math.random() * width * 0.7 + width * 0.2, y: Math.random() * height * 0.3, life: 0 };
      }
      if (!shooting) return;
      shooting.life += 1;
      const p = shooting.life / 50;
      const len = 120;
      const x = shooting.x - p * 260;
      const y = shooting.y + p * 130;
      const grad = ctx.createLinearGradient(x, y, x + len, y - len / 2);
      grad.addColorStop(0, 'rgba(255, 240, 210, 0.9)');
      grad.addColorStop(1, 'rgba(255, 240, 210, 0)');
      ctx.globalAlpha = Math.sin(Math.PI * Math.min(1, p)) * starLight;
      ctx.strokeStyle = grad;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x + len, y - len / 2);
      ctx.stroke();
      if (p >= 1) shooting = null;
    }

    function currentPhase(now) { return (START_PHASE + (now - t0) / CYCLE_MS) % 1; }
    function drawStill() {
      let phase = START_PHASE + pausedAt / CYCLE_MS;
      if (reduceMotion.matches) phase = STILL_PHASE;
      if (PINNED !== null) phase = PINNED;
      setSky(phase % 1);
      drawStars(0);
    }

    function loop(now) {
      if (t0 === null) t0 = now;
      setSky(currentPhase(now));
      drawStars(now / 1000);
      rafId = requestAnimationFrame(loop);
    }
    function start() {
      if (running || reduceMotion.matches || PINNED !== null || !visible || document.hidden) return;
      running = true;
      // Resume where the day was, not where the wall clock says it would be.
      if (t0 !== null) t0 = performance.now() - pausedAt;
      rafId = requestAnimationFrame(loop);
    }
    function stop() {
      if (!running) return;
      running = false;
      if (t0 !== null) pausedAt = performance.now() - t0;
      cancelAnimationFrame(rafId);
    }

    resize();
    window.addEventListener('resize', resize);
    document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
    reduceMotion.addEventListener?.('change', () => { stop(); drawStill(); start(); });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(([entry]) => {
        visible = entry.isIntersecting;
        visible ? start() : stop();
      }).observe(canvas);
    }
    start();
  }
})();
