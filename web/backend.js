// 화면(app.js)이 쓰는 두 가지 백엔드
//   ServerBackend - PC에서 python -m tvsquat 서버와 통신
//   LocalBackend  - 휴대폰 앱: 타이머·판정을 이 기기에서 하고, TV는 안드로이드 네이티브 브리지로 제어

import { DEFAULT_SETTINGS, Session, TV_OFF, TV_ON, mergeSettings, publicSettings } from "./session.js";

export class ServerBackend {
  local = false;

  async _api(path, body) {
    const res = await fetch(path, body === undefined ? {} : {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  state() { return this._api("/api/state"); }
  addReps(count) { return this._api("/api/rep", { count }); }
  updateSettings(data) { return this._api("/api/settings", data); }
  control(action, pin) { return this._api("/api/control", { action, pin }); }
}

// ---------- TV (휴대폰 앱) ----------

// 안드로이드 앱이 window.AndroidTv 로 넣어주는 네이티브 브리지를 감싼다
export class AndroidTv {
  constructor(bridge) { this.bridge = bridge; }
  configure(ip, mac) { this.bridge.configure(ip || "", mac || ""); }
  status() {
    try { return JSON.parse(this.bridge.status()); } catch { return { on: null, message: "" }; }
  }
  isOn() { return this.status().on; }
  powerOff() { this.bridge.powerOff(); }
  powerOn() { this.bridge.powerOn(); }
  pair() { this.bridge.pair(); }
  discover() { this.bridge.discover(); }
  speak(text) { this.bridge.speak(text); }
  describe(settings) { return settings.tv_ip ? `삼성 TV ${settings.tv_ip}` : "TV 미설정 (설정에서 TV 찾기)"; }
}

// 브라우저에서 앱 화면을 시험할 때 쓰는 가상 TV
export class FakeTv {
  on = true;
  message = "";
  configure() {}
  status() { return { on: this.on, message: this.message, busy: false, paired: true, discovered: [] }; }
  isOn() { return this.on; }
  powerOff() { this.on = false; this.message = "[가상] TV 끔"; }
  powerOn() { this.on = true; this.message = "[가상] TV 켬"; }
  pair() { this.message = "[가상] 연결됨"; }
  discover() { this.message = "[가상] 검색은 앱에서만 됩니다"; }
  describe() { return "가상 TV (브라우저 시험 모드)"; }
}

const STORAGE_KEY = "tvsquat.settings";
export const LOCAL_DEFAULTS = Object.freeze({ ...DEFAULT_SETTINGS, tv_ip: "", tv_mac: "", camera: "user" });

function loadSettings(storage) {
  try {
    const saved = JSON.parse(storage?.getItem(STORAGE_KEY) || "{}");
    return mergeSettings({ ...LOCAL_DEFAULTS }, saved);
  } catch {
    return { ...LOCAL_DEFAULTS };
  }
}

export class LocalBackend {
  local = true;

  constructor(tv, { storage = globalThis.localStorage, clock = () => performance.now() / 1000, autoTick = true } = {}) {
    this.tv = tv;
    this.storage = storage;
    this.clock = clock;
    this.settings = loadSettings(storage);
    this.session = new Session(this.settings);
    this.tv.configure(this.settings.tv_ip, this.settings.tv_mac);
    if (autoTick) setInterval(() => this.tick(), 1000);
  }

  _tvOn() {
    // TV를 설정하지 않았으면 상태를 모름(null) = 항상 켜진 것으로 보고 타이머를 돌린다
    if (this.tv instanceof AndroidTv && !this.settings.tv_ip) return null;
    const on = this.tv.isOn();
    return on === undefined ? null : on;
  }

  _run(actions) {
    for (const a of actions) {
      if (a === TV_OFF) this.tv.powerOff();
      else if (a === TV_ON) this.tv.powerOn();
    }
  }

  tick() {
    this._run(this.session.tick(this.clock(), this._tvOn()));
  }

  async state() {
    const data = this.session.snapshot(this.clock());
    data.settings = publicSettings(this.settings);
    data.tv = this.tv.describe(this.settings);
    data.tv_status = this.tv.status();
    return data;
  }

  async addReps(count) {
    this._run(this.session.addReps(count, this.clock()));
    return this.state();
  }

  _checkPin(pin) {
    if (this.settings.pin && String(pin ?? "") !== this.settings.pin) throw new Error("PIN이 틀렸습니다");
  }

  async updateSettings(data) {
    data = { ...data };
    this._checkPin(data.pin);
    delete data.pin;
    if ("new_pin" in data) data.pin = data.new_pin;
    const next = mergeSettings(this.settings, data);
    if (!["user", "environment"].includes(next.camera)) throw new Error("카메라 값이 잘못되었습니다");
    next.tv_ip = next.tv_ip.trim();
    next.tv_mac = next.tv_mac.trim();
    this.settings = next;
    this.session.settingsChanged(next, this.clock());
    this.tv.configure(next.tv_ip, next.tv_mac);
    try { this.storage?.setItem(STORAGE_KEY, JSON.stringify(next)); } catch {}
    return this.state();
  }

  async control(action, pin) {
    this._checkPin(pin);
    const now = this.clock();
    if (action === "pause") this.session.pause(now);
    else if (action === "resume") this._run(this.session.resume(now));
    else if (action === "reset") this.session.reset(now);
    else throw new Error(`알 수 없는 동작: ${action}`);
    return this.state();
  }

  // TV 도구 (설정 화면의 버튼). 부모 PIN 필요
  async tvAction(action, pin) {
    this._checkPin(pin);
    if (action === "pair") this.tv.pair();
    else if (action === "discover") this.tv.discover();
    else if (action === "off") this.tv.powerOff();
    else if (action === "on") this.tv.powerOn();
    else throw new Error(`알 수 없는 동작: ${action}`);
    return this.state();
  }
}
