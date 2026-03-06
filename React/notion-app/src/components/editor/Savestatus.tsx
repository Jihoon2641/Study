type Status = "idle" | "saving" | "saved";

type SaveStatusProps = {
    status: Status;
    updatedAt: number | null;
}

/**
 * 에디터 상단에 표시되는 저장 상태 인디케이터
 * idle -> 아무것도 표시 안함
 * saving -> "저장 중..." 표시
 * saved -> "최근 수정: {updatedAt}" 표시
 */
export function SaveStatus({ status, updatedAt }: SaveStatusProps) {

    if (status === "idle") return null;

    const timeLabel = updatedAt ?
        new Date(updatedAt).toLocaleTimeString("ko-KR", {
            hour: "2-digit",
            minute: "2-digit",
        }) : "";

    return (
        <div className="flex items-center gap-1.5 text-xs">
            {status === "saving" && (
                <>
                    {/* 회전하는 작은 스피너 */}
                    <span className="w-2.5 h-2.5 rounded-full border-2 border-amber-300 border-t-amber-500 animate-spin inline-block" />
                    <span className="text-amber-500">저장 중...</span>
                </>
            )}
            {status === "saved" && (
                <>
                    <span className="text-emerald-500">✓</span>
                    <span className="text-gray-400">저장됨 · {timeLabel}</span>
                </>
            )}
        </div>
    );
}