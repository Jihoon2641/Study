// OpenWeatherMap API 응답 타입
export type WeatherData = {
    name: string;
    sys: {
        country: string;
    };
    main: {
        temp: number;
        feels_like: number;
        humidity: number;
    };
    weather: Array<{
        id: number;
        main: string;
        description: string;
        icon: string;
    }>;
    wind: {
        speed: number;
    };
};

// API 호출의 세 가지 상태를 하나의 타입으로 표현
export type WeatherState = {
    data: WeatherData | null; // 성공 시 데이터, 아직 없으면 null
    loading: boolean; // 로딩 중 여부
    error: string | null; // 에러 메시지, 없으면 null
};