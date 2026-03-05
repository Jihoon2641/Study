import { useState, useEffect } from "react";

/**
 * useState처럼 동작하지만, 값이 바뀔 때마다 localStorage에도 자동 저장한다.
 * 새로 고침해도 값 유지 - localStorage 저장
 */

export function useLocalStorage<T>(key: string, initialValue: T) {

    const [storedValue, setStoredValue] = useState<T>(() => {
        // Lazy initialization, 초깃값을 계산하는 함수를 전달해서 
        // '처음 렌더링 때만' 실행되도록 한다.
        try {
            const item = window.localStorage.getItem(key);
            return item ? (JSON.parse(item) as T) : initialValue;
        } catch {
            return initialValue;
        }
    })

    useEffect(() => {
        try {
            window.localStorage.setItem(key, JSON.stringify(storedValue));
        } catch {
            console.warn(`[useLocalStorage] ${key} 저장 실패`);
        }
    }, [key, storedValue]);

    return [storedValue, setStoredValue] as const;
}