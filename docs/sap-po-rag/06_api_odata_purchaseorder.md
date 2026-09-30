---
id: api-odata-po
title: OData API로 구매오더 생성(API_PURCHASEORDER_PROCESS_SRV)
tags: [OData, API, REST, S/4HANA, CSRF, deep insert, 구매오더 생성, API_PURCHASEORDER_2]
version: 0.1
---

# OData API로 구매오더 생성

S/4HANA의 표준 OData 서비스로 PO를 만드는 방법입니다. ECC는 보통 이 서비스가 없으므로 `07_bapi_po_create1.md`를 보세요.

## 서비스 선택

| 서비스 | 버전 | 경로 | 비고 |
|---|---|---|---|
| `API_PURCHASEORDER_PROCESS_SRV` | OData V2 | `/sap/opu/odata/sap/API_PURCHASEORDER_PROCESS_SRV` | 널리 쓰임. 최신 릴리스에서는 후속 서비스 권장 |
| `API_PURCHASEORDER_2` | OData V4 | `/sap/opu/odata4/sap/api_purchaseorder_2/srvd_a2x/sap/purchaseorder/0001/` | 후속 서비스. 필드명·구조가 다름 |

> 회사 확인 필요: 게이트웨이(`/IWFND/MAINT_SERVICE`, V4는 `/IWFND/V4_ADMIN`)에서 어떤 서비스가 활성화돼 있는지 Basis 담당자에게 확인하세요. 아래 예시는 V2 기준입니다.

## 엔티티

| 엔티티 셋 | 내용 | 헤더에서의 내비게이션 |
|---|---|---|
| `A_PurchaseOrder` | 헤더 | - |
| `A_PurchaseOrderItem` | 품목 | `to_PurchaseOrderItem` |
| `A_PurchaseOrderScheduleLine` | 납품일정 | 품목의 `to_ScheduleLine` |
| `A_PurOrdAccountAssignment` | 계정지정 | 품목의 `to_AccountAssignment` |
| `A_PurchaseOrderNote` | 헤더 텍스트 | `to_PurchaseOrderNote` |

## 인증과 CSRF 토큰

POST 전에 CSRF 토큰을 받아야 합니다.

1. `GET` 요청에 헤더 `x-csrf-token: Fetch`를 붙여 호출
2. 응답 헤더의 `x-csrf-token` 값과 **쿠키**를 저장
3. `POST` 요청에 같은 토큰(`x-csrf-token: <값>`)과 쿠키를 함께 전송

토큰과 쿠키는 같은 세션에서만 유효합니다. 403 `CSRF token validation failed`가 나면 1단계부터 다시 합니다.

## 요청 예시: 한 번에 헤더+품목+일정 생성(Deep Insert)

```http
POST /sap/opu/odata/sap/API_PURCHASEORDER_PROCESS_SRV/A_PurchaseOrder
Content-Type: application/json
Accept: application/json
x-csrf-token: <토큰>
```

```json
{
  "PurchaseOrderType": "NB",
  "CompanyCode": "1000",
  "PurchasingOrganization": "1000",
  "PurchasingGroup": "001",
  "Supplier": "100001",
  "DocumentCurrency": "KRW",
  "CorrespncInternalReference": "AGENT-REQ-20260930-0012",
  "to_PurchaseOrderItem": {
    "results": [
      {
        "PurchaseOrderItem": "10",
        "Material": "100023",
        "Plant": "1000",
        "StorageLocation": "0001",
        "OrderQuantity": "100",
        "PurchaseOrderQuantityUnit": "EA",
        "NetPriceAmount": "1500",
        "NetPriceQuantity": "1",
        "to_ScheduleLine": {
          "results": [
            {
              "ScheduleLine": "1",
              "ScheduleLineDeliveryDate": "/Date(1792022400000)/",
              "ScheduleLineOrderQuantity": "100"
            }
          ]
        }
      }
    ]
  }
}
```

- V2 JSON에서 날짜는 `/Date(<UTC 밀리초>)/` 형식입니다. 위 값은 2026-10-15 00:00 UTC입니다.
- 계정지정 품목은 품목 안에 `"AccountAssignmentCategory": "K"`와 `to_AccountAssignment`를 추가합니다.

```json
"AccountAssignmentCategory": "K",
"to_AccountAssignment": {
  "results": [
    { "AccountAssignmentNumber": "1", "CostCenter": "10101301", "GLAccount": "61003000" }
  ]
}
```

## 응답

- 성공: `201 Created`. 본문의 `d.PurchaseOrder`가 생성된 PO 번호입니다.
- 경고: 응답 헤더 `sap-message`에 JSON으로 경고가 담겨 올 수 있습니다. 사용자에게 함께 보고합니다.
- 실패: `400` 등. 본문 `error.message.value`와 `error.innererror.errordetails[]`에 SAP 메시지가 있습니다. `09_errors_troubleshooting.md`로 해석합니다.

## 조회 예시

```http
GET .../A_PurchaseOrder('4500001234')?$expand=to_PurchaseOrderItem
GET .../A_PurchaseOrder?$filter=Supplier eq '100001' and PurchaseOrderDate ge datetime'2026-09-01T00:00:00'&$top=20
```

두 번째 요청은 중복 발주 확인(`08_validation_checklist.md`)에 씁니다.

## 시뮬레이션

V2 서비스에는 표준 "테스트 실행" 옵션이 없습니다. 운영 전에는 QAS 시스템에서 검증하고, 운영에서는 `10_agent_policy_guardrails.md`의 사용자 확인 절차로 대신합니다. 사전 검증이 꼭 필요하면 BAPI의 `TESTRUN`(`07` 문서)을 병행하세요.
