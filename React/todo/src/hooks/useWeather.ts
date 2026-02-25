import { useState, useEffect, useCallback } from "react";
import type { WeatherData, WeatherState } from "../types/weather";

const API_KEY = import.meta.env.VITE_WEATHER_API_KEY;

export function useWeather() {
    // 세 가지의 상태를 하나의 state로 관리
    const [state, setState] = useState<WeatherState>({
        data: null,
        loading: false,
        error: null,
    });

    // 위도/경도를 받아 날씨 API 호출 함수
    // useCallback: 이 함수는 의존성이 없으므로 항상 동일한 참조 유지
    const fetchWeather = useCallback(async (lat: number, lon: number) => {
        setState(prev => ({ ...prev, loading: true, error: null }));

        try {
            const url = `https://api.openweathermap.org/data/2.5/weather`
                + `?lat=${lat}&lon=${lon}&appid=${API_KEY}&units=metric&lang=kr`;

            const response = await fetch(url);

            if (!response.ok) {
                throw new Error(`날씨 데이터를 가져오지 못했습니다. (${response.status})`);
            }

            const data: WeatherData = await response.json();
            setState({ data, loading: false, error: null });
        } catch (error) {
            const message = error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.';
            setState(prev => ({ ...prev, loading: false, error: message }));
        }
    }, []);

    // 브라우저 GPS로 현재 위치를 가져오는 함수
    const getLocation = useCallback(() => {
        setState(prev => ({ ...prev, loading: true, error: null }));

        if (!navigator.geolocation) {
            setState(prev => ({
                ...prev,
                loading: false,
                error: '이 브라우저는 위치 정보를 지원하지 않습니다.'
            }));
            return;
        }

        navigator.geolocation.getCurrentPosition(
            // 성공 시
            (position) => {
                fetchWeather(position.coords.latitude, position.coords.longitude);
            },
            // 실패 시
            () => {
                fetchWeather(37.5665, 126.9780); // 서울 기본값
            }
        );
    }, []);

    // 컴포넌트가 처음 마운트 될 때 자동으로 위치 요청
    useEffect(() => {
        getLocation();
    }, []);

    return {
        ...state,
        refresh: getLocation,
    };
}