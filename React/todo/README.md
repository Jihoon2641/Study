App.tsx 렌더링
  └→ <WeatherWidget /> 마운트
       └→ useWeather() 호출
            └→ useEffect 실행 (마운트 1회)
                 └→ getLocation()
                      └→ GPS 권한 요청 (브라우저 팝업)
                           ├→ 허용: 실제 위치로 fetchWeather()
                           └→ 거부: 서울 좌표로 fetchWeather()
                                └→ loading: true → UI에 로딩 표시
                                     └→ fetch() API 호출
                                          ├→ 성공: data 채움 → 날씨 카드 표시
                                          └→ 실패: error 채움 → 에러 메시지 표시