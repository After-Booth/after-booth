export function countdown(deadline: string, now: number) {
  const seconds = Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1000))
  if (!seconds) return '픽업 마감'
  return `${Math.floor(seconds / 3600)}시간 ${Math.floor((seconds % 3600) / 60)}분 ${seconds % 60}초 남음`
}
