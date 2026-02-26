import { useWeather } from "../../hooks/useWeather";

// 날씨 아이콘 코드를 이모지로 변환하는 헬퍼 함수
function getWeatherEmoji(iconCode: string): string {
    const map: Record<string, string> = {
        '01d': '☀️', '01n': '🌙',
        '02d': '⛅', '02n': '🌙',
        '03d': '☁️', '03n': '☁️',
        '04d': '☁️', '04n': '☁️',
        '09d': '🌧️', '09n': '🌧️',
        '10d': '🌦️', '10n': '🌧️',
        '11d': '⛈️', '11n': '⛈️',
        '13d': '❄️', '13n': '❄️',
        '50d': '🌫️', '50n': '🌫️',
    };
    return map[iconCode] ?? '🌡️';
}

export default function WeatherWidget() {
    const { data, loading, error, refresh } = useWeather();

    if (loading) {
        return (
            <div className="flex items-center justify-center h-48 rounded-2xl bg-blue-50">
                <p className="text-blue-400 animate-pulse">날씨 정보를 불러오는 중...</p>
            </div>
        )
    }

    if (error) {
        return (
            <div className="flex flex-col items-center justify-center h-48 rounded-2xl bg-red-50 gap-3">
                <p className="text-red-400 text-sm">{error}</p>
                <button
                    onClick={refresh}
                    className="px-4 py-1.5 bg-red-100 text-red-500 rounded-lg text-sm hover:bg-red-200 transition"
                >
                    다시 시도
                </button>
            </div>
        );
    }

    if (!data) return null;

    const emoji = getWeatherEmoji(data.weather[0].icon);
    const temp = Math.round(data.main.temp);

    return (
        <div className="rounded-2xl bg-gradient-to-br from-blue-400 to-blue-600 p-6 text-white shadow-lg">
            {/* 도시명 */}
            <p className="text-sm font-medium opacity-80">
                📍 {data.name}, {data.sys.country}
            </p>

            {/* 메인 날씨 */}
            <div className="flex items-center gap-4 my-4">
                <span className="text-6xl">{emoji}</span>
                <div>
                    <p className="text-5xl font-bold">{temp}°C</p>
                    <p className="text-sm opacity-80 mt-1">{data.weather[0].description}</p>
                </div>
            </div>

            {/* 세부 정보 */}
            <div className="flex gap-4 text-sm opacity-90">
                <span>💧 습도 {data.main.humidity}%</span>
                <span>💨 바람 {data.wind.speed}m/s</span>
                <span>🌡️ 체감 {Math.round(data.main.feels_like)}°C</span>
            </div>

            {/* 새로고침 버튼 */}
            <button
                onClick={refresh}
                className="mt-4 w-full py-2 rounded-xl bg-white/20 hover:bg-white/30 transition text-sm font-medium"
            >
                새로고침
            </button>
        </div>
    );
}