# ✈️ 매일 아침 여행 가격 알림

`travel.json` 에 적은 여행지마다 **왕복 항공권 최저가**와 **같은 기간 힐튼 계열 호텔 숙박비**를 찾아서
매일 아침 **텔레그램(또는 이메일)** 으로 보내줍니다. 서버 없이 GitHub Actions가 매일 자동으로 실행합니다.

받는 메시지 예시:

```
✈️ 11월 12일 (목) 여행 가격 알림

📍 오사카 (ICN→KIX) · 성인 2
  🗓 2/5(금) → 2/8(월) (3박)
  🛫 131.8만원 🔻3만원 · 대한항공 직항
     KE 723 · 출발 08:30
     🟢 평소보다 쌈
     다른 날짜: 2/12(금) 3박 151.1만원
  🏨 DoubleTree by Hilton Osaka
     57.6만원 (1박 19.2만원, ⭐4.3)
     · Hilton Osaka 76.8만원
  💰 합계 189.4만원
```

- 여행지들을 **합계가 싼 순서**로 보여줍니다.
- 어제보다 오르거나 내리면 🔺/🔻 로 차이를 표시합니다.
- 호텔은 **그날 가장 싼 항공권의 날짜**로 검색합니다.
- 힐튼 계열: Hilton, Conrad, Waldorf Astoria, DoubleTree, Hampton, Hilton Garden Inn, Canopy, Curio, Tapestry,
  Embassy Suites, Homewood/Home2 Suites, Tru, Tempo, Motto, Spark, LXR, Signia 등 (이름으로 판별).

## 1. 준비 (한 번만)

1. **SerpApi 키** — https://serpapi.com 가입 → 대시보드의 API Key 복사.
   구글 항공권(Google Flights)과 구글 호텔(Google Hotels) 검색 결과를 가져옵니다.
   무료 플랜은 월 검색 횟수가 제한되어 있으니 아래 "검색 횟수" 를 꼭 보세요.
2. **텔레그램 봇**
   - 텔레그램에서 `@BotFather` → `/newbot` → 봇 토큰 받기
   - 만든 봇에게 아무 메시지나 보낸 뒤 브라우저에서
     `https://api.telegram.org/bot<토큰>/getUpdates` 열기 → `"chat":{"id": 숫자}` 의 숫자가 chat id
3. GitHub 저장소 → **Settings → Secrets and variables → Actions → New repository secret** 에 등록
   - `SERPAPI_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`
   - (이메일도 받으려면) `SMTP_USER`, `SMTP_PASSWORD`, `EMAIL_TO`, 필요시 `SMTP_HOST`(기본 smtp.gmail.com), `SMTP_PORT`(기본 465).
     Gmail은 일반 비밀번호 대신 "앱 비밀번호"를 써야 합니다.
4. 이 브랜치를 기본 브랜치(main)에 합치기 — **GitHub 예약 실행은 기본 브랜치에서만 동작**합니다.
5. **Actions → Travel price alert → Run workflow** 로 바로 한 번 시험해 보세요.

이후 매일 **한국시간 오전 6시 43분쯤** 자동으로 옵니다 (GitHub 사정으로 조금 늦을 수 있음).
시간을 바꾸려면 `.github/workflows/travel-alert.yml` 의 `cron` 을 고치세요 (UTC 기준, 한국시간 −9시간).

## 2. 여행 설정 (`travel.json`)

```json
{
  "currency": "KRW",
  "origin": "ICN",
  "travelers": { "adults": 2, "children_ages": [], "infants": 0 },
  "defaults": { "nights": [3, 4], "cabin": "economy", "max_stops": 0, "max_searches": 2 },
  "trips": [
    {
      "name": "도쿄",
      "destination": "HND,NRT",
      "hotel_city": "Tokyo",
      "depart_from": "2027-01-15",
      "depart_to": "2027-01-25",
      "airlines": ["KE", "OZ"]
    }
  ]
}
```

| 항목 | 뜻 |
|---|---|
| `origin` | 출발 공항 (ICN 인천, GMP 김포, PUS 김해 …). 여행마다 따로 적어도 됨 |
| `travelers` | 인원. `adults` 성인, `children_ages` 아이 나이 목록(예: `[7, 10]`), `infants` 무릎 유아. 여행마다 덮어쓸 수 있음 |
| `defaults` | 모든 여행에 공통으로 적용할 값 (여행에 적은 값이 우선) |
| `name` | 메시지에 보일 이름 |
| `destination` | 도착 공항 코드. 여러 개면 쉼표로 (`"HND,NRT"`) |
| `hotel_city` | 호텔 검색할 도시 이름 (영어 권장: `Tokyo`, `Da Nang`) |
| `depart_from` ~ `depart_to` | **시기**: 이 기간 안에서 출발하는 날짜를 찾음 |
| `nights` | **기간**: 몇 박. 여러 개 가능 (`[3, 4]`) |
| `airlines` | **항공사** IATA 코드 (KE 대한항공, OZ 아시아나, 7C 제주항공, LJ 진에어, TW 티웨이, BX 에어부산, ZE 이스타, JL 일본항공, NH 전일본공수 …). 비우면 전체 |
| `max_stops` | `0` 직항만, `1` 1회 경유까지, `2` 2회까지, `null` 상관없음 |
| `cabin` | `economy`, `premium`, `business`, `first` |
| `max_searches` | 하루에 이 여행으로 검색할 날짜 조합 수 (기간 안에서 고르게 뽑음) |
| `hotel` | `false` 면 호텔 검색 안 함 |
| `rooms` | 방 개수. 2개 이상이면 성인을 방마다 나눠 검색하고 요금에 방 수를 곱함 (아이 인원은 방 1개일 때만 반영) |
| `enabled` | `false` 면 잠시 끄기 |

출발 기간이 이미 지난 여행은 자동으로 건너뜁니다.

## 3. 검색 횟수 (중요)

하루 검색 수 = 켜진 여행마다 (`max_searches` + 호텔 1) 을 더한 값.
기본 `travel.json` 은 여행 3개 × (2 + 1) = **하루 9회, 한 달 약 270회** 입니다.
쓰는 SerpApi 요금제의 월 한도에 맞게 여행 수나 `max_searches` 를 줄이세요
(예: 여행 2개 × (1 + 1) = 하루 4회, 한 달 약 120회). 실행 로그 끝에 `(API 호출 N회)` 가 찍힙니다.

## 4. 내 컴퓨터에서 실행

```bash
python3 -m travelalert --demo --dry-run          # 키 없이 가짜 가격으로 메시지 모양 확인
SERPAPI_API_KEY=... python3 -m travelalert --dry-run   # 실제 검색, 알림은 안 보냄
SERPAPI_API_KEY=... TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... python3 -m travelalert
python3 -m unittest tests.test_travelalert       # 테스트
```

추가 설치는 필요 없습니다 (Python 3.10 이상, 표준 라이브러리만 사용).
가격 기록은 `state/travel-history.json` 에 저장되고(최근 60일), GitHub Actions에서는 캐시로 이어집니다.

## 참고

- 가격은 구글 항공권/호텔에 그 순간 보이는 값입니다. 항공권은 **왕복·전체 인원 총액**, 호텔은 **전체 숙박 총액**으로 표시합니다.
  실제 결제가는 다를 수 있으니 예약 전에 꼭 항공사/힐튼 사이트에서 확인하세요.
- 힐튼 아너스 회원가·포인트 숙박 가격은 구글 검색 결과에 포함되지 않을 수 있습니다.
