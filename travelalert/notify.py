"""텔레그램 / 이메일로 알림 보내기 (환경변수로 설정)."""

from __future__ import annotations

import json
import os
import smtplib
import urllib.request
from email.message import EmailMessage

TELEGRAM_LIMIT = 4000


def split_message(text: str, limit: int = TELEGRAM_LIMIT) -> list[str]:
    chunks, cur = [], ""
    for block in text.split("\n\n"):
        cand = f"{cur}\n\n{block}" if cur else block
        if len(cand) <= limit:
            cur = cand
            continue
        if cur:
            chunks.append(cur)
        while len(block) > limit:
            chunks.append(block[:limit])
            block = block[limit:]
        cur = block
    if cur:
        chunks.append(cur)
    return chunks


def send_telegram(text: str, token: str, chat_id: str) -> None:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    for chunk in split_message(text):
        body = json.dumps({"chat_id": chat_id, "text": chunk, "disable_web_page_preview": True}).encode()
        req = urllib.request.Request(url, body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            if not json.load(r).get("ok"):
                raise RuntimeError("텔레그램 전송 실패")


def send_email(text: str, subject: str, env=os.environ) -> None:
    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = env.get("EMAIL_FROM") or env["SMTP_USER"]
    msg["To"] = env["EMAIL_TO"]
    msg.set_content(text)
    port = int(env.get("SMTP_PORT", "465"))
    host = env.get("SMTP_HOST", "smtp.gmail.com")
    if port == 465:
        server = smtplib.SMTP_SSL(host, port, timeout=30)
    else:
        server = smtplib.SMTP(host, port, timeout=30)
        server.starttls()
    with server:
        server.login(env["SMTP_USER"], env["SMTP_PASSWORD"])
        server.send_message(msg)


def send_all(text: str, subject: str, env=os.environ) -> list[str]:
    """설정된 채널로 모두 보내고, 보낸 채널 이름 목록을 돌려준다."""
    sent = []
    if env.get("TELEGRAM_BOT_TOKEN") and env.get("TELEGRAM_CHAT_ID"):
        send_telegram(text, env["TELEGRAM_BOT_TOKEN"], env["TELEGRAM_CHAT_ID"])
        sent.append("telegram")
    if env.get("SMTP_USER") and env.get("SMTP_PASSWORD") and env.get("EMAIL_TO"):
        send_email(text, subject, env)
        sent.append("email")
    return sent
