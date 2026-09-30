---
id: po-field-reference
title: 구매오더 필드 사전(GUI / OData / BAPI 대응표)
tags: [필드, 헤더, 품목, 납품일정, 계정지정, OData, BAPI, 필수값]
version: 0.1
---

# 구매오더 필드 사전

PO는 **헤더 → 품목 → 납품일정 → 계정지정** 네 단계 구조입니다.
표의 "필수"는 표준 기준이며 회사 설정(필드 선택 그룹)에 따라 달라질 수 있습니다.

## 헤더(Header)

| 의미 | GUI 라벨 | OData (`A_PurchaseOrder`) | BAPI (`POHEADER`) | 필수 | 형식/예시 |
|---|---|---|---|---|---|
| 문서유형 | Order Type | `PurchaseOrderType` | `DOC_TYPE` | O | `NB` |
| 공급업체 | Vendor / Supplier | `Supplier` | `VENDOR` | O | `0000100001` (BAPI는 앞자리 0 채움) |
| 회사코드 | Company Code | `CompanyCode` | `COMP_CODE` | O | `1000` |
| 구매조직 | Purch. Org. | `PurchasingOrganization` | `PURCH_ORG` | O | `1000` |
| 구매그룹 | Purch. Group | `PurchasingGroup` | `PUR_GROUP` | O | `001` |
| 통화 | Currency | `DocumentCurrency` | `CURRENCY` | 벤더 기본값 | `KRW` |
| 문서일자 | Doc. Date | `PurchaseOrderDate` | `DOC_DATE` | 기본값 오늘 | OData `2026-09-30T00:00:00` / BAPI `20260930` |
| 지급조건 | Payt Terms | `PaymentTerms` | `PMNTTRMS` | 벤더 기본값 | `0001` |
| 인코텀즈 | Incoterms | `IncotermsClassification` | `INCOTERMS1` | 선택 | `FOB` |
| 우리 참조 | Our Reference | `CorrespncInternalReference` | `OUR_REF` | 선택 | 에이전트 요청 ID 기록용 |
| 귀사 참조 | Your Reference | `CorrespncExternalReference` | `REF_1` | 선택 | |

## 품목(Item)

| 의미 | GUI 라벨 | OData (`A_PurchaseOrderItem`) | BAPI (`POITEM`) | 필수 | 형식/예시 |
|---|---|---|---|---|---|
| 품목번호 | Item | `PurchaseOrderItem` | `PO_ITEM` | O | `10`, `20`, `30` (10 단위) |
| 품목범주 | I (Item Cat.) | `PurchaseOrderItemCategory` | `ITEM_CAT` | 선택 | 표준은 공란(`0`) |
| 계정지정범주 | A (Acct Assgt Cat.) | `AccountAssignmentCategory` | `ACCTASSCAT` | 비재고 시 O | `K`, `F`, `P`, `A` |
| 자재번호 | Material | `Material` | `MATERIAL` (S/4: `MATERIAL_LONG`) | 재고 구매 시 O | `000000000000100023` |
| 품목 텍스트 | Short Text | `PurchaseOrderItemText` | `SHORT_TEXT` | 자재 없을 때 O | 최대 40자 |
| 자재그룹 | Matl Group | `MaterialGroup` | `MATL_GROUP` | 자재 없을 때 O | `01` |
| 플랜트 | Plnt | `Plant` | `PLANT` | O | `1000` |
| 저장위치 | SLoc | `StorageLocation` | `STGE_LOC` | 재고 입고 시 권장 | `0001` |
| 수량 | PO Quantity | `OrderQuantity` | `QUANTITY` | O | `100` |
| 주문단위 | OUn | `PurchaseOrderQuantityUnit` | `PO_UNIT` | O | `EA`, `KG`, `BOX` |
| 단가 | Net Price | `NetPriceAmount` | `NET_PRICE` | 정보레코드 없으면 O | `1500` |
| 가격단위 | Per | `NetPriceQuantity` | `PRICE_UNIT` | 기본 1 | 단가가 100개당이면 `100` |
| 세금코드 | Tax Code | `TaxCode` | `TAX_CODE` | 회사 설정 | `V1` |
| 계약번호 | Agreement | `PurchaseContract` | `AGREEMENT` | 계약 참조 시 | |
| 계약품목 | Agmt Item | `PurchaseContractItem` | `AGMT_ITEM` | 계약 참조 시 | |
| 구매요청 번호 | Purch.Req. | `PurchaseRequisition` | `PREQ_NO` | PR 참조 시 | |
| 구매요청 품목 | Req. Item | `PurchaseRequisitionItem` | `PREQ_ITEM` | PR 참조 시 | |

### 단가 계산 주의

금액 = 수량 × (단가 ÷ 가격단위). 가격단위가 `100`이고 단가가 `5,000`이면 1개당 50원입니다.
KRW·JPY처럼 소수점 없는 통화는 SAP 내부에서 자릿수를 다르게 저장하므로, 직접 테이블 값을 읽지 말고 API/BAPI가 돌려주는 값을 그대로 씁니다.

## 납품일정(Schedule Line)

| 의미 | OData (`A_PurchaseOrderScheduleLine`) | BAPI (`POSCHEDULE`) | 필수 | 형식 |
|---|---|---|---|---|
| 품목번호 | `PurchaseOrderItem` | `PO_ITEM` | O | `10` |
| 일정번호 | `ScheduleLine` | `SCHED_LINE` | O | `1` |
| 납품일 | `ScheduleLineDeliveryDate` | `DELIVERY_DATE` | O | OData `2026-10-15T00:00:00` / BAPI `20261015` |
| 수량 | `ScheduleLineOrderQuantity` | `QUANTITY` | O | 품목 수량과 합계가 같아야 함 |

분할 납품이면 일정 라인을 여러 줄 만듭니다(일정번호 1, 2, 3 ...).

## 계정지정(Account Assignment)

계정지정범주가 공란이 아닐 때만 입력합니다.

| 의미 | OData (`A_PurOrdAccountAssignment`) | BAPI (`POACCOUNT`) | 필요한 경우 |
|---|---|---|---|
| 품목번호 | `PurchaseOrderItem` | `PO_ITEM` | 항상 |
| 순번 | `AccountAssignmentNumber` | `SERIAL_NO` | 항상(`1`부터) |
| G/L 계정 | `GLAccount` | `GL_ACCOUNT` | 대부분 필수 |
| 비용센터 | `CostCenter` | `COSTCENTER` | 범주 `K` |
| 내부오더 | `OrderID` | `ORDERID` | 범주 `F` |
| WBS 요소 | `WBSElement` | `WBS_ELEMENT` | 범주 `P` |
| 자산번호 | `MasterFixedAsset` | `ASSET_NO` | 범주 `A` |
| 수량 | `Quantity` | `QUANTITY` | 여러 비용센터로 나눌 때 |

## 날짜·숫자 형식 요약

| 구분 | OData V2 | BAPI(RFC) |
|---|---|---|
| 날짜 | `"2026-10-15T00:00:00"` (Edm.DateTime) | `"20261015"` |
| 수량/금액 | 문자열 `"100"`, `"1500.00"` (Edm.Decimal) | 숫자 또는 문자열 |
| 번호(벤더·자재) | 외부 형식 `"100001"` 허용 | 내부 형식, 앞자리 0 채움 필요할 수 있음 |
