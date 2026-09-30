---
id: errors-troubleshooting
title: 구매오더 생성 오류와 조치 방법
tags: [오류, 에러, 트러블슈팅, 메시지, RETURN, 403, CSRF, 권한]
version: 0.1
---

# 구매오더 생성 오류와 조치 방법

SAP 메시지 문구는 로그온 언어에 따라 영어/한국어로 나옵니다. 아래는 **의미 기준**으로 정리했습니다.
메시지 클래스·번호(`RETURN-ID`, `RETURN-NUMBER`)는 시스템마다 다를 수 있으니, 실제 발생한 번호를 이 표에 덧붙여 두면 검색 정확도가 올라갑니다.

## 마스터 데이터 관련

| 메시지 요지 | 원인 | 에이전트 조치 |
|---|---|---|
| Vendor ... is blocked / 공급업체가 블록됨 | 공급업체 구매 블록 또는 삭제 표시 | 생성 중단. 구매 담당에게 확인 요청 |
| Vendor ... not created for purchasing organization | 공급업체가 구매조직으로 확장 안 됨 | 생성 중단. 마스터 담당에게 확장 요청 |
| Material ... not maintained in plant | 자재가 플랜트에 확장 안 됨 | 다른 플랜트인지 사용자에게 확인, 아니면 마스터 담당 요청 |
| Material ... has status blocked for purchasing | 자재 상태가 구매 금지 | 생성 중단, 사유 안내 |
| Source list requirement / not in source list | 소스 리스트 필수인데 공급업체가 없음 | 허용된 공급업체 후보 제시 |
| Purchasing organization ... not responsible for plant | 구매조직-플랜트 할당 없음 | 설정값(`00` 문서) 재확인 |

## 입력값 관련

| 메시지 요지 | 원인 | 에이전트 조치 |
|---|---|---|
| Enter net price / 단가를 입력하십시오 | 정보레코드 없고 단가 미입력 | 사용자에게 단가 요청 |
| Delivery date is in the past | 납기가 과거 | 날짜 재확인 |
| Unit ... not defined / 단위가 없음 | 내부 단위 코드 불일치(`EA` vs `ST`) | ISO 단위 필드 사용 또는 자재 주문단위 조회 |
| Material group required | 자재번호 없는 품목에 자재그룹 누락 | 자재그룹 요청 |
| Account assignment mandatory / Cost center required | 계정지정범주 넣고 비용센터·G/L 누락 | 비용 귀속처 요청 |
| Cost center ... is blocked / not valid on date | 비용센터 잠김 또는 유효기간 밖 | 올바른 비용센터 요청 |
| G/L account ... not defined in chart of accounts | 잘못된 G/L 또는 회사코드 불일치 | 회계 담당 확인 |
| Budget exceeded | 예산 통제 초과 | 생성 중단, 예산 담당 연결 |
| Quantity in schedule lines does not match | 일정 수량 합 ≠ 품목 수량 | 수량 재계산 |

## 연동·기술 오류

| 증상 | 원인 | 조치 |
|---|---|---|
| HTTP 403 `CSRF token validation failed` | 토큰 없음/만료, 쿠키 미전송 | 토큰 재발급 후 같은 세션으로 재시도 |
| HTTP 401 | 인증 실패 | 자격 증명 확인. 재시도 반복 금지(계정 잠김 위험) |
| HTTP 403 (CSRF 외) / `No authorization` | 사용자 권한 부족 | 권한 담당에게 요청. 우회 시도 금지 |
| HTTP 400 + `errordetails` | 비즈니스 오류 | 위 표로 해석 |
| HTTP 5xx / 타임아웃 | 시스템 장애 | **바로 재시도하지 말 것.** 먼저 요청 ID로 PO가 이미 생성됐는지 조회 |
| BAPI는 번호를 반환했는데 PO가 없음 | `BAPI_TRANSACTION_COMMIT` 누락 | 커밋 호출 추가 |
| `Purchase order ... is being processed by user ...` | 다른 사용자가 같은 문서를 편집 중(잠금) | 잠시 후 재시도 |

## 경고(Warning) 처리

경고는 저장을 막지 않지만 사용자에게 알려야 합니다. 흔한 예:

- 납기가 계획 납기(Planned delivery time)보다 짧음
- 단가가 정보레코드와 다름
- 같은 품목이 최근 발주된 적 있음

## 오류 보고 형식

사용자에게는 아래처럼 알립니다.

```
발주를 만들지 못했습니다.
- 원인: 공급업체 100001이 구매조직 1000에서 구매 블록 상태입니다.
- SAP 메시지: <원문 메시지> (<ID>/<번호>)
- 필요한 조치: 구매팀 <담당자>에게 블록 해제 여부를 확인해 주세요.
```
