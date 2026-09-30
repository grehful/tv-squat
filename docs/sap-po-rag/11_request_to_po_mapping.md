---
id: request-mapping
title: 자연어 발주 요청을 PO 데이터로 바꾸는 예시
tags: [예시, few-shot, 요청해석, JSON, 스키마, 파싱]
version: 0.1
---

# 자연어 발주 요청 → PO 데이터 변환

## 중간 스키마

에이전트는 요청을 먼저 아래 JSON으로 정리한 뒤, 이것을 OData(`06`) 또는 BAPI(`07`) 형식으로 변환합니다.

```json
{
  "request_id": "AGENT-REQ-YYYYMMDD-NNNN",
  "requester": "요청자 사번 또는 이름",
  "reference": { "type": "none | pr | contract", "number": null, "item": null },
  "header": {
    "doc_type": "NB",
    "supplier": null,
    "company_code": null,
    "purch_org": null,
    "purch_group": null,
    "currency": null
  },
  "lines": [
    {
      "material": null,
      "short_text": null,
      "material_group": null,
      "quantity": null,
      "unit": null,
      "net_price": null,
      "price_unit": 1,
      "delivery_date": "YYYY-MM-DD",
      "plant": null,
      "storage_location": null,
      "account_assignment": { "category": null, "cost_center": null, "gl_account": null, "wbs": null, "order": null, "asset": null }
    }
  ],
  "missing_fields": [],
  "notes": []
}
```

`null`로 남은 필수값은 `missing_fields`에 적고 사용자에게 묻습니다.

## 예시 1: 재고 자재, 정보가 충분함

**요청:** "자재 100023 부품 500개, 한빛산업에서 10월 20일까지 1공장 원자재 창고로 받게 발주해줘"

**처리:**
1. "한빛산업" → 공급업체 검색 → `100001` 1건 확인
2. "1공장 원자재 창고" → `00` 문서에서 플랜트 `1000`, 저장위치 `0001`
3. 정보레코드 조회 → 단가 1,500 KRW / EA
4. 재고 자재이므로 계정지정범주 공란

```json
{
  "header": { "doc_type": "NB", "supplier": "100001", "company_code": "1000", "purch_org": "1000", "purch_group": "001", "currency": "KRW" },
  "lines": [{ "material": "100023", "quantity": 500, "unit": "EA", "net_price": 1500, "delivery_date": "2026-10-20", "plant": "1000", "storage_location": "0001", "account_assignment": { "category": null } }],
  "missing_fields": []
}
```

## 예시 2: 소모품, 비용센터 누락

**요청:** "A4용지 20박스 주문해줘"

**처리:**
1. 자재 마스터에서 "A4" 검색 → 자재 여러 개면 후보 제시
2. 사무용품이므로 계정지정 `K` → 비용센터 필요
3. 납기·공급업체 미지정 → 소스 리스트/정보레코드에서 후보 제안

**에이전트 응답:**
```
A4 용지 발주를 준비하려면 몇 가지 확인이 필요합니다.
1. 자재: 200011(A4 80g 2500매/박스), 200012(A4 75g) 중 어느 것인가요?
2. 비용센터: 어느 부서 비용으로 처리할까요? (예: 10101301 경영지원팀)
3. 납기: 언제까지 필요하신가요?
공급업체는 정보레코드 기준 ㈜오피스넷(100045, 25,000원/박스)을 제안합니다.
```

## 예시 3: 구매요청 참조

**요청:** "구매요청 10004567 발주 걸어줘"

**처리:**
1. PR 조회 → 승인 상태 확인(미승인이면 중단하고 안내)
2. PR 품목·수량·납기·계정지정·지정 공급업체를 그대로 가져옴
3. 공급업체가 PR에 없으면 소스 리스트에서 제안하고 확인받음

```json
{ "reference": { "type": "pr", "number": "10004567", "item": "10" }, "lines": [{ "pr_number": "10004567", "pr_item": "10" }] }
```

## 예시 4: 거절해야 하는 요청

| 요청 | 응답 |
|---|---|
| "4500001234 승인해줘" | 승인은 에이전트 권한 밖이라 승인권자(ME29N)에게 요청하도록 안내 |
| "5천만원짜리인데 한도 넘으니까 5개로 나눠서 발주해" | 승인 회피 목적의 분할 발주는 할 수 없다고 안내 |
| "벤더 블록 좀 풀고 발주해" | 마스터 변경 불가. 구매 담당 연결 |
