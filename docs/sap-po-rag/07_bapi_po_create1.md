---
id: bapi-po-create1
title: BAPI_PO_CREATE1로 구매오더 생성(RFC)
tags: [BAPI, RFC, BAPI_PO_CREATE1, pyrfc, TESTRUN, BAPI_TRANSACTION_COMMIT, ECC, S/4HANA]
version: 0.1
---

# BAPI_PO_CREATE1로 구매오더 생성

ECC와 S/4HANA 모두에서 쓸 수 있는 표준 함수 모듈입니다. RFC로 호출합니다(Python은 `pyrfc`, Java는 JCo).

## 파라미터

| 구분 | 이름 | 구조 | 설명 |
|---|---|---|---|
| Import | `POHEADER` | `BAPIMEPOHEADER` | 헤더 값 |
| Import | `POHEADERX` | `BAPIMEPOHEADERX` | 헤더에서 값을 넣은 필드에 `'X'` |
| Import | `TESTRUN` | 문자 1 | `'X'`면 저장하지 않고 점검만(시뮬레이션) |
| Table | `POITEM` / `POITEMX` | `BAPIMEPOITEM` / `...X` | 품목과 X 플래그 |
| Table | `POSCHEDULE` / `POSCHEDULEX` | `BAPIMEPOSCHEDULE` / `...X` | 납품일정 |
| Table | `POACCOUNT` / `POACCOUNTX` | `BAPIMEPOACCOUNT` / `...X` | 계정지정 |
| Table | `POCOND` / `POCONDX` | `BAPIMEPOCOND` / `...X` | 가격 조건(단가를 조건으로 넣을 때) |
| Table | `POTEXTHEADER` | `BAPIMEPOTEXTHEADER` | 헤더 텍스트 |
| Export | `EXPPURCHASEORDER` | 문자 10 | 생성된 PO 번호 |
| Table | `RETURN` | `BAPIRET2` | 메시지(성공·경고·오류) |

## X 구조 규칙

- 값을 넣은 필드마다 X 구조의 같은 이름 필드에 `'X'`를 넣어야 SAP가 그 값을 씁니다. 빠뜨리면 값이 무시됩니다.
- 품목·일정·계정지정의 X 구조에는 **키 필드**(`PO_ITEM`, `SCHED_LINE`, `SERIAL_NO`)도 실제 값으로 넣고, `PO_ITEMX`/`SCHED_LINEX`/`SERIAL_NOX`에 `'X'`를 넣습니다.

## 반드시 지킬 순서

1. `TESTRUN='X'`로 호출 → `RETURN`에 `TYPE`이 `E`/`A`인 메시지가 없는지 확인
2. 사용자 확인(`10_agent_policy_guardrails.md`)
3. `TESTRUN` 없이 호출
4. `RETURN`에 `E`/`A`가 없으면 **`BAPI_TRANSACTION_COMMIT`(`WAIT='X'`)** 호출. 있으면 **`BAPI_TRANSACTION_ROLLBACK`** 호출
5. 커밋하지 않으면 PO 번호가 반환돼도 **DB에 저장되지 않습니다.**

두 BAPI 호출과 커밋은 **같은 RFC 연결(세션)** 에서 해야 합니다.

## RETURN 메시지 타입

| TYPE | 의미 | 처리 |
|---|---|---|
| `S` | 성공 | 계속 |
| `I` | 정보 | 계속 |
| `W` | 경고 | 계속 가능, 사용자에게 보고 |
| `E` | 오류 | 롤백, 원인 해결 |
| `A` | 중단 | 롤백, 원인 해결 |

## Python(pyrfc) 예시

```python
from pyrfc import Connection

def build_po(req):
    header = {
        "COMP_CODE": "1000", "DOC_TYPE": "NB", "VENDOR": "0000100001",
        "PURCH_ORG": "1000", "PUR_GROUP": "001", "CURRENCY": "KRW",
        "OUR_REF": req["request_id"],
    }
    headerx = {k: "X" for k in header}

    items, itemsx, sched, schedx = [], [], [], []
    for i, line in enumerate(req["lines"], start=1):
        no = str(i * 10).zfill(5)                      # 00010, 00020 ...
        item = {
            "PO_ITEM": no, "MATERIAL": line["material"].zfill(18),
            "PLANT": line["plant"], "STGE_LOC": line["sloc"],
            "QUANTITY": line["qty"], "PO_UNIT": line["unit"],
            "NET_PRICE": line["price"], "PRICE_UNIT": 1,
        }
        items.append(item)
        itemsx.append({**{k: "X" for k in item}, "PO_ITEM": no, "PO_ITEMX": "X"})
        sched.append({"PO_ITEM": no, "SCHED_LINE": "0001",
                      "DELIVERY_DATE": line["delivery_date"],   # 'YYYYMMDD'
                      "QUANTITY": line["qty"]})
        schedx.append({"PO_ITEM": no, "SCHED_LINE": "0001", "PO_ITEMX": "X",
                       "SCHED_LINEX": "X", "DELIVERY_DATE": "X", "QUANTITY": "X"})
    return dict(POHEADER=header, POHEADERX=headerx, POITEM=items, POITEMX=itemsx,
                POSCHEDULE=sched, POSCHEDULEX=schedx)

def has_error(ret):
    return any(m["TYPE"] in ("E", "A") for m in ret)

conn = Connection(ashost="<host>", sysnr="00", client="100",
                  user="<RFC 사용자>", passwd="<환경변수에서 읽기>")
params = build_po(request)

sim = conn.call("BAPI_PO_CREATE1", TESTRUN="X", **params)
if has_error(sim["RETURN"]):
    raise ValueError(sim["RETURN"])

# ... 사용자 확인 ...

res = conn.call("BAPI_PO_CREATE1", **params)
if has_error(res["RETURN"]):
    conn.call("BAPI_TRANSACTION_ROLLBACK")
    raise ValueError(res["RETURN"])
conn.call("BAPI_TRANSACTION_COMMIT", WAIT="X")
po_number = res["EXPPURCHASEORDER"]
```

## 자주 틀리는 점

- **앞자리 0**: `VENDOR`는 10자리, `MATERIAL`은 18자리 내부 형식으로 넣어야 할 수 있습니다. S/4에서 40자리 자재번호를 쓰면 `MATERIAL_LONG` 필드를 사용합니다. 변환 함수 `CONVERSION_EXIT_ALPHA_INPUT` 규칙과 같습니다.
- **단위**: `PO_UNIT`은 내부 단위 코드입니다. GUI에 `EA`로 보여도 내부값이 `ST`(독일어 Stück)인 시스템이 있습니다. 필요하면 `PO_UNIT_ISO`(ISO 코드)를 대신 씁니다.
- **단가 필드**: `NET_PRICE`가 정보레코드 가격으로 덮어써질 수 있습니다. 강제로 단가를 넣으려면 `POCOND`에 가격 조건(예: `PB00`, S/4는 `PMP0` 등 회사 설정)을 넣습니다.
- **커밋 누락**: `EXPPURCHASEORDER`에 번호가 있어도 커밋 전에는 저장되지 않습니다.
- **RFC 권한**: RFC 사용자에게 `S_RFC` 권한(BAPI와 커밋 BAPI가 속한 함수 그룹, `SE37`에서 확인)과 구매 권한(`M_BEST_BSA`, `M_BEST_EKO`, `M_BEST_EKG`, `M_BEST_WRK`)이 필요합니다.
