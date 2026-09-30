# SAP NERP MM 발주 에이전트용 RAG 문서 세트

SAP(NERP) MM 모듈에서 구매오더(PO)를 만드는 에이전트가 검색해 쓸 지식 문서입니다.
문서마다 YAML front matter(`id`, `title`, `tags`)를 달았고, 한 섹션이 한 주제만 다루도록 `##` / `###` 단위로 나눴습니다.

## 문서 목록

| 파일 | 내용 | 에이전트가 검색하는 상황 |
|---|---|---|
| `00_company_config_template.md` | **회사별 설정값(직접 채워야 함)** | 회사코드, 구매조직, 승인 한도 등 조회 |
| `01_po_process_overview.md` | 구매 프로세스 전체 흐름(P2P) | "발주는 어떤 순서로 해?" |
| `02_org_structure_and_master_data.md` | 조직 구조와 마스터 데이터 | 벤더·자재·플랜트 확인 |
| `03_po_field_reference.md` | PO 헤더/품목/일정/계정지정 필드 사전 | 필드 의미, 필수 여부, 형식 |
| `04_document_types_and_categories.md` | 문서유형, 품목범주, 계정지정범주 | 유형 선택 |
| `05_tcode_me21n_guide.md` | SAP GUI(ME21N) 입력 절차 | 사람이 수동으로 처리할 때 |
| `06_api_odata_purchaseorder.md` | OData API로 PO 생성 | 에이전트 도구(REST) 구현 |
| `07_bapi_po_create1.md` | BAPI_PO_CREATE1(RFC)로 PO 생성 | 에이전트 도구(RFC) 구현 |
| `08_validation_checklist.md` | 생성 전 검증 규칙 | 발주 전 사전 점검 |
| `09_errors_troubleshooting.md` | 자주 나는 오류와 조치 | 오류 메시지 해석 |
| `10_agent_policy_guardrails.md` | 에이전트 행동 규칙과 승인 정책 | 무엇을 해도 되고 안 되는지 |
| `11_request_to_po_mapping.md` | 자연어 요청 → PO 데이터 변환 예시 | 요청 파싱, few-shot |
| `12_related_tcodes_and_followups.md` | 조회·변경·후속 처리 T-code | 발주 후 조회, 변경, 입고 |
| `13_glossary.md` | 한·영 용어집 | 용어 해석 |

## 사용 전에 할 일

1. **`00_company_config_template.md`를 회사 값으로 채우세요.** 다른 문서는 SAP 표준 기준으로 작성했고, 회사 고유 값은 `<...>` 자리표시자로 남겨 두었습니다.
2. 사내 SAP 버전(ECC 6.0 / S/4HANA)과 연동 방식(OData / RFC)을 확인한 뒤 `06`, `07` 중 해당 없는 문서는 빼거나 "미사용"이라고 적어 두세요. 그래야 에이전트가 쓰지 않는 방식을 검색해 오지 않습니다.
3. 표준에서 바꾼 부분(Z 문서유형, 커스텀 필드, 승인 전략 등)은 해당 문서의 `회사 확인 필요` 표시 부분에 덧붙이세요.

## 청킹 권장값

- 분할 기준: Markdown 헤더(`##`, `###`). LangChain을 쓴다면 `MarkdownHeaderTextSplitter`가 맞습니다.
- 청크 크기: 500~1,000 토큰, 겹침 50~100 토큰.
- 메타데이터: front matter의 `id`, `tags`와 헤더 경로를 청크 메타데이터로 넣으면 필터 검색에 쓸 수 있습니다.
- `10_agent_policy_guardrails.md`는 검색에 맡기지 말고 **시스템 프롬프트에 통째로 넣는 것**을 권장합니다. 안전 규칙은 매번 적용돼야 하기 때문입니다.

## 주의

- 필드명·API 경로·BAPI 구조는 SAP 표준 기준입니다. 릴리스와 회사 설정에 따라 다를 수 있으니 운영 적용 전에 개발(DEV)·품질(QAS) 시스템에서 검증하세요.
- 계정, 비밀번호, 토큰 같은 인증 정보는 이 문서에 넣지 마세요. RAG 문서는 검색 결과로 그대로 노출될 수 있습니다.
