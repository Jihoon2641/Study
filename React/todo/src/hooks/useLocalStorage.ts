import { useState, useEffect } from "react";

export function useLocalStorage<T>(key: string, initialValue: T) {

    const [storedValue, setStoredValue] = useState<T>(() => {
        // 초깃값 설정 : localStorage에 저장된 값이 있으면 그걸 사용, 없으면 initialValue 사용
        try {
            const item = window.localStorage.getItem(key);
            return item ? (JSON.parse(item) as T) : initialValue;
        } catch {
            return initialValue;
        }
    });

    // storedValue가 변경될 때마다 localStorage에 저장
    useEffect(() => {
        try {
            window.localStorage.setItem(key, JSON.stringify(storedValue));
        } catch {
            console.warn(`localStorage 저장 실패: ${key}`);
        }
    }, [key, storedValue]);

    return [storedValue, setStoredValue] as const;
}