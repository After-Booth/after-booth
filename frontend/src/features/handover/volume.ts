// The contract rounds liters down, so the m³ display rounds down as well instead of toFixed's half-up
export function volumeText(liters: number) {
  return liters < 1000 ? `${liters}L` : `${(Math.floor(liters / 10) / 100).toFixed(2)}㎥`
}
