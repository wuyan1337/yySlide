document.documentElement.classList.add("js");

const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

if ("scrollRestoration" in history) {
  history.scrollRestoration = "manual";
}

if (!location.hash) {
  window.scrollTo(0, 0);
}

const scenes = [...document.querySelectorAll("[data-scene]")];
if (reducedMotion || !("IntersectionObserver" in window)) {
  scenes.forEach((scene) => scene.classList.add("in"));
} else {
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          observer.unobserve(entry.target);
        }
      }
    },
    { threshold: 0.18 },
  );
  scenes.forEach((scene) => observer.observe(scene));
}

document.getElementById("year").textContent = String(new Date().getFullYear());

if (!reducedMotion) {
  const floats = [...document.querySelectorAll("[data-float]")];
  let ticking = false;
  const updateFloats = () => {
    const y = window.scrollY || 0;
    floats.forEach((element, index) => {
      const speed = element.dataset.float === "fast" ? 0.055 : element.dataset.float === "mid" ? 0.035 : 0.022;
      element.style.transform = `translate3d(0,${Math.sin((y + index * 120) * 0.004) * 12 - y * speed}px,0)`;
    });
    ticking = false;
  };
  window.addEventListener(
    "scroll",
    () => {
      if (!ticking) {
        window.requestAnimationFrame(updateFloats);
        ticking = true;
      }
    },
    { passive: true },
  );
  updateFloats();

  const spotlight = document.querySelector(".cursor-spotlight");
  let mouseX = innerWidth * 0.5;
  let mouseY = innerHeight * 0.35;
  let targetX = mouseX;
  let targetY = mouseY;
  window.addEventListener(
    "pointermove",
    (event) => {
      targetX = event.clientX;
      targetY = event.clientY;
      document.documentElement.style.setProperty("--cx", `${event.clientX}px`);
      document.documentElement.style.setProperty("--cy", `${event.clientY}px`);
    },
    { passive: true },
  );
  const updatePointer = () => {
    mouseX += (targetX - mouseX) * 0.12;
    mouseY += (targetY - mouseY) * 0.12;
    if (spotlight) {
      spotlight.style.setProperty("--mx", `${mouseX}px`);
      spotlight.style.setProperty("--my", `${mouseY}px`);
    }
    window.requestAnimationFrame(updatePointer);
  };
  updatePointer();

  const stage = document.querySelector(".reactbits-stage");
  const card = document.querySelector(".desktop-card");
  if (stage && card) {
    stage.addEventListener(
      "pointermove",
      (event) => {
        const bounds = stage.getBoundingClientRect();
        const x = (event.clientX - bounds.left) / bounds.width - 0.5;
        const y = (event.clientY - bounds.top) / bounds.height - 0.5;
        card.style.setProperty(
          "transform",
          `rotateX(${4 - y * 7}deg) rotateY(${-9 + x * 10}deg) translateZ(0)`,
          "important",
        );
      },
      { passive: true },
    );
    stage.addEventListener("pointerleave", () => {
      card.style.setProperty("transform", "rotateX(4deg) rotateY(-9deg)", "important");
    });
  }

  const canvas = document.querySelector(".mesh-canvas");
  const context = canvas?.getContext("2d");
  if (canvas && context) {
    let width = 0;
    let height = 0;
    let pixelRatio = 1;
    let time = 0;
    const resize = () => {
      pixelRatio = Math.min(devicePixelRatio || 1, 2);
      width = canvas.width = innerWidth * pixelRatio;
      height = canvas.height = innerHeight * pixelRatio;
      canvas.style.width = `${innerWidth}px`;
      canvas.style.height = `${innerHeight}px`;
    };
    const draw = () => {
      time += 0.006;
      context.clearRect(0, 0, width, height);
      for (let index = 0; index < 34; index += 1) {
        const x = (Math.sin(time + index * 1.7) * 0.5 + 0.5) * width;
        const y = (Math.cos(time * 0.8 + index * 1.13) * 0.5 + 0.5) * height;
        const radius = (80 + Math.sin(time * 2 + index) * 34) * pixelRatio;
        const gradient = context.createRadialGradient(x, y, 0, x, y, radius);
        const color = index % 3 === 0 ? "122,167,255" : index % 3 === 1 ? "255,208,232" : "103,240,180";
        gradient.addColorStop(0, `rgba(${color},.08)`);
        gradient.addColorStop(1, `rgba(${color},0)`);
        context.fillStyle = gradient;
        context.beginPath();
        context.arc(x, y, radius, 0, Math.PI * 2);
        context.fill();
      }
      window.requestAnimationFrame(draw);
    };
    resize();
    window.addEventListener("resize", resize, { passive: true });
    draw();
  }
}
