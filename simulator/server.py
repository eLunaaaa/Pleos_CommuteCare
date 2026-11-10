#!/usr/bin/env python3
"""
Pleos CommuteCare Local Simulation Server
- 출퇴근 일정, 브리핑 데이터(날씨/캘린더/뉴스), 차량 프리컨디셔닝 상태 모의 API 제공
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

MOCK_STATE = {
    "vehicle": {
        "ignition": "OFF",
        "cabin_temp": 14.2,
        "target_temp": 22.0,
        "hvac_power": False,
        "seat_heating_level": 0,
        "speed_kmh": 0.0
    },
    "current_mode": "morning"
}

class CommuteSimulationHandler(BaseHTTPRequestHandler):
    def _send_json(self, status_code: int, data: dict):
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok", "service": "Pleos CommuteCare Simulator"})
        elif self.path == "/api/commute/briefing":
            mode = MOCK_STATE["current_mode"]
            if mode == "morning":
                data = {
                    "mode": "morning",
                    "weather": {
                        "location": "서울시 서초구 양재동 (현대자동차 본사)",
                        "temperature": 14.5,
                        "condition": "맑음 (강수확률 10%)",
                        "air_quality": "좋음 (PM2.5: 12㎍/㎥)"
                    },
                    "schedule": [
                        {"time": "09:30", "title": "모빌리티 SW 아키텍처 스탠드업", "location": "11층 회의실 A"},
                        {"time": "14:00", "title": "Pleos 신규 기능 검증 회의", "location": "온라인"}
                    ],
                    "news": [
                        {"category": "모빌리티", "title": "현대차 SDV 전환 가속화 및 OS 통합 로드맵 발표"},
                        {"category": "테크", "title": "AI 기반 개인화 차량 인포테인먼트 UX 연구 확산"}
                    ],
                    "audio": {
                        "playlist": "출근길 에너지 업 믹스",
                        "track": "Viva La Vida - Coldplay",
                        "eq_preset": "Vocal & Energy",
                        "bands": {"bass": 4.5, "mid": 2.0, "treble": 3.0}
                    }
                }
            else:
                data = {
                    "mode": "evening",
                    "weather": {
                        "location": "서울시 서초구 양재동",
                        "temperature": 18.0,
                        "condition": "구름 조금 (일몰 17:35)",
                        "air_quality": "보통 (PM2.5: 22㎍/㎥)"
                    },
                    "schedule": [
                        {"time": "19:30", "title": "동기들과 저녁 식사", "location": "강남역"}
                    ],
                    "news": [
                        {"category": "시사", "title": "오늘의 주요 경제 및 금융 마감 시황"},
                        {"category": "교통", "title": "경부고속도로 및 강남순환로 퇴근길 교통 상황 원활"}
                    ],
                    "audio": {
                        "playlist": "퇴근길 칠아웃 재즈 & 어쿠스틱",
                        "track": "Fly Me to the Moon - Frank Sinatra",
                        "eq_preset": "Warm & Acoustic Relax",
                        "bands": {"bass": 2.0, "mid": 3.5, "treble": 1.0}
                    }
                }
            self._send_json(200, data)
        elif self.path == "/api/vehicle/state":
            self._send_json(200, MOCK_STATE["vehicle"])
        else:
            self._send_json(404, {"error": "Not Found"})

    def do_POST(self):
        if self.path == "/api/vehicle/precondition":
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            payload = json.loads(body) if body else {}

            target_temp = payload.get("target_temperature", 22.0)
            seat_heating = payload.get("seat_heating_level", 2)

            MOCK_STATE["vehicle"]["ignition"] = "ON_REMOTE"
            MOCK_STATE["vehicle"]["hvac_power"] = True
            MOCK_STATE["vehicle"]["target_temp"] = target_temp
            MOCK_STATE["vehicle"]["seat_heating_level"] = seat_heating
            MOCK_STATE["vehicle"]["cabin_temp"] = target_temp

            logging.info("스마트 프리컨디셔닝 실행 완료: 목표온도=%s, 시트열선=%s", target_temp, seat_heating)
            self._send_json(200, {
                "success": True,
                "message": "Preconditioning triggered successfully",
                "state": MOCK_STATE["vehicle"]
            })
        elif self.path == "/api/commute/toggle-mode":
            MOCK_STATE["current_mode"] = "evening" if MOCK_STATE["current_mode"] == "morning" else "morning"
            logging.info("모드 전환: %s", MOCK_STATE["current_mode"])
            self._send_json(200, {"current_mode": MOCK_STATE["current_mode"]})
        else:
            self._send_json(404, {"error": "Not Found"})

def run_server(port: int = 8082):
    server_address = ("127.0.0.1", port)
    httpd = HTTPServer(server_address, CommuteSimulationHandler)
    logging.info("Pleos CommuteCare 시뮬레이션 서버 시작: http://127.0.0.1:%s", port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        logging.info("서버 종료")
        httpd.server_close()

if __name__ == "__main__":
    run_server()
