import { MultiPersonCounter } from "./squat.js";
import { AndroidTv, FakeTv, LocalBackend, ServerBackend } from "./backend.js";

const MP_VERSION = "1.0.1";
// web/vendor 에 받아둔 파일이 있으면 그걸 쓰고(오프라인), 없으면 CDN에서 받는다
const VISION_SOURCES = ["./vendor/tasks-vision", `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VERSION}`];
const MODEL_SOURCES = [
  "./vendor/pose_landmarker_lite.task",
  "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
];

const $ = (id) => document.getElementById(id);
const video = $("video");
const canvas = $("overlay");
const ctx = canvas.getContext("2d");

let state = null;
let settings = null;
let landmarker = null;
let drawing = null;
let PoseLandmarker = null;
let pendingReps = 0;
let soundOn = true;
try { soundOn = localStorage.getItem("tvsquat.sound") !== "off"; } catch {}
const counter = new MultiPersonCounter();

// 안드로이드 앱 안이거나 ?local 이면 서버 없이 이 기기에서 판정 (?local 은 가상 TV로 시험)
const nativeTv = window.AndroidTv ? new AndroidTv(window.AndroidTv) : null;
const backend = nativeTv || new URLSearchParams(location.search).has("local")
  ? new LocalBackend(nativeTv || new FakeTv())
  : new ServerBackend();
document.body.dataset.mode = backend.local ? "local" : "server";

// ---------- 서버 통신 ----------

async function flushReps() {
  if (!pendingReps) return;
  const n = pendingReps;
  pendingReps = 0;
  try {
    applyState(await backend.addReps(n));
  } catch {
    pendingReps += n; // 서버가 잠깐 끊겨도 개수를 잃지 않게
  }
}

async function poll() {
  try {
    await flushReps();
    applyState(await backend.state());
    $("conn").textContent = "";
  } catch {
    $("conn").textContent = "⚠ 서버에 연결할 수 없어요 (python -m tvsquat 실행 중인지 확인)";
  }
}

// ---------- 화면 ----------

