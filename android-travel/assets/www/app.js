'use strict';

// 브라우저에서 열면(안드로이드 밖) 가짜 Travel 객체로 화면만 시험
const T = window.Travel || (() => {
  let cfg = '{"origin":"ICN","currency":"KRW","trips":[]}', set = { hour: 7, minute: 0, alarmOn: true };
  return {
    state: () => JSON.stringify({ config: cfg, hasKey: false, keyHint: '', ...set, next: '내일 오전 7:00', last: null, lastError: null, running: false }),
    saveConfig: (j) => { cfg = j; return ''; },
    defaultConfig: () => cfg,
    saveSettings: (k, h, m, on) => { set = { hour: h, minute: m, alarmOn: on }; },
    runNow: () => setTimeout(() => onSearchDone('브라우저에서는 검색할 수 없어요'), 300),
    openUrl: (u) => window.open(u),
    openBatterySettings: () => {},
  };
})();

const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const DESTS = [
  ['도쿄', 'HND,NRT', 'Tokyo'], ['오사카', 'KIX', 'Osaka'], ['후쿠오카', 'FUK', 'Fukuoka'], ['삿포로', 'CTS', 'Sapporo'],
  ['오키나와', 'OKA', 'Okinawa'], ['타이베이', 'TPE', 'Taipei'], ['홍콩', 'HKG', 'Hong Kong'], ['상하이', 'PVG', 'Shanghai'],
  ['방콕', 'BKK', 'Bangkok'], ['다낭', 'DAD', 'Da Nang'], ['나트랑', 'CXR', 'Nha Trang'], ['싱가포르', 'SIN', 'Singapore'],
  ['세부', 'CEB', 'Cebu'], ['발리', 'DPS', 'Bali'], ['괌', 'GUM', 'Guam'], ['하와이', 'HNL', 'Honolulu'],
  ['파리', 'CDG', 'Paris'], ['런던', 'LHR', 'London'], ['로마', 'FCO', 'Rome'], ['뉴욕', 'JFK', 'New York'], ['LA', 'LAX', 'Los Angeles'],
];
const AIRLINES = [
  ['KE', '대한항공'], ['OZ', '아시아나'], ['7C', '제주항공'], ['LJ', '진에어'], ['TW', '티웨이'], ['BX', '에어부산'],
  ['ZE', '이스타'], ['RS', '에어서울'], ['YP', '에어프레미아'], ['JL', 'JAL'], ['NH', 'ANA'], ['SQ', '싱가포르'], ['CX', '캐세이'], ['VJ', '비엣젯'],
];
const LEVELS = { low: '🟢 평소보다 쌈', typical: '🟡 보통 가격', high: '🔴 평소보다 비쌈' };

let state = {};
let config = {};
let editing = -1; // 편집 중인 여행 번호 (-1 = 새 여행)
let page = 'home';

function load() {
  state = JSON.parse(T.state());
  config = normalize(JSON.parse(state.config));
}

// defaults / 공통 인원을 각 여행에 풀어 넣어, 화면에서는 여행마다 값이 다 들어 있게
function normalize(c) {
  const d = c.defaults || {};
  const trav = c.travelers || { adults: 1, children_ages: [], infants: 0 };
  const trips = (c.trips || []).map((t) => {
    const m = { ...d, ...t };
    m.travelers = { adults: 1, children_ages: [], infants: 0, ...(t.travelers || trav) };
    if (!Array.isArray(m.nights)) m.nights = [m.nights || 4];
    if (!('max_stops' in m)) m.max_stops = null;
    if (m.enabled === undefined) m.enabled = true;
    if (m.hotel === undefined) m.hotel = true;
    m.depart_to = m.depart_to || m.depart_from;
    return m;
  });
  return { currency: c.currency || 'KRW', origin: c.origin || 'ICN', travelers: trav, trips };
}

function saveConfig() {
  const err = T.saveConfig(JSON.stringify(config));
  if (err) toast(err);
  return err;
}

