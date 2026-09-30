---
id: me21n-guide
title: ME21N 구매오더 생성 절차(SAP GUI)
tags: [ME21N, SAP GUI, 수동 생성, 절차, 구매요청 참조]
version: 0.1
---

# ME21N 구매오더 생성 절차

에이전트가 API로 처리할 수 없는 경우 사용자에게 안내하는 수동 절차입니다.

## 화면 구성

ME21N은 네 영역으로 나뉩니다.

1. **문서 개요(Document Overview)**: 왼쪽. 구매요청·계약을 끌어다 참조할 때 사용. 안 보이면 `Document Overview On` 버튼
2. **헤더(Header)**: 조직 데이터, 지급조건, 텍스트, 승인 전략
3. **품목 개요(Item Overview)**: 품목 표
4. **품목 상세(Item Detail)**: 선택한 품목의 납품일정, 계정지정, 조건(가격)

## 직접 생성 절차

1. `ME21N` 실행
2. 상단에서 문서유형 선택(기본 `NB`), 공급업체 입력
3. 헤더 → **Org. Data** 탭: 구매조직, 구매그룹, 회사코드 입력
4. 품목 개요에 한 줄씩 입력
   - 계정지정범주(A), 품목범주(I), 자재, (자재 없으면) 품목 텍스트·자재그룹, 수량, 단위, 납품일, 단가, 플랜트, 저장위치
5. 계정지정범주를 넣었으면 품목 상세 → **Account Assignment** 탭에서 비용센터·G/L 등 입력
6. 품목 상세 → **Conditions** 탭에서 가격 조건 확인
7. `Check`(점검) 버튼으로 오류 확인. 메시지 로그의 빨간 항목(오류)은 모두 해결해야 저장 가능
8. `Save` → 하단 상태 표시줄에 `Standard PO created under the number 45xxxxxxxx` 표시

## 구매요청 참조 생성

1. `ME21N` → 문서 개요 켜기
2. 선택 변형(Selection Variant)에서 **Purchase Requisitions** 선택 → PR 번호 입력 후 실행
3. 목록에서 PR 품목을 선택하고 오른쪽 장바구니(Adopt) 아이콘 클릭, 또는 끌어서 놓기
4. 벤더가 PR에 지정돼 있지 않으면 벤더 입력
5. 점검 → 저장

## 계약 참조 생성

1. 품목 개요의 `OA`(Outline Agreement) 필드에 계약 번호, `Item` 필드에 계약 품목 입력
2. 또는 문서 개요에서 **Contracts** 선택 후 Adopt
3. 계약 단가가 자동 적용됐는지 Conditions 탭에서 확인 후 저장

## 저장 후 확인

- `ME23N`으로 PO 번호 조회
- 헤더 **Release strategy** 탭: 승인 대기 여부
- **Messages** 버튼: 공급업체 출력(NEU) 생성 여부