const fmtTime = (s) => {
  s = Math.max(0, Math.ceil(s));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

let lastWarned = null;
let lastStateName = null;

function applyState(s) {
  const prev = state;
  state = s;
  const newSettings = s.settings;
  if (!settings || settings.players !== newSettings.players) {
    landmarker?.setOptions({ numPoses: newSettings.players });
  }
  if (!settings || settings.down_angle !== newSettings.down_angle || settings.up_angle !== newSettings.up_angle) {
    counter.setThresholds(newSettings.down_angle, newSettings.up_angle);
  }
  const cameraChanged = settings && newSettings.camera && settings.camera !== newSettings.camera;
  settings = newSettings;
  if (cameraChanged && video.srcObject) initCamera().catch((e) => console.error(e));
  if (s.tv_status) renderTvStatus(s.tv_status);

  $("app").dataset.state = s.state;
  const locked = s.state === "locked";
  const reps = locked ? s.unlock_reps : s.reps;
  const quota = locked ? s.unlock_needed : s.quota;
  $("reps").textContent = reps;
  $("quota").textContent = quota;
  $("progress-bar").style.width = `${Math.min(100, (reps / Math.max(1, quota)) * 100)}%`;
  $("remaining").textContent = locked ? "--:--" : fmtTime(s.remaining_seconds);

  let status, message;
  const left = Math.max(0, s.quota - s.reps);
  if (locked) {
    status = "📺 TV 꺼짐";
    message = settings.auto_turn_on
      ? `스쿼트 ${Math.max(0, s.unlock_needed - s.unlock_reps)}개를 더 하면 TV가 다시 켜져요!`
      : "엄마·아빠가 '재개'를 눌러야 TV가 켜져요.";
  } else if (s.state === "paused") {
    status = "⏸ 일시정지";
    message = s.pause_reason === "tv_off" ? "TV가 꺼져 있어서 타이머가 멈췄어요." : "부모님이 일시정지했어요.";
  } else if (left === 0) {
    status = "✅ 통과!";
    message = "이번 구간 할당량을 채웠어요. 마음껏 보세요!";
  } else {
    status = s.remaining_seconds <= 60 ? "⚠️ 서둘러요!" : "🏃 스쿼트 타임";
    message = `${fmtTime(s.remaining_seconds)} 안에 ${left}개 더 해야 TV가 안 꺼져요.`;
  }
  $("status").textContent = status;
  $("message").textContent = message;
  $("rule").textContent = `규칙: ${settings.seconds_per_squat}초에 1개 · ${settings.window_minutes}분마다 ${settings.quota}개 검사` +
    (settings.players > 1 ? ` (${settings.players}명 합계)` : "") + ` · ${s.tv}`;
  $("btn-sound").textContent = soundOn ? "소리 켜짐" : "소리 꺼짐";
  $("btn-sound").setAttribute("aria-pressed", String(soundOn));

  $("history").innerHTML = s.history.slice(-8).map((h) =>
    `<span class="${h.passed ? "ok" : "fail"}" title="${h.reps}/${h.quota}">${h.passed ? "○" : "✕"}</span>`).join("");

  // 음성 안내
  if (prev && lastStateName !== s.state) {
    if (s.state === "locked") say("할당량을 못 채워서 TV를 껐어요. 스쿼트를 하면 다시 켜져요.");
    else if (lastStateName === "locked" && s.state === "running") say("잘했어요! TV를 다시 켤게요.");
  }
  lastStateName = s.state;
  if (s.state === "running" && left > 0 && s.remaining_seconds <= 60 && lastWarned !== s.history.length) {
    lastWarned = s.history.length;
    say(`1분 남았어요! ${left}개 더 해야 해요.`);
  }
}

function say(text) {
  if (!soundOn) return;
  if (nativeTv) return nativeTv.speak(text); // 안드로이드 WebView에는 speechSynthesis가 없음
  if (!("speechSynthesis" in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = "ko-KR";
  speechSynthesis.cancel();
  speechSynthesis.speak(u);
}

let audio = null;
function beep() {
  if (!soundOn) return;
  try {
    audio ??= new AudioContext();
    const o = audio.createOscillator(), g = audio.createGain();
    o.frequency.value = 880;
    g.gain.setValueAtTime(0.2, audio.currentTime);
    g.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + 0.25);
    o.connect(g).connect(audio.destination);
    o.start();
    o.stop(audio.currentTime + 0.25);
  } catch {}
}

function onRep() {
  pendingReps += 1;
  if (state) {
    // 서버 응답 전에 바로 화면에 반영
    const locked = state.state === "locked";
    const next = (locked ? state.unlock_reps : state.reps) + pendingReps;
    $("reps").textContent = next;
    if (soundOn && (nativeTv || "speechSynthesis" in window)) say(String(next));
    else beep();
  }
  $("app").classList.remove("flash");
  void $("app").offsetWidth;
  $("app").classList.add("flash");
  flushReps();
}

// ---------- 카메라 + 포즈 인식 ----------

async function importFirst(urls) {
  let lastErr;
  for (const url of urls) {
    try { return { mod: await import(url), url }; } catch (e) { lastErr = e; }
  }
  throw lastErr;
}

async function firstReachable(urls) {
  for (const url of urls) {
    try {
      const r = await fetch(url, { method: "HEAD" });
      if (r.ok) return url;
    } catch {}
  }
  return urls[urls.length - 1];
}

async function initPose() {
  const { mod, url } = await importFirst(VISION_SOURCES.map((b) => `${b}/vision_bundle.mjs`));
  PoseLandmarker = mod.PoseLandmarker;
  const base = url.replace(/\/vision_bundle\.mjs$/, "");
  const fileset = await mod.FilesetResolver.forVisionTasks(`${base}/wasm`);
  const modelAssetPath = await firstReachable(MODEL_SOURCES);
  const opts = (delegate) => ({
    baseOptions: { modelAssetPath, delegate },
    runningMode: "VIDEO",
    numPoses: settings?.players ?? 1,
  });
  try {
    landmarker = await PoseLandmarker.createFromOptions(fileset, opts("GPU"));
  } catch {
    landmarker = await PoseLandmarker.createFromOptions(fileset, opts("CPU"));
  }
  drawing = new mod.DrawingUtils(ctx);
}

async function initCamera() {
  video.srcObject?.getTracks().forEach((t) => t.stop());
  const facingMode = settings?.camera || "user";
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode },
    audio: false,
  });
  video.srcObject = stream;
  // 전면 카메라만 거울처럼 뒤집어서 보여준다
  $("app").classList.toggle("mirror", facingMode === "user");
  await video.play();
}

let lastVideoTime = -1;
function loop() {
  if (landmarker && video.readyState >= 2 && video.currentTime !== lastVideoTime) {
    lastVideoTime = video.currentTime;
    const t = performance.now();
    const result = landmarker.detectForVideo(video, t);
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const aspect = video.videoWidth / video.videoHeight;
    const reps = counter.update(result.landmarks, t, aspect);
    for (let i = 0; i < reps; i++) onRep();
    draw(result.landmarks);
  }
  requestAnimationFrame(loop);
}

function draw(poses) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  for (const lm of poses) {
    drawing.drawConnectors(lm, PoseLandmarker.POSE_CONNECTIONS, { color: "#7CFFB2", lineWidth: 4 });
    drawing.drawLandmarks(lm, { color: "#FFD166", radius: 4 });
  }
  const people = counter.people.filter((p) => p.landmarks);
  const deepest = people.reduce((m, p) => Math.max(m, p.counter.depth()), 0);
  $("depth-bar").style.height = `${deepest * 100}%`;
  $("depth-bar").classList.toggle("deep", deepest >= 1);

  let msg = "";
  if (!poses.length) msg = "화면에 아무도 안 보여요. 몸 전체(발끝까지)가 보이게 서 주세요!";
  else if (people.some((p) => p.counter.angle === null)) msg = "다리가 잘 안 보여요. 조금 뒤로 가 주세요.";
  $("camera-msg").textContent = msg;
  $("camera-msg").hidden = !msg;
  $("people").innerHTML = settings && settings.players > 1
    ? people.map((p, i) => `<span>친구 ${i + 1}: ${p.counter.count}개</span>`).join("")
    : "";
}