function money(n) {
  const cur = config.currency || 'KRW';
  if (cur !== 'KRW') return `${Math.round(n).toLocaleString('en-US')} ${cur}`;
  if (Math.abs(n) >= 10000) return `${(n / 10000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만원`;
  return `${n.toLocaleString('ko-KR')}원`;
}
const delta = (d) => (typeof d === 'number' && d !== 0)
  ? `<span class="${d < 0 ? 'down' : 'up'}">${d < 0 ? '▼' : '▲'} ${money(Math.abs(d))}</span>` : '';

function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.add('hidden'), 2600);
}

// ---------- 화면 전환 ----------
const TITLES = { home: '오늘의 여행 가격', trips: '여행 목록', edit: '여행 편집', settings: '설정' };
function show(p) {
  page = p;
  document.querySelectorAll('.page').forEach((el) => el.classList.toggle('hidden', el.id !== `page-${p}`));
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.page === p || (p === 'edit' && b.dataset.page === 'trips')));
  $('#title').textContent = p === 'edit' && editing < 0 ? '새 여행' : TITLES[p];
  $('#back').classList.toggle('hidden', p !== 'edit');
  window.scrollTo(0, 0);
  render();
}
window.onBack = () => {
  if (page === 'edit') { show('trips'); return true; }
  if (page !== 'home') { show('home'); return true; }
  return false;
};
window.onResumeApp = () => { load(); render(); };

function render() {
  if (page === 'home') renderHome();
  if (page === 'trips') renderTrips();
  if (page === 'settings') renderSettings();
}

// ---------- 오늘 가격 ----------
function renderHome() {
  const last = state.last;
  const banner = $('#banner');
  const msgs = [];
  if (!state.hasKey) msgs.push('체험 모드예요. 지금 보이는 가격은 가짜예요. [설정]에서 SerpApi 키를 넣어 주세요.');
  if (state.lastError) msgs.push('최근 검색 실패: ' + state.lastError);
  banner.innerHTML = msgs.map(esc).join('<br>');
  banner.classList.toggle('hidden', !msgs.length);

  $('#next').textContent = state.alarmOn && state.next ? `다음 알림: ${state.next}` : '아침 알림 꺼짐';
  const run = $('#run');
  run.disabled = state.running;
  run.innerHTML = state.running ? '<span class="spin"></span> 검색 중' : '지금 검색';

  if (!last) {
    $('#updated').textContent = '아직 검색 전';
    $('#results').innerHTML = `<div class="empty">✈️<br>[지금 검색]을 누르거나 내일 아침 알림을 기다려 주세요.</div>`;
    return;
  }
  const at = new Date(last.at);
  $('#updated').textContent = `${at.getMonth() + 1}/${at.getDate()} ${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')} 기준`
    + (last.demo ? ' · 체험 모드' : '') + ` · 검색 ${last.calls}회`;
  const trips = last.trips || [];
  $('#results').innerHTML = trips.length ? trips.map(tripCard).join('')
    : '<div class="empty">켜진 여행이 없어요. [여행] 탭에서 추가해 주세요.</div>';
}

function tripCard(t, i) {
  const f = t.flight;
  let h = `<div class="card"><h2><span>${esc(t.name)}</span>`;
  if (t.total != null) h += `<span class="total">${money(t.total)}</span>`;
  else if (f) h += `<span class="total">${money(f.price)}</span>`;
  h += `</h2><div class="sub">${esc(t.route)} · ${esc(t.travelers)}`;
  if (f) h += ` · ${esc(f.outbound)} → ${esc(f.inbound)} ${f.nights}박`;
  h += t.total != null && t.totalDelta ? ` · 어제보다 ${delta(t.totalDelta)}` : '';
  h += '</div>';
  if (!f) {
    h += '<div class="part"><div class="info">항공권 결과가 없어요</div></div>';
  } else {
    h += `<div class="part"><div class="head"><span>🛫 항공 (왕복 · 전원)</span><span class="price">${money(f.price)} ${delta(f.delta)}</span></div>
      <div class="info">${esc(f.airlines)} · ${f.stops === 0 ? '직항' : `경유 ${f.stops}회`}${f.numbers ? ' · ' + esc(f.numbers) : ''}${f.depart ? ' · 출발 ' + esc(f.depart) : ''}</div>`;
    if (LEVELS[f.level]) h += `<span class="level">${LEVELS[f.level]}</span>`;
    if (t.others && t.others.length) h += `<div class="info">다른 날짜: ${t.others.map((o) => `${esc(o.label)} ${money(o.price)}`).join(', ')}</div>`;
    h += '</div>';
  }
  if (t.hotelWanted && f) {
    const hs = t.hotels || [];
    if (!hs.length) h += '<div class="part"><div class="info">🏨 힐튼 계열 호텔 결과가 없어요</div></div>';
    hs.forEach((x, j) => {
      h += `<div class="part"><div class="head"><span>${j ? '' : '🏨 '}${esc(x.name)}</span><span class="price">${money(x.total)} ${j ? '' : delta(t.hotelDelta)}</span></div>
        <div class="info">1박 ${money(x.perNight)}${x.rating ? ` · ⭐ ${x.rating}` : ''}</div></div>`;
    });
  }
  (t.errors || []).forEach((e) => { h += `<div class="err">⚠️ ${esc(e)}</div>`; });
  const links = [];
  if (t.flightsUrl) links.push(`<button data-url="${esc(t.flightsUrl)}">구글 항공권에서 보기</button>`);
  const hl = (t.hotels || [])[0];
  if (hl && hl.link) links.push(`<button data-url="${esc(hl.link)}">호텔 보기</button>`);
  if (links.length) h += `<div class="links">${links.join('')}</div>`;
  return h + '</div>';
}

window.onSearchDone = (err) => {
  load();
  render();
  toast(err ? `검색 실패: ${err}` : '검색 완료');
};

// ---------- 여행 목록 ----------
function renderTrips() {
  const list = $('#trip-list');
  if (!config.trips.length) {
    list.innerHTML = '<div class="empty">아직 여행이 없어요</div>';
  } else {
    list.innerHTML = config.trips.map((t, i) => {
      const tr = t.travelers;
      const people = `성인 ${tr.adults}${tr.children_ages.length ? ` · 아동 ${tr.children_ages.length}` : ''}${tr.infants ? ` · 유아 ${tr.infants}` : ''}`;
      return `<div class="card trip ${t.enabled ? '' : 'off'}" data-i="${i}">
        <div class="grow"><b>${esc(t.name)}</b> <span class="muted small">${esc(t.origin || config.origin)}→${esc(t.destination)}</span>
          <div class="muted small">${esc(fmtRange(t.depart_from, t.depart_to))} 출발 · ${esc(t.nights.join('/'))}박 · ${people}</div>
          <div class="muted small">${t.airlines && t.airlines.length ? esc(t.airlines.join(', ')) : '전체 항공사'} · ${stopName(t.max_stops)}${t.hotel ? ' · 🏨 힐튼' : ''}</div>
        </div>
        <label class="switch" data-toggle="${i}"><input type="checkbox" ${t.enabled ? 'checked' : ''}><span></span></label>
      </div>`;
    }).join('');
  }
  const calls = config.trips.filter((t) => t.enabled).reduce((s, t) => s + Math.min(t.max_searches || 3, combos(t)) + (t.hotel ? 1 : 0), 0);
  $('#quota').textContent = `하루 검색 약 ${calls}회 · 한 달 약 ${calls * 30}회 (SerpApi 요금제 한도 안에서 쓰세요)`;
}

const stopName = (s) => (s === null || s === undefined || s === '') ? '경유 상관없음' : s === 0 ? '직항' : `${s}회 경유까지`;
function fmtRange(a, b) {
  const f = (s) => { const [, m, d] = s.split('-'); return `${+m}/${+d}`; };
  return a === b ? f(a) : `${f(a)}~${f(b)}`;
}
function combos(t) {
  const days = Math.round((new Date(t.depart_to) - new Date(t.depart_from)) / 864e5) + 1;
  return Math.max(0, days) * (t.nights.length || 1);
}

$('#trip-list').addEventListener('click', (e) => {
  const sw = e.target.closest('[data-toggle]');
  if (sw) {
    if (e.target.tagName !== 'INPUT') return; // label 클릭은 input 으로 한 번 더 들어옴
    const t = config.trips[+sw.dataset.toggle];
    t.enabled = e.target.checked;
    if (saveConfig()) t.enabled = !t.enabled;
    renderTrips();
    return;
  }
  const card = e.target.closest('.trip');
  if (card) openEditor(+card.dataset.i);
});
$('#add-trip').onclick = () => openEditor(-1);