async function start() {
  await poll();
  setInterval(poll, 1000);
  try {
    $("camera-msg").textContent = "카메라 켜는 중…";
    await initCamera();
    $("camera-msg").textContent = "동작 인식 모델 불러오는 중…";
    await initPose();
    $("camera-msg").hidden = true;
    requestAnimationFrame(loop);
  } catch (e) {
    console.error(e);
    $("camera-msg").hidden = false;
    $("camera-msg").textContent = window.isSecureContext
      ? `카메라/인식 모델을 시작할 수 없어요: ${e.message || e}`
      : "카메라는 http://localhost 주소에서만 쓸 수 있어요. (이 화면은 설정·확인용으로만 쓸 수 있어요)";
  }
}

// ---------- 버튼 / 설정 ----------

function askPin() {
  if (!settings?.pin_required) return "";
  return prompt("부모 PIN을 입력하세요") ?? null;
}

async function control(action) {
  const pin = askPin();
  if (pin === null) return;
  try { applyState(await backend.control(action, pin)); } catch (e) { alert(e.message); }
}

$("btn-pause").onclick = () => control("pause");
$("btn-resume").onclick = () => control("resume");
$("btn-reset").onclick = () => control("reset");
$("btn-sound").onclick = () => {
  soundOn = !soundOn;
  try { localStorage.setItem("tvsquat.sound", soundOn ? "on" : "off"); } catch {}
  if (state) applyState(state);
};

const dialog = $("settings-dialog");
const form = $("settings-form");

function updateQuotaPreview() {
  const sec = Number(form.seconds_per_squat.value) || 30;
  const min = Number(form.window_minutes.value) || 5;
  const players = Number(form.players.value) || 1;
  const quota = Math.max(1, Math.ceil((min * 60) / sec)) * players;
  $("quota-preview").textContent = `→ ${min}분마다 스쿼트 ${quota}개`;
}
form.addEventListener("input", updateQuotaPreview);

$("btn-settings").onclick = () => {
  if (!settings) return;
  for (const key of ["seconds_per_squat", "window_minutes", "players", "down_angle", "up_angle"]) {
    form[key].value = settings[key];
  }
  if (backend.local) {
    form.tv_ip.value = settings.tv_ip;
    form.tv_mac.value = settings.tv_mac;
    form.camera.value = settings.camera;
  }
  form.auto_turn_on.checked = settings.auto_turn_on;
  form.carry_over.checked = settings.carry_over;
  form.new_pin.value = "";
  form.pin.value = "";
  $("pin-row").hidden = !settings.pin_required;
  $("settings-error").textContent = "";
  updateQuotaPreview();
  dialog.showModal();
};

form.addEventListener("submit", async (ev) => {
  if (ev.submitter?.value !== "save") return;
  ev.preventDefault();
  const body = {
    seconds_per_squat: Number(form.seconds_per_squat.value),
    window_minutes: Number(form.window_minutes.value),
    players: Number(form.players.value),
    down_angle: Number(form.down_angle.value),
    up_angle: Number(form.up_angle.value),
    auto_turn_on: form.auto_turn_on.checked,
    carry_over: form.carry_over.checked,
    pin: form.pin.value,
  };
  if (form.new_pin.value) body.new_pin = form.new_pin.value;
  if (backend.local) {
    body.tv_ip = form.tv_ip.value;
    body.tv_mac = form.tv_mac.value;
    body.camera = form.camera.value;
  }
  try {
    applyState(await backend.updateSettings(body));
    dialog.close();
  } catch (e) {
    $("settings-error").textContent = e.message;
  }
});

// ---------- TV 도구 (휴대폰 앱) ----------

let lastDiscovered = "";
function renderTvStatus(st) {
  $("tv-message").textContent = st.message || "";
  const list = st.discovered || [];
  const key = JSON.stringify(list);
  if (key === lastDiscovered) return;
  lastDiscovered = key;
  $("tv-found").innerHTML = "";
  for (const tv of list) {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = `${tv.name || "삼성 TV"} (${tv.ip})`;
    b.onclick = () => {
      form.tv_ip.value = tv.ip;
      if (tv.mac) form.tv_mac.value = tv.mac;
    };
    $("tv-found").append(b);
  }
}

for (const btn of document.querySelectorAll("[data-tv]")) {
  btn.onclick = async () => {
    const action = btn.dataset.tv;
    try {
      // 입력한 IP/MAC을 먼저 저장해야 해당 TV로 명령이 간다
      if (action !== "discover") {
        applyState(await backend.updateSettings({ tv_ip: form.tv_ip.value, tv_mac: form.tv_mac.value, pin: form.pin.value }));
      }
      applyState(await backend.tvAction(action, form.pin.value));
      $("settings-error").textContent = "";
    } catch (e) {
      $("settings-error").textContent = e.message;
    }
  };
}

start();