// ---------- 여행 편집 ----------
function iso(d) { return d.toISOString().slice(0, 10); }

function openEditor(i) {
  editing = i;
  const now = new Date();
  const t = i >= 0 ? config.trips[i] : {
    name: '', destination: '', hotel_city: '', origin: config.origin,
    depart_from: iso(new Date(now.getTime() + 30 * 864e5)), depart_to: iso(new Date(now.getTime() + 40 * 864e5)),
    nights: [3, 4], airlines: [], cabin: 'economy', max_stops: 0, max_searches: 2, hotel: true, rooms: 1, enabled: true,
    travelers: { ...{ adults: 2, children_ages: [], infants: 0 }, ...config.travelers },
  };
  const f = $('#trip-form');
  f.name.value = t.name;
  f.origin.value = t.origin || config.origin;
  f.destination.value = t.destination;
  f.hotel_city.value = t.hotel_city || '';
  f.depart_from.value = t.depart_from;
  f.depart_to.value = t.depart_to;
  f.nights.value = t.nights.join(',');
  f.airlines.value = (t.airlines || []).join(',');
  f.cabin.value = t.cabin || 'economy';
  f.max_stops.value = t.max_stops === null || t.max_stops === undefined ? '' : String(t.max_stops);
  f.adults.value = t.travelers.adults;
  f.infants.value = t.travelers.infants || 0;
  f.children_ages.value = (t.travelers.children_ages || []).join(',');
  f.rooms.value = t.rooms || 1;
  f.hotel.checked = t.hotel !== false;
  f.max_searches.value = t.max_searches || 2;
  f.enabled.checked = t.enabled !== false;
  $('#delete-trip').classList.toggle('hidden', i < 0);
  $('#form-error').classList.add('hidden');
  renderChips();
  show('edit');
}

function nums(s) {
  return s.split(/[,\s]+/).filter(Boolean).map(Number);
}

function renderChips() {
  const f = $('#trip-form');
  const dest = f.destination.value.toUpperCase().replace(/\s/g, '');
  $('#dest-chips').innerHTML = DESTS.map(([n, code, city]) =>
    `<button type="button" class="${dest === code ? 'on' : ''}" data-dest="${code}" data-city="${city}" data-name="${n}">${n}</button>`).join('');
  const al = f.airlines.value.toUpperCase().split(/[,\s]+/).filter(Boolean);
  $('#airline-chips').innerHTML = AIRLINES.map(([c, n]) =>
    `<button type="button" class="${al.includes(c) ? 'on' : ''}" data-air="${c}">${n}</button>`).join('');
  const t = { depart_from: f.depart_from.value, depart_to: f.depart_to.value || f.depart_from.value, nights: nums(f.nights.value) };
  const c = t.depart_from ? combos(t) : 0;
  const m = +f.max_searches.value || 1;
  $('#combo-hint').textContent = c
    ? `가능한 날짜 조합 ${c}개 중 매일 ${Math.min(c, m)}개를 고르게 골라 검색해요 (호텔은 가장 싼 날짜로 1번 더).`
    : '';
}

$('#dest-chips').addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  const f = $('#trip-form');
  f.destination.value = b.dataset.dest;
  f.hotel_city.value = b.dataset.city;
  if (!f.name.value) f.name.value = b.dataset.name;
  renderChips();
});
$('#airline-chips').addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (!b) return;
  const f = $('#trip-form');
  let al = f.airlines.value.toUpperCase().split(/[,\s]+/).filter(Boolean);
  al = al.includes(b.dataset.air) ? al.filter((x) => x !== b.dataset.air) : [...al, b.dataset.air];
  f.airlines.value = al.join(',');
  renderChips();
});
$('#trip-form').addEventListener('input', renderChips);

$('#trip-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const f = e.target;
  const ms = f.max_stops.value;
  const trip = {
    name: f.name.value.trim(),
    origin: f.origin.value.trim().toUpperCase() || config.origin,
    destination: f.destination.value.trim().toUpperCase().replace(/\s/g, ''),
    hotel_city: f.hotel_city.value.trim(),
    depart_from: f.depart_from.value,
    depart_to: f.depart_to.value || f.depart_from.value,
    nights: nums(f.nights.value),
    airlines: f.airlines.value.toUpperCase().split(/[,\s]+/).filter(Boolean),
    cabin: f.cabin.value,
    max_stops: ms === '' ? null : +ms,
    travelers: { adults: +f.adults.value || 1, children_ages: nums(f.children_ages.value), infants: +f.infants.value || 0 },
    rooms: +f.rooms.value || 1,
    hotel: f.hotel.checked,
    max_searches: +f.max_searches.value || 2,
    enabled: f.enabled.checked,
  };
  const err = $('#form-error');
  if (trip.nights.some((n) => !Number.isInteger(n) || n < 1)) { err.textContent = '몇 박은 1 이상의 숫자로 입력하세요 (예: 3,4)'; err.classList.remove('hidden'); return; }
  if (trip.travelers.children_ages.some((n) => !Number.isInteger(n) || n < 2 || n > 17)) { err.textContent = '아이 나이는 2~17 숫자로 입력하세요 (2세 미만은 유아 칸에)'; err.classList.remove('hidden'); return; }
  const before = config.trips.slice();
  if (editing >= 0) config.trips[editing] = trip; else config.trips.push(trip);
  const msg = T.saveConfig(JSON.stringify(config));
  if (msg) {
    config.trips = before;
    err.textContent = msg;
    err.classList.remove('hidden');
    return;
  }
  toast('저장했어요');
  show('trips');
});

$('#delete-trip').onclick = () => {
  if (editing < 0 || !confirm(`'${config.trips[editing].name}' 여행을 삭제할까요?`)) return;
  config.trips.splice(editing, 1);
  saveConfig();
  show('trips');
};

// ---------- 설정 ----------
function renderSettings() {
  const f = $('#settings-form');
  f.apiKey.value = '';
  f.apiKey.placeholder = state.hasKey ? '저장된 키 있음 (바꾸려면 새로 붙여넣기)' : 'API Key 붙여넣기';
  $('#key-hint').textContent = state.hasKey ? `저장된 키: ${state.keyHint}` : '키 없음 · 체험 모드';
  $('#clear-key').classList.toggle('hidden', !state.hasKey);
  f.alarmOn.checked = state.alarmOn;
  f.time.value = `${String(state.hour).padStart(2, '0')}:${String(state.minute).padStart(2, '0')}`;
  f.origin.value = config.origin;
  f.currency.value = config.currency;
}

function saveSettings(apiKey) {
  const f = $('#settings-form');
  const [h, m] = (f.time.value || '07:00').split(':').map(Number);
  T.saveSettings(apiKey, h, m, f.alarmOn.checked);
  config.origin = f.origin.value.trim().toUpperCase() || 'ICN';
  config.currency = f.currency.value;
  saveConfig();
  load();
}

$('#settings-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const key = e.target.apiKey.value.trim();
  saveSettings(key || null);
  toast(state.alarmOn ? `저장했어요 · 다음 알림 ${state.next}` : '저장했어요 · 아침 알림 꺼짐');
  renderSettings();
});
$('#clear-key').onclick = () => { saveSettings(''); toast('키를 지웠어요 (체험 모드)'); renderSettings(); };
$('#battery').onclick = () => T.openBatterySettings();
$('#test-notify').onclick = () => {
  T.runNow(true);
  toast('검색 중… 끝나면 알림이 와요');
  state.running = true;
};
$('#reset').onclick = () => {
  if (!confirm('여행 목록을 처음 예시로 되돌릴까요? 지금 목록은 사라져요.')) return;
  T.defaultConfig();
  load();
  toast('되돌렸어요');
};

// ---------- 공통 ----------
document.querySelector('nav').addEventListener('click', (e) => {
  const b = e.target.closest('button');
  if (b) show(b.dataset.page);
});
$('#back').onclick = () => window.onBack();
$('#run').onclick = () => {
  T.runNow(false);
  state.running = true;
  renderHome();
};
$('#results').addEventListener('click', (e) => {
  const b = e.target.closest('[data-url]');
  if (b) T.openUrl(b.dataset.url);
});

load();
show('home');
